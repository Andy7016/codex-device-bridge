"""Fresh v1 assets for display-isolation regression; no original runtime or claims."""
import json
from pathlib import Path
import queue
import socket
import sys
import threading
import time
import uuid

from prototype.host import build_host
from tools.pairing_lab import SyntheticRuntime
from tools.prepare_android_host import prepare


def main():
    root = Path(__file__).resolve().parents[1] / '.local' / ('wss-v1-regression-' + uuid.uuid4().hex)
    root.mkdir()
    policy = {'targets': {'codex': {'kind': 'codex', 'hostId': 'local', 'threadId': 'synthetic-v1',
              'rights': ['read', 'send'], 'allowedMessages': ['synthetic text'], 'testFixture': True}}}
    (root / 'policy.json').write_text(json.dumps(policy), encoding='utf8')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
    prepare(root / 'policy.json', root / 'host', port=port)
    config = json.loads((root / 'host/host.json').read_text(encoding='utf8'))
    server, journal = build_host(config, runtime_factory=SyntheticRuntime)
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .05}); thread.start()
    profile = json.loads((root / 'host/device-bridge.connection.json').read_text(encoding='utf8'))
    (root / 'bridge-test.json').write_text(json.dumps({'profile': profile}), encoding='utf8')
    (root / 'bridge-tls-test.json').write_text(json.dumps({'validDerivedLeaf': {**profile, 'port': port + 1 if port < 65535 else port - 1}}), encoding='utf8')
    print(json.dumps({'ready': True, 'directory': str(root), 'port': port, 'syntheticOnly': True}), flush=True)
    commands = queue.Queue()
    def read_commands():
        for line in sys.stdin: commands.put(line.strip())
        commands.put('shutdown')
    threading.Thread(target=read_commands, daemon=True).start()
    deadline = time.monotonic() + 900
    try:
        while time.monotonic() < deadline:
            try:
                if commands.get(timeout=.5) == 'shutdown': break
            except queue.Empty: pass
    finally:
        for peer in config['peers']: server.service.revoke(peer['device'])
        server.shutdown(); server.server_close(); thread.join(3); journal.close()
        print('{"stopped":true,"enabledDevices":0}', flush=True)


if __name__ == '__main__': main()
