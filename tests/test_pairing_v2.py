"""Isolated pairing/relay attacks; synthetic data, loopback only, <=32 peers."""
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

try:
    from cryptography import x509
except ImportError:
    raise unittest.SkipTest('install companion/requirements.txt for pairing tests')
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

from companion.host import Host
from companion.pairing import Pairing
from companion.relay import RelayServer
from companion.security import certificate, confirmation, fingerprint, generate_identity, proof_bytes, protect, sha256, unprotect, verify_phone
from companion.wire import TLSStream, read_frame, relay_connect, send_frame
from prototype.desktop import DesktopRecoveryService, DesktopUnavailable
from prototype.journal import Conflict, Denied, Journal
from tools.prepare_companion import prepare

ROOT = Path(__file__).resolve().parents[1]
POLICY = {'targets': {'codex': {'kind': 'codex', 'hostId': 'local', 'threadId': 'synthetic-thread',
          'displayName': 'Synthetic lab', 'rights': ['read', 'send'], 'allowedMessages': ['synthetic text']}}}


def leaf(name='phone', relay=False, ca=False, android_format=False):
    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    now = datetime.now(timezone.utc)
    builder = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
               .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=2))
               .not_valid_after(now+timedelta(days=1)))
    if android_format:
        builder = builder.add_extension(x509.KeyUsage(True, False, False, False, False, False, False, False, False), False)
    else:
        builder = builder.add_extension(x509.BasicConstraints(ca=ca, path_length=None), True)
    if relay:
        import ipaddress
        builder = builder.add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1')), x509.IPAddress(ipaddress.ip_address('10.0.2.2'))]), False)
    cert = builder.sign(key, hashes.SHA256())
    return key, cert.public_bytes(serialization.Encoding.PEM).decode()


def sign(key, data):
    return base64.b64encode(key.sign(data, ec.ECDSA(hashes.SHA256()))).decode()


class Runtime:
    def __init__(self, *args):
        self.sends = []
    def send(self, operation, guard):
        with guard():
            self.sends.append(operation['binding'])
        return {'delivery': 'observed', 'turnId': 'synthetic-turn', 'turnStatus': 'completed', 'evidence': 'runtime-message-id'}
    def inspect(self, operation):
        return {'observed': True, 'targetId': operation['binding']['target'], 'messageId': operation['binding']['message_id'], 'turnId': 'synthetic-turn', 'turnStatus': 'completed'}
    def history(self, *args):
        return {'messages': [{'id': 'synthetic-reply', 'role': 'assistant', 'text': 'Synthetic relay fixture', 'status': 'completed'}],
                'hasMore': False, 'nextBefore': None, 'historyComplete': True, 'runtimeStatus': 'idle'}


