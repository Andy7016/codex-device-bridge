"""Bounded loopback host for the single claimed Android integration fixture."""
import json
import os
from pathlib import Path
import sys
import threading
import ssl
from prototype.host import build_host
from prototype.loopback import LoopbackServer

ROOT = Path(__file__).resolve().parents[1]

if __name__ == '__main__':
    claim = json.loads((ROOT/'.local/android-app-live.claim.json').read_text(encoding='utf8'))
    folder = Path(claim['directory']).resolve()
    config = json.loads((folder/'host.json').read_text(encoding='utf8'))
    policy = json.loads(Path(config['policy']).read_text(encoding='utf8'))
    if policy['targets']['codex'].get('testFixture') is not True or policy['targets']['codex'].get('allowFreeText'):
        raise RuntimeError('fixed-message dedicated fixture required')
    server, journal = build_host(config)
    server.drop_ack_once.add(claim['operationId'])
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.02})
    thread.start()
    additional = []
    if (folder/'tls-servers.json').exists():
        for entry in json.loads((folder/'tls-servers.json').read_text(encoding='utf8')):
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(entry['certificate'], entry['key'])
            context.verify_mode = ssl.CERT_REQUIRED
            context.load_verify_locations(cafile=config['peers'][0]['certificate'])
            extra = LoopbackServer(('127.0.0.1',entry['port']),context,server.identities,journal,service=server.service)
            worker = threading.Thread(target=extra.serve_forever,kwargs={'poll_interval':0.02});worker.start()
            additional.append((extra,worker))
    watchdog = threading.Timer(900, lambda: os._exit(24)); watchdog.start()
    print(json.dumps({'ready': True, 'port': server.server_address[1], 'loopbackOnly': True}), flush=True)
    try:
        for line in sys.stdin:
            command = json.loads(line)
            if command == {'action': 'shutdown'}:
                break
            if command == {'action': 'revoke'}:
                server.service.revoke(config['peers'][0]['device'])
                print('{"revoked":true}', flush=True)
    finally:
        for extra,worker in additional:
            extra.shutdown();extra.server_close();worker.join(timeout=3)
        server.shutdown(); server.server_close(); thread.join(timeout=3)
        journal.close(); watchdog.cancel()
