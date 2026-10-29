"""Offline-first local configuration; never probes endpoints or widens grants."""
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
import time
import uuid

from .security import certificate, fingerprint, generate_identity, private_address, protect, public_id, sha256


def read_json(path):
    from prototype.loopback import unique_pairs, reject_constant
    with Path(path).open('rb') as source:
        data = source.read(128*1024+1)
    if len(data) > 128*1024:
        raise ValueError('configuration_too_large')
    value = json.loads(data.decode('utf-8-sig'), object_pairs_hook=unique_pairs, parse_constant=reject_constant)
    if not isinstance(value, dict):
        raise ValueError('invalid_configuration')
    return value


def write_json(path, value):
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix='.'+path.name+'-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf8') as output:
            json.dump(value, output, ensure_ascii=False, indent=2, allow_nan=False)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def relay_endpoint(host=None, port=443, certificate_path=None):
    if host is None or host == '':
        if certificate_path:
            raise ValueError('relay_address_required')
        return None
    if not isinstance(host, str) or host != host.strip() or not 1 <= len(host) <= 253 or any(c.isspace() or c in '/\\@#?%[]' for c in host):
        raise ValueError('invalid_relay')
    try:
        host = str(ipaddress.ip_address(host))
    except ValueError:
        if any(not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?', part) for part in host.split('.')):
            raise ValueError('invalid_relay')
        host = host.lower()
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('invalid_port')
    pem = None
    if certificate_path:
        with Path(certificate_path).open('rb') as source:
            pem = source.read(8193).replace(b'\r\n', b'\n').decode('ascii')
        certificate(pem)
    return {'host': host, 'port': port, 'certificatePem': pem}


def policy_snapshot(path=None):
    policy = read_json(path) if path else {'targets': {}}
    targets = policy.get('targets')
    if not isinstance(targets, dict) or len(targets) > 100:
        raise ValueError('invalid_policy')
    for alias, target in targets.items():
        if not isinstance(alias, str) or not 1 <= len(alias) <= 128 or not isinstance(target, dict):
            raise ValueError('invalid_policy')
        if target.get('kind') != 'codex' or target.get('hostId') != 'local' or not isinstance(target.get('threadId'), str) or not 1 <= len(target['threadId']) <= 128:
            raise ValueError('local_codex_policy_required')
        rights = target.get('rights')
        if not isinstance(rights, list) or not all(isinstance(r, str) and r in {'read', 'send', 'approve'} for r in rights):
            raise ValueError('invalid_policy')
    return policy


def prepare(output, policy=None, relay_host=None, relay_port=443, relay_certificate=None, direct_host=None, direct_port=8765, display_name='我的电脑'):
    output = Path(output).resolve()
    if not isinstance(display_name, str) or not 1 <= len(display_name) <= 80 or display_name != display_name.strip() or any(ord(c) < 32 or ord(c) == 127 for c in display_name):
        raise ValueError('invalid_display_name')
    selected_policy = policy_snapshot(policy)
    relay = relay_endpoint(relay_host, relay_port, relay_certificate)
    endpoints = []
    if direct_host:
        ip = private_address(direct_host)
        if type(direct_port) is not int or not 1 <= direct_port <= 65535:
            raise ValueError('invalid_port')
        endpoints.append({'host': str(ip), 'port': direct_port})
    output.mkdir(parents=True, exist_ok=False)
    identity, dns, pem, key = generate_identity()
    token = secrets.token_hex(32)
    encrypted = protect(json.dumps({'keyPem': key.decode(), 'relayToken': token}).encode())
    config = {'policy': 'policy.json', 'identity': {'displayName': display_name, 'hostId': identity, 'serverName': dns,
              'serverCertificatePem': pem, 'serverCertificateSha256': fingerprint(certificate(pem)),
              'relay': relay, 'directEndpoints': endpoints}}
    (output/'private.dpapi').write_bytes(encrypted)
    (output/'server.pem').write_text(pem, encoding='ascii')
    write_json(output/'policy.json', selected_policy)
    write_json(output/'relay-registration.json', {identity: sha256(token.encode())})
    write_json(output/'host.json', config)  # completion marker, written last
    return {'directory': str(output), 'hostId': identity, 'listenerStarted': False}


def load_configuration(path):
    path = Path(path).resolve()
    config = read_json(path)
    identity = config.get('identity')
    if not isinstance(identity, dict):
        raise ValueError('invalid_host_configuration')
    cert = certificate(identity.get('serverCertificatePem'))
    host_id = public_id(cert)
    if identity.get('hostId') != host_id or identity.get('serverCertificateSha256') != fingerprint(cert) or identity.get('serverName') != 'device.'+host_id[:32]+'.'+host_id[32:]+'.bridge.invalid':
        raise ValueError('host_identity_mismatch')
    if not (path.parent/'private.dpapi').is_file() or not (path.parent/'server.pem').is_file():
        raise ValueError('identity_files_missing')
    with (path.parent/'server.pem').open('rb') as source:
        stored = certificate(source.read(8193).replace(b'\r\n', b'\n').decode('ascii'))
    if fingerprint(stored) != fingerprint(cert):
        raise ValueError('host_identity_mismatch')
    relay = identity.get('relay')
    if relay is not None:
        if not isinstance(relay, dict) or set(relay) != {'host', 'port', 'certificatePem'}:
            raise ValueError('invalid_relay')
        relay_endpoint(relay['host'], relay['port'])
        if relay['certificatePem'] is not None:
            certificate(relay['certificatePem'])
    policy = config.get('policy')
    if not isinstance(policy, str) or not policy:
        raise ValueError('invalid_policy')
    policy_path = Path(policy)
    policy_path = policy_path if policy_path.is_absolute() else path.parent/policy_path
    policy_snapshot(policy_path)
    return config, policy_path.resolve()


def update_configuration(path, *, relay_host=None, relay_port=443, relay_certificate=None, policy=None, use_system_trust=False):
    path = Path(path).resolve()
    config, _ = load_configuration(path)
    relay = relay_endpoint(relay_host, relay_port, relay_certificate)
    old_relay = config['identity'].get('relay')
    if relay and not relay_certificate and not use_system_trust and old_relay:
        relay['certificatePem'] = old_relay.get('certificatePem')
    selected = policy_snapshot(policy) if policy else None
    path.with_name('host-'+str(time.time_ns())+'.json.bak').write_bytes(path.read_bytes())
    if selected is not None:
        name = 'policy-'+uuid.uuid4().hex+'.json'
        write_json(path.parent/name, selected)
        config['policy'] = name
    config['identity']['relay'] = relay
    write_json(path, config)
    return path


def remember_configuration(directory, path):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    write_json(directory/'startup.json', {'schemaVersion': 1, 'configPath': str(Path(path).resolve())})


def remembered_configuration(directory):
    try:
        value = read_json(Path(directory)/'startup.json')
        if set(value) != {'schemaVersion', 'configPath'} or value['schemaVersion'] != 1:
            return None
        path = Path(value['configPath']).resolve()
        load_configuration(path)
        return path
    except (OSError, ValueError, TypeError):
        return None