class PairingTests(unittest.TestCase):
    def setUp(self):
        root = ROOT/'test-results'
        root.mkdir(exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix='pair-v2-', dir=root))
        self.journal = Journal(self.directory/'journal.sqlite')
        self.service = DesktopRecoveryService(self.journal, Runtime(), POLICY, devices=())
        self.now = time.time()
        host_id, name, pem, _ = generate_identity()
        self.identity = {'hostId': host_id, 'serverName': name, 'serverCertificatePem': pem,
                         'serverCertificateSha256': fingerprint(certificate(pem)), 'displayName': 'Synthetic PC',
                         'relay': {'host': '127.0.0.1', 'port': 443, 'certificatePem': None}, 'directEndpoints': []}
        self.pairing = Pairing(self.journal, self.service, self.identity, clock=lambda: self.now)
        self.invite = self.pairing.invite()['packet']
        self.key, self.pem = leaf()
        self.request = {'inviteId': self.invite['inviteId'], 'inviteSecret': self.invite['inviteSecret'], 'requestId': str(uuid.uuid4()),
                        'phoneName': 'Synthetic phone', 'clientCertificatePem': self.pem}
    def tearDown(self):
        self.journal.close()
    def begin(self):
        return self.pairing.call('pair.begin', self.request)
    def proof(self, challenge=None, key=None):
        challenge = challenge or self.begin()['challenge']
        data = proof_bytes(self.request['inviteId'], self.request['requestId'], self.identity['hostId'], challenge, fingerprint(certificate(self.pem)))
        return {k: self.request[k] for k in ('inviteId', 'inviteSecret', 'requestId')} | {'signature': sign(key or self.key, data)}
    def approve(self):
        result = self.pairing.call('pair.prove', self.proof())
        return self.pairing.approve(self.invite['inviteId'], result['confirmationCode'], {'codex': ['read', 'send']})
    def test_begin_lost_ack_same_request_is_idempotent(self):
        first = self.begin()
        self.assertEqual(self.begin(), first)
        self.assertEqual(len(self.pairing.pending()), 0)
    def test_invitation_binds_one_key_and_one_request(self):
        self.begin()
        with self.assertRaises(Conflict):
            self.pairing.call('pair.begin', self.request | {'requestId': str(uuid.uuid4())})
        _, another = leaf()
        with self.assertRaises(Conflict):
            self.pairing.call('pair.begin', self.request | {'clientCertificatePem': another})
    def test_concurrent_invitation_claim_has_one_winner(self):
        def claim(request_id):
            try:
                self.pairing.call('pair.begin', self.request | {'requestId': request_id})
                return True
            except Conflict:
                return False
        with ThreadPoolExecutor(max_workers=4) as pool:
            outcomes = list(pool.map(claim, [str(uuid.uuid4()) for _ in range(4)]))
        self.assertEqual(sum(outcomes), 1)
    def test_wrong_secret_and_expired_invite_denied(self):
        with self.assertRaises(Denied):
            self.pairing.call('pair.begin', self.request | {'inviteSecret': '0'*64})
        self.now += 121
        with self.assertRaises(Denied):
            self.begin()
    def test_forged_proof_cannot_reach_local_approval(self):
        key, _ = leaf()
        with self.assertRaises(Exception):
            self.pairing.call('pair.prove', self.proof(key=key))
        self.assertEqual(self.pairing.pending(), [])
    def test_confirmation_and_grants_are_local_exact_and_scoped(self):
        result = self.pairing.call('pair.prove', self.proof())
        with self.assertRaises(Denied):
            self.pairing.approve(self.invite['inviteId'], 'not-the-code', {'codex': ['read']})
        with self.assertRaises(Denied):
            self.pairing.approve(self.invite['inviteId'], result['confirmationCode'], {'codex': ['read', 'approve']})
        with self.assertRaises(ValueError):
            self.pairing.call('pair.approve', {})
    def test_approval_ack_loss_fresh_signature_status_and_revoke(self):
        device = self.approve()
        self.assertEqual(self.pairing.call('pair.status', self.proof())['state'], 'approved')
        self.assertEqual(self.service.call(device, 'catalog', {})['sessions'][0]['label'], 'codex')
        self.service.revoke(device)
        with self.assertRaises(Denied):
            self.pairing.call('pair.status', self.proof())
        self.assertEqual(self.journal.db.execute('SELECT enabled FROM devices WHERE id=?', (device,)).fetchone()[0], 0)
    def test_approved_restart_survives_unconfirmed_restart_does_not(self):
        device = self.approve()
        original = self.request.copy()
        self.pairing = Pairing(self.journal, self.service, self.identity, clock=lambda: self.now)
        self.assertEqual(self.pairing.call('pair.status', self.proof())['state'], 'approved')
        self.invite = self.pairing.invite()['packet']
        self.request = self.request | {k: self.invite[k] for k in ('inviteId', 'inviteSecret')}
        self.request['requestId'] = str(uuid.uuid4())
        self.begin()
        self.pairing = Pairing(self.journal, self.service, self.identity, clock=lambda: self.now)
        with self.assertRaises(Denied):
            self.begin()
        self.journal.authorize_device(device)
    def test_single_approval_transaction_and_no_identity_reactivation(self):
        device = self.approve()
        self.service.revoke(device)
        self.invite = self.pairing.invite()['packet']
        self.request |= {k: self.invite[k] for k in ('inviteId', 'inviteSecret')}
        self.request['requestId'] = str(uuid.uuid4())
        self.pairing.call('pair.prove', self.proof())
        with self.assertRaises(Conflict):
            self.pairing.approve(self.invite['inviteId'], self.begin()['confirmationCode'], {'codex': ['read']})
    def test_pending_registration_has_no_authority_and_readonly_stays_readonly(self):
        result = self.pairing.call('pair.prove', self.proof())
        phone = 'phone-'+fingerprint(certificate(self.pem))
        with self.assertRaises(Denied):
            self.journal.authorize_device(phone)
        self.pairing.approve(self.invite['inviteId'], result['confirmationCode'], {'codex': ['read']})
        self.assertFalse(self.service.call(phone, 'catalog', {})['sessions'][0]['canSend'])
    def test_phone_ca_certificates_rejected(self):
        _, ca = leaf(ca=True)
        with self.assertRaises(ValueError):
            verify_phone(ca)
    def test_hostname_labels_and_private_dpapi_roundtrip(self):
        self.assertTrue(all(len(label) <= 63 for label in self.identity['serverName'].split('.')))
        secret = b'synthetic-host-secret'
        encrypted = protect(secret)
        self.assertNotIn(secret, encrypted)
        self.assertEqual(unprotect(encrypted), secret)

    def test_abrupt_tls_load_leaves_only_encrypted_temporary_key(self):
        script = '''import os,ssl,sys
from pathlib import Path
from companion.security import generate_identity,server_context
directory=Path(sys.argv[1])
_,_,cert,key=generate_identity()
(directory/'crash-cert.pem').write_text(cert)
ssl.SSLContext.load_cert_chain=lambda *args,**kwargs: os._exit(17)
server_context(directory/'crash-cert.pem',key,directory)
'''
        child = subprocess.run([sys.executable, '-c', script, str(self.directory)], cwd=ROOT, timeout=10)
        self.assertEqual(child.returncode, 17)
        artifacts = list(self.directory.glob('.tls-key-*'))
        self.assertEqual(len(artifacts), 1)
        contents = artifacts[0].read_bytes()
        self.assertIn(b'BEGIN ENCRYPTED PRIVATE KEY', contents)
        self.assertNotIn(b'BEGIN PRIVATE KEY', contents)
        with self.assertRaises(TypeError):
            serialization.load_pem_private_key(contents, password=None)


