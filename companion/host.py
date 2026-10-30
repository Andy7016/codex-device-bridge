"""Outbound relay, optional LAN and strictly local pairing controls."""
import ipaddress
import json
from pathlib import Path
import random
import socket
import ssl
import threading
import secrets
import time

from cryptography.exceptions import InvalidSignature
from prototype.desktop import DesktopRecoveryService, NodeDesktopRuntime
from prototype.journal import Conflict, Denied, Journal
from .pairing import Pairing
from .configuration import load_configuration
from .connection_status import RelayStatus, RelayWaitEnded, failure_detail
from .security import certificate, fingerprint, private_address, public_id, server_context, unprotect
from .wire import TLSStream, read_frame, relay_connect, send_frame


class Host:
    def __init__(self, config_path, *, runtime_factory=NodeDesktopRuntime):
        self.config_path = Path(config_path).resolve()
        self.directory = self.config_path.parent
        self.config, policy_path = load_configuration(self.config_path)
        self.config['policy'] = str(policy_path)
        self.identity = self.config['identity']
        cert = certificate(self.identity['serverCertificatePem'])
        host_id = self.identity['hostId']
        expected_name = 'device.'+host_id[:32]+'.'+host_id[32:]+'.bridge.invalid'
        if public_id(cert) != host_id or fingerprint(cert) != self.identity['serverCertificateSha256'] or self.identity['serverName'] != expected_name:
            raise ValueError('host_identity_mismatch')
        self.private = json.loads(unprotect((self.directory/'private.dpapi').read_bytes()))
        policy = json.loads(Path(self.config['policy']).read_text(encoding='utf-8-sig'))
        self.journal = Journal(self.directory/'journal.sqlite')
        try:
            runtime = runtime_factory(self.config['policy'], self.directory/'attempts')
            self.service = DesktopRecoveryService(self.journal, runtime, policy, devices=())
            self.pairing = Pairing(self.journal, self.service, self.identity, ticket_issuer=self.issue_ticket)
            self.context_lock, self.active_lock = threading.RLock(), threading.RLock()
            self.active, self.sockets = {}, set()
            self.stop_event = threading.Event()
            self.threads = []
            self.refresh_context()
            self.listener = None
            self.status = '尚未启动'
            self.relay_status = RelayStatus()
            self.last_tls_failure = None
        except BaseException:
            self.journal.close()
            raise

    def refresh_context(self):
        with self.context_lock:
            self.trusted = self.pairing.trusted()
            self.context = server_context(self.directory/'server.pem', self.private['keyPem'].encode(), self.directory,
                                          [entry[1] for entry in self.trusted.values()])

    def issue_ticket(self, subject, *, expires=None):
        from .admission import issue_ticket
        return issue_ticket(self.private['keyPem'], self.identity['hostId'], subject, expires=expires)

    def revoked_subjects(self):
        with self.journal.lock:
            rows = self.journal.db.execute('SELECT id FROM devices WHERE enabled=0').fetchall()
        return ['phone:' + row['id'][6:] for row in rows if row['id'].startswith('phone-')]

    def connection_update(self, device):
        from .admission import sign_claims
        if self.identity.get('relay', {}).get('transport') != 'wss':
            raise ValueError('websocket_relay_required')
        self.journal.authorize_device(device)
        if device not in {entry[0] for entry in self.pairing.trusted().values()}:
            raise Denied('unknown_device')
        now = int(time.time())
        subject = 'phone:' + device[6:]
        claims = {'iss': self.identity['hostId'], 'sub': subject, 'aud': 'device-bridge-route-update-v1',
                  'iat': now, 'exp': now + 120, 'jti': secrets.token_hex(16),
                  'relay': {**self.identity['relay'], 'ticket': self.issue_ticket(subject)}}
        return 'dbroute:' + sign_claims(self.private['keyPem'], claims)

    def approve(self, invite, code, grants):
        device = self.pairing.approve(invite, code, grants)
        self.refresh_context()
        return device

    def revoke(self, device):
        self.service.revoke(device)
        self.refresh_context()
        with self.active_lock:
            streams = list(self.active.get(device, ()))
        for stream in streams:
            try:
                stream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            stream.close()

    def _track(self, stream):
        with self.active_lock:
            if self.stop_event.is_set():
                stream.close()
                raise OSError('host_stopping')
            self.sockets.add(stream)

    def serve_connection(self, transport):
        device, stream = None, None
        self._track(transport)
        try:
            with self.context_lock:
                # Enrollment is already committed when the phone observes an
                # approved result. Derive trust from that transaction rather
                # than an older cached context captured just before UI refresh.
                self.refresh_context()
                context = self.context
            stream = TLSStream(transport, context, server_side=True)
            peer = stream.getpeercert(binary_form=True)
            if peer:
                import hashlib
                with self.context_lock:
                    enrollment = self.trusted.get(hashlib.sha256(peer).hexdigest())
                if not enrollment:
                    raise Denied('unknown_leaf')
                device = enrollment[0]
                with self.active_lock:
                    self.active.setdefault(device, set()).add(transport)
                self.journal.authorize_device(device)
            request = read_frame(stream)
            if set(request) != {'method', 'params'}:
                raise ValueError('invalid_request')
            method, params = request['method'], request['params']
            if not isinstance(method, str) or not isinstance(params, dict):
                raise ValueError('invalid_request')
            if method.startswith('pair.'):
                result = self.pairing.call(method, params)
                send_frame(stream, {'ok': True, 'result': result})
                return
            if not device:
                raise Denied('client_identity_required')
            result = self.service.call(device, method, params)
            with self.service.response_guard(device):
                envelope = {'ok': True, 'result': result}
                if self.identity.get('relay', {}).get('transport') == 'wss':
                    envelope['relayTicket'] = self.issue_ticket('phone:' + device[6:])
                send_frame(stream, envelope, limit=65536)
        except ssl.SSLError as error:
            self.last_tls_failure = str(getattr(error, 'reason', 'TLS_FAILURE'))[:80]
            return
        except (OSError, EOFError):
            return
        except (Denied, InvalidSignature):
            self._error(stream, 'denied')
        except Conflict:
            self._error(stream, 'conflict')
        except (ValueError, TypeError, KeyError, RecursionError):
            self._error(stream, 'invalid_request')
        except Exception:
            self._error(stream, 'internal_error')
        finally:
            with self.active_lock:
                self.sockets.discard(transport)
                if device:
                    self.active.get(device, set()).discard(transport)
            transport.close()

    @staticmethod
    def _error(stream, code):
        if stream:
            try:
                send_frame(stream, {'ok': False, 'error': code})
            except (OSError, EOFError):
                pass

    def _relay_worker(self):
        channel = threading.get_ident()
        failures = 0
        while not self.stop_event.is_set():
            tracked = []
            def track(stream):
                tracked.append(stream)
                self._track(stream)
            try:
                self.relay_status.update(channel, 'connecting')
                preface = {'version': 1, 'role': 'host', 'hostId': self.identity['hostId'], 'token': self.private['relayToken']}
                if self.identity['relay'].get('transport') == 'wss':
                    preface['revoked'] = self.revoked_subjects()
                outer = relay_connect(self.identity['relay'], preface, on_socket=track,
                                      on_waiting=lambda: self.relay_status.update(channel, 'waiting'))
                failures = 0
                if self.stop_event.is_set():
                    break
                self.relay_status.update(channel, 'processing')
                self.serve_connection(outer)
            except RelayWaitEnded:
                failures = 0
                self.relay_status.update(channel, 'renewing')
            except (OSError, ValueError, EOFError, ImportError) as error:
                failures += 1
                self.relay_status.update(channel, 'failed', failure_detail(error))
            finally:
                for stream in tracked:
                    with self.active_lock:
                        self.sockets.discard(stream)
                    stream.close()
            self.stop_event.wait(min(15, .2*(2**min(failures, 6)))+random.random()*.15)

    @property
    def status(self):
        relay = getattr(self, 'relay_status', None)
        return relay.text() if relay and self.identity.get('relay') and self.threads else self._status

    @status.setter
    def status(self, value):
        self._status = value

    def _direct_worker(self):
        while not self.stop_event.is_set():
            try:
                transport, _ = self.listener.accept()
                self.serve_connection(transport)
            except OSError:
                if self.stop_event.is_set():
                    break

    def start(self):
        endpoints = self.identity.get('directEndpoints', [])
        if endpoints:
            address = endpoints[0]
            ip = private_address(address['host'])
            self.listener = socket.socket(socket.AF_INET6 if ip.version == 6 else socket.AF_INET, socket.SOCK_STREAM)
            self.listener.bind((str(ip), address['port']))
            self.listener.listen(2)
            self.listener.settimeout(1)
        relay_workers = [self._relay_worker]*2 if self.identity.get('relay') else []
        self.status = '未配置中继 · 本机已初始化' if not relay_workers else '中继连接准备中'
        for target in (relay_workers + ([self._direct_worker] if endpoints else [])):
            thread = threading.Thread(target=target, daemon=True)
            self.threads.append(thread)
            thread.start()

    def close(self):
        self.stop_event.set()
        if self.listener:
            self.listener.close()
        with self.active_lock:
            for stream in list(self.sockets):
                try:
                    stream.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                stream.close()
        for thread in self.threads:
            thread.join(36)
        if any(thread.is_alive() for thread in self.threads):
            raise RuntimeError('host_worker_did_not_stop')
        self.journal.close()
