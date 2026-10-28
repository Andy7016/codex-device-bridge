"""Prepare a private, scoped mTLS host/profile bundle. Never starts a listener."""

import argparse
import base64
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import secrets
import shutil
import ssl
import subprocess
import uuid


def prepare(source_policy, output, host='127.0.0.1', port=8765, *, free_text=False, operation_id=None):
    output = Path(output).resolve()
    policy = json.loads(Path(source_policy).read_text(encoding='utf-8-sig'))
    original = policy['targets']['codex']
    if original.get('kind') != 'codex' or original.get('hostId') != 'local':
        raise ValueError('authorized local Codex target required')
    address = ipaddress.ip_address(host)
    if not (address.is_loopback or host == '10.0.2.2'):
        raise ValueError('v1 supports loopback/USB or Android emulator only')
    if not 1 <= port <= 65535:
        raise ValueError('invalid port')
    output.mkdir(parents=True, exist_ok=False)
    openssl = shutil.which('openssl')
    if not openssl:
        raise RuntimeError('OpenSSL CLI required')
    identity = 'android-' + uuid.uuid4().hex
    for name in ('server', 'phone'):
        command = [openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '30',
                   '-subj', '/CN=DeviceBridge-'+name, '-keyout', str(output/(name+'.key')), '-out', str(output/(name+'.pem'))]
        if name == 'server':
            command += ['-addext', f'subjectAltName=IP:{host},IP:127.0.0.1']
        subprocess.run(command, check=True, capture_output=True, timeout=20, cwd=output)
    password = secrets.token_urlsafe(24)
    environment = dict(os.environ, DEVICE_BRIDGE_P12_PASSWORD=password)
    subprocess.run([openssl, 'pkcs12', '-export', '-inkey', str(output/'phone.key'), '-in', str(output/'phone.pem'),
                    '-out', str(output/'phone.p12'), '-name', 'device-bridge', '-keypbe', 'PBE-SHA1-3DES',
                    '-certpbe', 'PBE-SHA1-3DES', '-macalg', 'SHA1', '-passout', 'env:DEVICE_BRIDGE_P12_PASSWORD'],
                   env=environment, check=True, capture_output=True, timeout=20, cwd=output)
    scoped = {'targets': {'codex': {key: original[key] for key in ('threadId', 'hostId', 'kind')}}}
    scoped['targets']['codex'].update({'displayName': 'Codex 测试会话', 'rights': ['read', 'send'],
        'allowedMessages': original.get('allowedMessages', [])[:1], 'testFixture': original.get('testFixture', False),
        'allowFreeText': free_text})
    (output/'policy.json').write_text(json.dumps(scoped, ensure_ascii=False), encoding='utf8')
    config = {'policy': str(output/'policy.json'), 'journal': str(output/'journal.sqlite'), 'attempts': str(output/'attempts'),
              'serverCert': str(output/'server.pem'), 'serverKey': str(output/'server.key'), 'port': port,
              'peers': [{'device': identity, 'certificate': str(output/'phone.pem')}],
              'bootstrapGrants': [{'device': identity, 'thread': 'codex', 'rights': ['read', 'send']}]}
    (output/'host.json').write_text(json.dumps(config), encoding='utf8')
    server_pem = (output/'server.pem').read_text(encoding='ascii')
    profile = {'schemaVersion': 1, 'displayName': '我的电脑 · Codex', 'host': host, 'port': port,
               'serverCertificatePem': server_pem,
               'serverCertificateSha256': hashlib.sha256(ssl.PEM_cert_to_DER_cert(server_pem)).hexdigest(),
               'clientPkcs12Base64': base64.b64encode((output/'phone.p12').read_bytes()).decode(),
               'clientPkcs12Password': password}
    (output/'device-bridge.connection.json').write_text(json.dumps(profile, ensure_ascii=False), encoding='utf8')
    if operation_id:
        (output/'bridge-test.json').write_text(json.dumps({'profile': profile, 'operationId': operation_id,
            'message': scoped['targets']['codex']['allowedMessages'][0]}), encoding='utf8')
    return {'output': str(output), 'device': identity, 'freeText': free_text, 'startedListener': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--policy', default='.local/desktop-target-policy.json')
    parser.add_argument('--output', required=True)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', default=8765, type=int)
    parser.add_argument('--free-text', action='store_true', help='explicitly authorize ordinary user text for this scoped host')
    args = parser.parse_args()
    print(json.dumps(prepare(args.policy, args.output, args.host, args.port, free_text=args.free_text), ensure_ascii=False))
