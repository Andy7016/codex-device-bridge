"""Prepare a Windows companion identity without starting listeners."""
import argparse
import ipaddress
import json
from pathlib import Path
import secrets

from companion.security import certificate, fingerprint, generate_identity, private_address, protect, sha256


def prepare(output, policy, relay_host, relay_port=443, relay_certificate=None, direct_host=None, direct_port=8765, display_name='我的电脑'):
    output, policy = Path(output).resolve(), Path(policy).resolve()
    targets = json.loads(policy.read_text(encoding='utf-8-sig')).get('targets', {})
    if not targets or any(t.get('kind') != 'codex' or t.get('hostId') != 'local' or not t.get('threadId') for t in targets.values()):
        raise ValueError('local_codex_policy_required')
    if not isinstance(relay_host, str) or not 1 <= len(relay_host) <= 253 or any(c in relay_host for c in '/\\@#?% ') or not 1 <= relay_port <= 65535:
        raise ValueError('invalid_relay')
    pem = Path(relay_certificate).read_text(encoding='ascii') if relay_certificate else None
    if pem:
        certificate(pem)
    endpoints = []
    if direct_host:
        ip = private_address(direct_host)
        if not 1 <= direct_port <= 65535:
            raise ValueError('private_direct_address_required')
        endpoints.append({'host': str(ip), 'port': direct_port})
    output.mkdir(parents=True, exist_ok=False)
    identity, dns, cert, key = generate_identity()
    token = secrets.token_hex(32)
    config = {'policy': str(policy), 'identity': {'displayName': display_name, 'hostId': identity, 'serverName': dns,
              'serverCertificatePem': cert, 'serverCertificateSha256': fingerprint(certificate(cert)),
              'relay': {'host': relay_host, 'port': relay_port, 'certificatePem': pem}, 'directEndpoints': endpoints}}
    (output/'private.dpapi').write_bytes(protect(json.dumps({'keyPem': key.decode(), 'relayToken': token}).encode()))
    (output/'server.pem').write_text(cert, encoding='ascii')
    (output/'host.json').write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding='utf8')
    (output/'relay-registration.json').write_text(json.dumps({identity: sha256(token.encode())}), encoding='utf8')
    return {'directory': str(output), 'hostId': identity, 'listenerStarted': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--policy', required=True)
    parser.add_argument('--relay-host', required=True)
    parser.add_argument('--relay-port', type=int, default=443)
    parser.add_argument('--relay-certificate')
    parser.add_argument('--direct-host')
    parser.add_argument('--direct-port', type=int, default=8765)
    args = parser.parse_args()
    print(json.dumps(prepare(args.output, args.policy, args.relay_host, args.relay_port, args.relay_certificate, args.direct_host, args.direct_port)))


if __name__ == '__main__':
    main()
