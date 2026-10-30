"""Dedicated synthetic Android WSS fixture. Loopback only, hard 15-minute lifetime."""
import json
from pathlib import Path
import queue
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from test_wss_relay import WssRelayTests
import companion.host as host_module
from companion.security import certificate, fingerprint


def main():
    lab = WssRelayTests('test_bad_ticket_cannot_reach_inner_tls_or_runtime')
    lab.setUp()
    operation = str(uuid.uuid4())
    dropped = threading.Event()
    stopped = threading.Event()
    original = host_module.send_frame
    history = lab.runtime.history
    def lose_ack(stream, value, limit=8192):
        if value.get('result', {}).get('id') == operation and not dropped.is_set():
            dropped.set()
            raise EOFError('synthetic_lost_ack')
        return original(stream, value, limit)
    def fixture_history(*args):
        result = history(*args)
        devices = [d for d in lab.host.pairing.devices() if d['enabled']]
        if len(devices) == 1:
            result['messages'].append({'id': 'connection-update', 'role': 'assistant',
                'text': lab.host.connection_update(devices[0]['device']), 'status': 'completed'})
        return result
    lab.runtime.history = fixture_history
    host_module.send_frame = lose_ack
    def approve():
        while not stopped.wait(.1):
            for pending in lab.host.pairing.pending():
                try:
                    lab.host.approve(pending['id'], pending['code'], {'codex': ['read', 'send']})
                except Exception:
                    pass
    worker = threading.Thread(target=approve, daemon=True); worker.start()
    def publish():
        invitation = lab.host.pairing.invite()
        fixture = {'qrText': invitation['text'], 'operationId': operation, 'message': 'synthetic text',
                   'hostId': lab.host.identity['hostId'], 'port': lab.port}
        (lab.directory / 'wss-lab.json').write_text(json.dumps(fixture), encoding='utf8')
        print(json.dumps({'ready': True, 'asset': str(lab.directory / 'wss-lab.json'), 'port': lab.port,
                          'syntheticOnly': True}), flush=True)
    commands = queue.Queue()
    def read_commands():
        for line in sys.stdin:
            commands.put(line.strip())
        commands.put('shutdown')
    threading.Thread(target=read_commands, daemon=True).start()
    publish()
    try:
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            try:
                command = commands.get(timeout=.5)
            except queue.Empty:
                continue
            if command == 'shutdown': break
            if command == 'invite': publish()
            if command == 'status':
                print(json.dumps({'runtimeSends': len(lab.runtime.sends), 'droppedAck': dropped.is_set(),
                    'devices': len(lab.host.pairing.devices()), 'tlsFailure': lab.host.last_tls_failure}), flush=True)
            if command == 'restart-relay':
                lab.stop_relay(); lab.start_relay()
                print('{"relayRestarted":true}', flush=True)
    finally:
        stopped.set(); worker.join(2)
        lab.tearDown()
        print(json.dumps({'stopped': True, 'runtimeSends': len(lab.runtime.sends),
            'droppedAck': dropped.is_set(), 'enabledDevices': 0}), flush=True)


if __name__ == '__main__': main()