class RelayFixture(unittest.TestCase):
    def setUp(self):
        root = ROOT/'test-results'
        root.mkdir(exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix='relay-v2-', dir=root))
        key, self.pem = leaf('relay', relay=True)
        (self.directory/'relay.pem').write_text(self.pem)
        (self.directory/'relay.key').write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(self.directory/'relay.pem', self.directory/'relay.key')
        self.token, self.host_id = secrets.token_hex(32), secrets.token_hex(32)
        self.server = RelayServer(('127.0.0.1', 0), context, {self.host_id: sha256(self.token.encode())})
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .02})
        self.thread.start()
        self.endpoint = {'host': '127.0.0.1', 'port': self.server.server_address[1], 'certificatePem': self.pem}
    def tearDown(self):
        self.server.stop()
        self.thread.join(3)
    def waiter(self):
        return relay_connect(self.endpoint, {'version': 1, 'role': 'host', 'hostId': self.host_id, 'token': self.token})
    def client(self):
        return relay_connect(self.endpoint, {'version': 1, 'role': 'client', 'hostId': self.host_id}, timeout=3)
    def wait_registered(self):
        for _ in range(100):
            with self.server.lock:
                if self.server.waiters.get(self.host_id):
                    return
            time.sleep(.01)
        self.fail('host did not register')


