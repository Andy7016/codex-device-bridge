"""Owned loopback WSS/TLS lab. Synthetic identities, <=8 peers, <=180s per case."""
import base64
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

from cryptography.hazmat.primitives import serialization
from companion.admission import issue_ticket, registration
from companion.configuration import prepare, relay_endpoint, update_configuration, write_json
from companion.host import Host
from companion.security import certificate, fingerprint, proof_bytes
from companion.wire import TLSStream, read_frame, relay_connect, send_frame
from prototype.child_job import KillOnCloseJob
from test_pairing_v2 import POLICY, Runtime, leaf, sign

ROOT = Path(__file__).resolve().parents[1]


class WssRelayTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'test-results').mkdir(exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix='wss-v03-', dir=ROOT / 'test-results'))
        relay_key, relay_pem = leaf('synthetic-wss-relay', relay=True, ca=True)
        (self.directory / 'relay.pem').write_text(relay_pem, encoding='ascii')
        (self.directory / 'relay.key').write_bytes(relay_key.private_bytes(serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        with socket.socket() as port_probe:
            port_probe.bind(('127.0.0.1', 0)); self.port = port_probe.getsockname()[1]
        write_json(self.directory / 'policy.json', POLICY)
        prepare(self.directory / 'host', self.directory / 'policy.json', 'wss://127.0.0.1:' + str(self.port),
                relay_certificate=self.directory / 'relay.pem')
        self.runtime = Runtime()
        self.host = Host(self.directory / 'host/host.json', runtime_factory=lambda *args: self.runtime)
        self.process = None
        self.start_relay()
        self.host.start()
        self.key, self.pem = leaf('synthetic-wss-phone')
        self.endpoint = None

    def start_relay(self):
        environment = dict(os.environ, PORT=str(self.port), RELAY_BIND='127.0.0.1',
            RELAY_TLS_CERT=str(self.directory / 'relay.pem'), RELAY_TLS_KEY=str(self.directory / 'relay.key'),
            RELAY_REGISTRATIONS_JSON=json.dumps(registration(self.host.identity['serverCertificatePem'], self.host.private['relayToken'])))
        self.output = (self.directory / ('relay-' + uuid.uuid4().hex + '.log')).open('wb')
        command = ['node', str(ROOT / 'relay/test/fixture-server.mjs')]
        if os.environ.get('WSS_TEST_BACKEND') == 'cloudflare':
            config = json.loads((ROOT / 'relay/wrangler.jsonc').read_text(encoding='utf8'))
            config.update(name='synthetic-device-relay', main=str(ROOT / 'relay/cloudflare.mjs'),
                          vars={'RELAY_REGISTRATIONS_JSON': environment['RELAY_REGISTRATIONS_JSON']})
            write_json(self.directory / 'wrangler.json', config)
            environment.update(XDG_CONFIG_HOME=str(ROOT / '.local/cloudflare-config'),
                WRANGLER_LOG_PATH=str(ROOT / '.local/cloudflare-logs'), WRANGLER_SEND_METRICS='false')
            command = ['node', str(ROOT / 'relay/node_modules/wrangler/bin/wrangler.js'), 'dev', '--local',
                '--config', str(self.directory / 'wrangler.json'), '--ip', '127.0.0.1', '--port', str(self.port),
                '--inspector-port', '0', '--local-protocol', 'https', '--https-cert-path', str(self.directory / 'relay.pem'),
                '--https-key-path', str(self.directory / 'relay.key'), '--persist-to', str(self.directory / 'worker-state'),
                '--show-interactive-dev-session=false', '--log-level', 'warn']
        self.process = subprocess.Popen(command, cwd=ROOT / 'relay', env=environment,
            stdin=subprocess.DEVNULL, stdout=self.output, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.job = KillOnCloseJob(self.process) if os.name == 'nt' else None
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            try:
                connection = http.client.HTTPSConnection('127.0.0.1', self.port, timeout=.5,
                    context=ssl.create_default_context(cafile=str(self.directory / 'relay.pem')))
                connection.request('GET', '/healthz')
                response = connection.getresponse()
                ready = response.status == 200
                connection.close()
                if ready:
                    return
            except OSError:
                pass
            if self.process.poll() is not None:
                raise RuntimeError('synthetic_relay_start_failed')
            time.sleep(.05)
        raise TimeoutError('synthetic_relay_start_timeout')

    def stop_relay(self):
        if self.process:
            if self.job:
                self.job.close()
            else:
                self.process.terminate()
            self.process.wait(timeout=5)
            self.process = None
        self.output.close()

    def tearDown(self):
        try:
            for device in self.host.pairing.devices():
                if device['enabled']:
                    self.host.revoke(device['device'])
            self.host.close()
        finally:
            self.stop_relay()

    def rpc(self, endpoint, method, params, authenticated=False):
        outer = relay_connect(endpoint, {'role': 'client', 'hostId': self.host.identity['hostId']}, timeout=8)
        try:
            context = ssl.create_default_context(cadata=self.host.identity['serverCertificatePem'])
            if authenticated:
                password = secrets.token_bytes(24)
                (self.directory / 'phone.pem').write_text(self.pem, encoding='ascii')
                (self.directory / 'phone.key').write_bytes(self.key.private_bytes(serialization.Encoding.PEM,
                    serialization.PrivateFormat.PKCS8, serialization.BestAvailableEncryption(password)))
                context.load_cert_chain(self.directory / 'phone.pem', self.directory / 'phone.key', password=password)
            inner = TLSStream(outer, context, server_hostname=self.host.identity['serverName'])
            send_frame(inner, {'method': method, 'params': params})
            return read_frame(inner)
        finally:
            outer.close()

    def pair(self):
        packet = self.host.pairing.invite()['packet']
        self.assertEqual(packet['schemaVersion'], 2)
        params = {k: packet[k] for k in ('inviteId', 'inviteSecret')}
        params.update(requestId=str(uuid.uuid4()), phoneName='Synthetic WSS phone', clientCertificatePem=self.pem)
        begin = self.rpc(packet['relay'], 'pair.begin', params)['result']
        proof = {k: params[k] for k in ('inviteId', 'inviteSecret', 'requestId')}
        proof['signature'] = sign(self.key, proof_bytes(packet['inviteId'], params['requestId'], packet['hostId'],
            begin['challenge'], fingerprint(certificate(self.pem))))
        self.rpc(packet['relay'], 'pair.prove', proof)
        self.device = self.host.approve(packet['inviteId'], begin['confirmationCode'], {'codex': ['read', 'send']})
        approved = self.rpc(packet['relay'], 'pair.status', proof)['result']
        self.endpoint = {**packet['relay'], 'ticket': approved['relayTicket']}
        return packet

    def test_real_double_tls_pairing_send_lost_ack_restart_and_reconcile(self):
        self.pair()
        result = self.rpc(self.endpoint, 'catalog', {}, True)
        self.assertTrue(result['ok']); self.assertIn('relayTicket', result)
        operation_id = str(uuid.uuid4())
        import companion.host as host_module
        original = host_module.send_frame
        dropped = []
        def lose_ack(stream, value, limit=8192):
            if value.get('result', {}).get('id') == operation_id and not dropped:
                dropped.append(True)
                raise EOFError('synthetic_lost_ack')
            return original(stream, value, limit)
        with patch.object(host_module, 'send_frame', side_effect=lose_ack):
            with self.assertRaises((EOFError, OSError)):
                self.rpc(self.endpoint, 'submit', {'thread': 'codex', 'id': operation_id, 'payload': {'text': 'synthetic text'}}, True)
        self.assertEqual(len(self.runtime.sends), 1)
        self.stop_relay(); self.start_relay()
        self.host.close()
        self.host = Host(self.directory / 'host/host.json', runtime_factory=lambda *args: self.runtime)
        self.host.start()
        response = self.rpc(self.endpoint, 'reconcile', {'id': operation_id}, True)
        self.assertEqual(response['result']['state'], 'completed')
        self.assertEqual(len(self.runtime.sends), 1)

    def test_bad_ticket_cannot_reach_inner_tls_or_runtime(self):
        self.pair()
        for ticket in ['x' * 120, issue_ticket(self.host.private['keyPem'], self.host.identity['hostId'],
                'phone:' + fingerprint(certificate(self.pem)), now=int(time.time()) - 60, expires=int(time.time()) - 1)]:
            with self.assertRaises(OSError):
                self.rpc({**self.endpoint, 'ticket': ticket}, 'catalog', {}, True)
        self.assertEqual(self.runtime.sends, [])

    def test_signed_connection_update_preserves_host_identity_and_device(self):
        self.pair()
        original_id = self.host.identity['hostId']
        update_configuration(self.host.config_path, relay_host='wss://replacement.invalid', use_system_trust=True)
        self.host.close()
        self.host = Host(self.directory / 'host/host.json', runtime_factory=lambda *args: self.runtime)
        update = self.host.connection_update(self.device)
        self.assertTrue(update.startswith('dbroute:'))
        claims = json.loads(base64.urlsafe_b64decode(update.split('.')[1] + '=='))
        self.assertEqual(claims['iss'], original_id)
        self.assertEqual(claims['sub'], 'phone:' + fingerprint(certificate(self.pem)))
        self.assertEqual(claims['relay']['host'], 'replacement.invalid')
        self.assertEqual(len(self.host.pairing.devices()), 1)

    def test_revoked_device_denied_after_cloud_state_is_rebuilt(self):
        self.pair()
        self.host.revoke(self.device)
        with self.assertRaises((OSError, EOFError)):
            self.rpc(self.endpoint, 'catalog', {}, True)
        self.host.close(); self.stop_relay()
        self.host = Host(self.directory / 'host/host.json', runtime_factory=lambda *args: self.runtime)
        self.start_relay(); self.host.start()
        time.sleep(.25)
        with self.assertRaises(OSError):
            self.rpc(self.endpoint, 'catalog', {}, True)
        self.assertEqual(self.runtime.sends, [])


class WssConfigurationTests(unittest.TestCase):
    def test_url_validation_has_no_downgrade_query_credentials_or_arbitrary_path(self):
        endpoint = relay_endpoint('wss://device-bridge-relay.example:443/')
        self.assertEqual(endpoint['transport'], 'wss')
        for value in ['ws://example.com', 'https://example.com', 'wss://user:secret@example.com',
                      'wss://example.com/?token=x', 'wss://example.com/wrong', 'wss://example.com\t']:
            with self.assertRaises(ValueError): relay_endpoint(value)


if __name__ == '__main__':
    unittest.main()
