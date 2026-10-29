"""Synthetic Android relay fixture; never connects to a desktop runtime."""
import argparse
from datetime import datetime, timedelta, timezone
import ipaddress
import json
import queue
from pathlib import Path
import ssl
import sys
import threading
import time
import uuid
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
import qrcode
import companion.host as host_module
from companion.host import Host
from companion.relay import RelayServer
from companion.security import sha256, unprotect
from tools.prepare_companion import prepare


class SyntheticRuntime:
    def __init__(self, *args):
        self.sends = []
    def send(self, operation, guard):
        with guard():
            self.sends.append(operation['binding'].copy())
        return {'delivery': 'observed', 'turnId': 'synthetic-turn', 'turnStatus': 'completed', 'evidence': 'runtime-message-id'}
    def inspect(self, operation):
        return {'observed': True, 'targetId': operation['binding']['target'], 'messageId': operation['binding']['message_id'],
                'turnId': 'synthetic-turn', 'turnStatus': 'completed'}
    def history(self, *args):
        return {'messages': [{'id': 'synthetic-user', 'role': 'user', 'text': 'synthetic text', 'status': 'completed'},
                             {'id': 'synthetic-reply', 'role': 'assistant', 'text': '配对与加密中继测试成功。此处为隔离合成会话。', 'status': 'completed'}],
                'nextBefore': None, 'hasMore': False, 'runtimeStatus': 'idle', 'historyComplete': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    directory = Path(parser.parse_args().output).resolve()
    directory.mkdir(parents=True, exist_ok=False)
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Synthetic relay')])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=1))
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]), False).sign(key, hashes.SHA256()))
    (directory/'relay.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (directory/'relay.key').write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(directory/'relay.pem', directory/'relay.key')
    relay = RelayServer(('127.0.0.1', 0), context, {})
    relay_thread = threading.Thread(target=relay.serve_forever, kwargs={'poll_interval': .05})
    relay_thread.start()
    port = relay.server_address[1]
    policy = {'targets': {'codex': {'kind': 'codex', 'hostId': 'local', 'threadId': 'synthetic-pairing-fixture',
              'displayName': '扫码中继测试 · 合成会话', 'rights': ['read', 'send'], 'allowedMessages': ['synthetic text']}}}
    (directory/'policy.json').write_text(json.dumps(policy, ensure_ascii=False), encoding='utf8')
    prepare(directory/'host', directory/'policy.json', '127.0.0.1', port, directory/'relay.pem', display_name='中继测试电脑 · 合成数据')
    host = Host(directory/'host'/'host.json', runtime_factory=SyntheticRuntime)
    private = json.loads(unprotect((directory/'host'/'private.dpapi').read_bytes()))
    relay.tokens = {host.identity['hostId']: sha256(private['relayToken'].encode())}
    operation_id, dropped = str(uuid.uuid4()), threading.Event()
    real_send = host_module.send_frame
    def fault_send(stream, value, limit=8192):
        result = value.get('result', {})
        if result.get('id') == operation_id and result.get('state') == 'completed' and not dropped.is_set():
            dropped.set()
            raise EOFError('synthetic_lost_ack')
        return real_send(stream, value, limit)
    host_module.send_frame = fault_send
    host.start()
    stopped = threading.Event()
    def publish():
        invitation = host.pairing.invite()
        (directory/'qr.txt').write_text(invitation['text'], encoding='ascii')
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, box_size=5, border=4)
        qr.add_data(invitation['text']); qr.make(fit=True)
        qr.make_image().save(directory/'qr.png')
        asset = {'qrText': invitation['text'], 'operationId': operation_id, 'message': 'synthetic text', 'hostId': host.identity['hostId']}
        (directory/'pairing-lab.json').write_text(json.dumps(asset), encoding='utf8')
        print(json.dumps({'invitationReady': True, 'asset': str(directory/'pairing-lab.json')}), flush=True)
    def approve_loop():
        while not stopped.wait(.1):
            for pending in host.pairing.pending():
                try:
                    host.approve(pending['id'], pending['code'], {'codex': ['read', 'send']})
                except Exception:
                    pass
    approver = threading.Thread(target=approve_loop, daemon=True)
    approver.start()
    print(json.dumps({'ready': True, 'port': port, 'syntheticOnly': True}), flush=True)
    publish()
    commands = queue.Queue()
    def read_commands():
        for line in sys.stdin:
            commands.put(line.strip())
        commands.put('shutdown')
    threading.Thread(target=read_commands, daemon=True).start()
    deadline = time.monotonic()+1800
    try:
        while time.monotonic() < deadline:
            try:
                command = commands.get(timeout=.5)
            except queue.Empty:
                continue
            if command == 'shutdown':
                break
            if command == 'invite':
                publish()
            if command == 'status':
                print(json.dumps({'runtimeSends': len(host.service.runtime.sends), 'droppedAck': dropped.is_set(), 'devices': len(host.pairing.devices()), 'tlsFailure': host.last_tls_failure}), flush=True)
            if command == 'reset-relay':
                registrations = relay.tokens.copy()
                relay.stop(); relay_thread.join(3)
                relay = RelayServer(('127.0.0.1', port), context, registrations)
                relay_thread = threading.Thread(target=relay.serve_forever, kwargs={'poll_interval': .05})
                relay_thread.start()
                print('{"relayRestarted":true}', flush=True)
            if command == 'revoke':
                for device in host.pairing.devices():
                    if device['enabled']:
                        host.revoke(device['device'])
                print('{"revoked":true}', flush=True)
    finally:
        stopped.set(); approver.join(2)
        host.close(); relay.stop(); relay_thread.join(3)
        print(json.dumps({'stopped': True, 'runtimeSends': len(host.service.runtime.sends), 'droppedAck': dropped.is_set()}), flush=True)


if __name__ == '__main__':
    main()