class RelayTests(RelayFixture):
    def test_expired_waiter_never_receives_successful_readiness(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(self.waiter)
            self.wait_registered()
            with self.server.lock:
                self.server.waiters[self.host_id][0].expires = time.monotonic()-1
            with self.assertRaises(ConnectionError):
                self.client()
            with self.assertRaises((EOFError, OSError)):
                future.result(3)

    def test_wrong_host_token_or_missing_owner_rejected(self):
        with self.assertRaises(ConnectionError):
            relay_connect(self.endpoint, {'version': 1, 'role': 'host', 'hostId': self.host_id, 'token': '0'*64}, timeout=3)
        with self.assertRaises(ConnectionError):
            self.client()
    def test_opaque_bidirectional_forwarding_and_slot_reuse(self):
        for _ in range(2):
            with ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(self.waiter)
                self.wait_registered()
                client = self.client()
                host = future.result(3)
            try:
                client.sendall(b'opaque synthetic ciphertext')
                self.assertEqual(host.recv(128), b'opaque synthetic ciphertext')
                host.sendall(b'return ciphertext')
                self.assertEqual(client.recv(128), b'return ciphertext')
            finally:
                client.close(); host.close()
    def test_wrong_outer_certificate_does_not_register(self):
        _, wrong = leaf('wrong', relay=True)
        with self.assertRaises(ssl.SSLError):
            relay_connect(self.endpoint | {'certificatePem': wrong}, {'version': 1, 'role': 'host', 'hostId': self.host_id, 'token': self.token})
        with self.server.lock:
            self.assertFalse(self.server.waiters.get(self.host_id))


class IntegratedRelayTests(RelayFixture):
    def setUp(self):
        super().setUp()
        (self.directory/'policy.json').write_text(json.dumps(POLICY))
        prepare(self.directory/'host', self.directory/'policy.json', '127.0.0.1', self.endpoint['port'], self.directory/'relay.pem')
        self.host = Host(self.directory/'host'/'host.json', runtime_factory=Runtime)
        self.host_id = self.host.identity['hostId']
        from companion.security import unprotect
        token = json.loads(unprotect((self.directory/'host'/'private.dpapi').read_bytes()))['relayToken']
        self.server.tokens = {self.host_id: sha256(token.encode())}
        self.host.start()
        self.wait_registered()
        self.phone_key, self.phone_pem = leaf(android_format=True)
        (self.directory/'phone.pem').write_text(self.phone_pem)
        (self.directory/'phone.key').write_bytes(self.phone_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        self.packet = self.host.pairing.invite()['packet']
        self.request_id = str(uuid.uuid4())
    def tearDown(self):
        try:
            self.host.close()
        finally:
            super().tearDown()
    def rpc(self, method, params, *, authenticated=False):
        context = ssl.create_default_context(cadata=self.host.identity['serverCertificatePem'])
        if authenticated:
            context.load_cert_chain(self.directory/'phone.pem', self.directory/'phone.key')
        self.wait_registered()
        outer = self.client()
        stream = None
        try:
            stream = TLSStream(outer, context, server_hostname=self.host.identity['serverName'])
            send_frame(stream, {'method': method, 'params': params})
            return read_frame(stream)
        finally:
            outer.close()
    def enroll(self, grants=None):
        begin = {'inviteId': self.packet['inviteId'], 'inviteSecret': self.packet['inviteSecret'], 'requestId': self.request_id,
                 'phoneName': 'Synthetic TLS phone', 'clientCertificatePem': self.phone_pem}
        reply = self.rpc('pair.begin', begin)
        self.assertTrue(reply['ok'], reply)
        self.challenge = reply['result']['challenge']
        self.pair_params = {k: begin[k] for k in ('inviteId', 'inviteSecret', 'requestId')}
        proof = proof_bytes(begin['inviteId'], self.request_id, self.host_id, self.challenge, fingerprint(certificate(self.phone_pem)))
        self.pair_params['signature'] = sign(self.phone_key, proof)
        pending = self.rpc('pair.prove', self.pair_params)
        self.assertEqual(pending['result']['state'], 'pending')
        self.device = self.host.approve(begin['inviteId'], pending['result']['confirmationCode'], grants or {'codex': ['read', 'send']})
        return self.rpc('pair.status', self.pair_params)
    def test_real_nested_tls_enrollment_and_authenticated_rpc(self):
        self.assertEqual(self.enroll()['result']['state'], 'approved')
        self.assertTrue(self.rpc('catalog', {}, authenticated=True)['ok'])
        reply = self.rpc('history', {'thread': 'codex', 'before': None, 'limit': 8}, authenticated=True)
        self.assertEqual(reply['result']['messages'][0]['text'], 'Synthetic relay fixture')
        deadline = time.monotonic()+1
        while '会话请求已回复' not in self.host.phone_status.text() and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual(self.host.phone_status.arrivals, 5)
        self.assertIn('会话请求已回复', self.host.phone_status.text())
    def test_anonymous_rpc_and_unregistered_client_rejected(self):
        self.assertEqual(self.rpc('catalog', {}), {'ok': False, 'error': 'denied'})
        with self.assertRaises((OSError, EOFError)):
            self.rpc('catalog', {}, authenticated=True)

    def test_distinct_phone_certificates_with_same_subject_both_remain_usable(self):
        self.assertEqual(self.enroll()['result']['state'], 'approved')
        first_device = self.device
        first_key = (self.directory/'phone.key').read_bytes()
        first_pem = self.phone_pem
        self.phone_key, self.phone_pem = leaf(android_format=True)
        (self.directory/'phone.pem').write_text(self.phone_pem)
        (self.directory/'phone.key').write_bytes(self.phone_key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        self.packet = self.host.pairing.invite()['packet']
        self.request_id = str(uuid.uuid4())
        self.assertEqual(self.enroll()['result']['state'], 'approved')
        second_key = (self.directory/'phone.key').read_bytes()
        second_pem = self.phone_pem
        self.assertTrue(self.rpc('catalog', {}, authenticated=True)['ok'])
        (self.directory/'phone.pem').write_text(first_pem)
        (self.directory/'phone.key').write_bytes(first_key)
        self.assertTrue(self.rpc('catalog', {}, authenticated=True)['ok'])
        # A third same-subject leaf is never trusted just because its name
        # matches. Revoking one enrolled leaf must leave the other usable.
        unknown_key, unknown_pem = leaf(android_format=True)
        (self.directory/'phone.pem').write_text(unknown_pem)
        (self.directory/'phone.key').write_bytes(unknown_key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        with self.assertRaises((OSError, EOFError)):
            self.rpc('catalog', {}, authenticated=True)
        self.host.revoke(first_device)
        (self.directory/'phone.pem').write_text(first_pem)
        (self.directory/'phone.key').write_bytes(first_key)
        with self.assertRaises((OSError, EOFError)):
            self.rpc('catalog', {}, authenticated=True)
        (self.directory/'phone.pem').write_text(second_pem)
        (self.directory/'phone.key').write_bytes(second_key)
        self.assertTrue(self.rpc('catalog', {}, authenticated=True)['ok'])

    def test_unavailable_history_has_a_fixed_error_instead_of_internal_failure(self):
        self.enroll()
        runtime = self.host.service.runtime
        original = runtime.history
        try:
            for code in ('desktop_unavailable', 'desktop_history_too_large'):
                def unavailable(*args):
                    raise DesktopUnavailable('synthetic non-public detail', code=code)
                runtime.history = unavailable
                reply = self.rpc('history', {'thread': 'codex', 'before': None, 'limit': 1}, authenticated=True)
                self.assertEqual(reply, {'ok': False, 'error': code})
                self.assertNotIn('本机请求处理失败', self.host.phone_status.text())
        finally:
            runtime.history = original

    def test_shutdown_rejects_a_late_outbound_socket_registration(self):
        self.host.close()
        socket_a, socket_b = socket.socketpair()
        try:
            with self.assertRaises(OSError):
                self.host._track(socket_a)
            self.assertEqual(socket_a.fileno(), -1)
        finally:
            socket_a.close(); socket_b.close()
        # A fresh coordinator is needed for the common teardown path.
        self.host = Host(self.directory/'host'/'host.json', runtime_factory=Runtime)

    def test_relay_restart_preserves_pairing_and_does_not_resend(self):
        self.enroll()
        operation = {'thread': 'codex', 'id': 'relay-restart-op', 'payload': {'text': 'synthetic text'}}
        self.assertEqual(self.rpc('submit', operation, authenticated=True)['result']['state'], 'completed')
        port, context, registrations = self.server.server_address[1], self.server.context, self.server.tokens.copy()
        self.server.stop(); self.thread.join(3)
        self.server = RelayServer(('127.0.0.1', port), context, registrations)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .02})
        self.thread.start()
        self.wait_registered()
        result = self.rpc('reconcile', {'id': operation['id']}, authenticated=True)
        self.assertEqual(result['result']['state'], 'completed')
        self.assertEqual(len(self.host.service.runtime.sends), 1)
        self.journal_identity = self.host.journal.authorize_device(self.device)
    def test_relay_disconnect_reconnect_same_device_and_operation(self):
        self.enroll()
        operation = {'thread': 'codex', 'id': 'synthetic-stable-id', 'payload': {'text': 'synthetic text'}}
        sent = self.rpc('submit', operation, authenticated=True)
        self.assertEqual(sent['result']['state'], 'completed')
        self.host.close()
        self.host = Host(self.directory/'host'/'host.json', runtime_factory=Runtime)
        self.host.start()
        status = self.rpc('status', {'id': operation['id']}, authenticated=True)
        self.assertEqual(status['result']['state'], 'completed')
        self.assertEqual(self.host.service.runtime.sends, [])
        self.host.revoke(self.device)
        with self.assertRaises((OSError, EOFError)):
            self.rpc('catalog', {}, authenticated=True)
        self.assertEqual(self.rpc('pair.status', self.pair_params), {'ok': False, 'error': 'denied'})


if __name__ == '__main__':
    unittest.main()
