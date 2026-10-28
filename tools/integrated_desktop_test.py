"""One authorized fixture send, deliberate host crash, read-only recovery and revocation.

Run from repository root: python -m tools.integrated_desktop_test
Private evidence/certificates are retained in .local/. A durable claim blocks reruns.
"""

import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
import uuid

from prototype.desktop import NodeDesktopRuntime
from prototype.host import serve
from prototype.journal import Journal
from prototype.loopback import exchange

ROOT = Path(__file__).resolve().parents[1]


class CrashAfterPositiveRuntimeEvidence(NodeDesktopRuntime):
    def send(self, operation, commit_guard):
        super().send(operation, commit_guard)
        os._exit(23)  # real handoff occurred; journal remains dispatching


def run():
    policy_path = ROOT / '.local/desktop-target-policy.json'
    policy = json.loads(policy_path.read_text(encoding='utf-8-sig'))
    target = policy['targets']['codex']
    if target.get('testFixture') is not True or not {'read', 'send'}.issubset(target.get('rights', [])):
        raise RuntimeError('dedicated authorized test fixture required')
    text = target['allowedMessages'][0]
    if text != 'DEVICE_BRIDGE_HELLO_V1。请仅回复 DEVICE_BRIDGE_ACK_V1。不要使用工具或操作文件。':
        raise RuntimeError('fixed harmless test text required')
    private = ROOT / '.local' / ('integrated-live-' + uuid.uuid4().hex)
    claim = ROOT / '.local/integrated-live-run.claim.json'
    with claim.open('x', encoding='utf8') as file:
        json.dump({'evidenceDirectory': str(private), 'operationId': 'live-recovery-v1'}, file)
        file.flush(); os.fsync(file.fileno())
    private.mkdir()
    openssl = shutil.which('openssl')
    if not openssl:
        raise RuntimeError('OpenSSL required')
    for name in ('server', 'phone', 'observer'):
        subprocess.run([openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN='+name, '-addext', 'subjectAltName=IP:127.0.0.1',
                        '-keyout', str(private / (name+'.key')), '-out', str(private / (name+'.pem'))],
                       check=True, capture_output=True, timeout=20, cwd=private)
    config = {'policy': str(policy_path), 'journal': str(private / 'journal.sqlite'),
              'attempts': str(private / 'attempts'), 'retention': 2,
              'serverCert': str(private / 'server.pem'), 'serverKey': str(private / 'server.key'),
              'peers': [{'device': name, 'certificate': str(private / (name+'.pem'))} for name in ('phone', 'observer')]}
    config_path = private / 'host.json'
    config_path.write_text(json.dumps(config), encoding='utf8')
    journal = Journal(Path(config['journal']))
    journal.provision('phone', 'codex', rights=('read', 'send'))
    journal.provision('observer', 'codex', rights=('read',))
    journal.submit('phone', 'codex', 'queued-revocation', {'text': text}, runtime_target=target['threadId'])
    journal.close()
    report = {'fixtureOnly': True, 'publicListener': False, 'agentContextUsed': False}
    process = None

    def context(name):
        result = ssl.create_default_context(cafile=str(private / 'server.pem'))
        result.load_cert_chain(str(private / (name+'.pem')), str(private / (name+'.key')))
        return result

    def start(crash=False):
        command = [sys.executable, '-m', 'tools.integrated_desktop_test', '--child-crash', str(config_path)] if crash else [sys.executable, '-m', 'prototype.host', str(config_path)]
        child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding='utf8')
        # A local subprocess watchdog bounds startup and retained host lifetime.
        import threading
        watchdog = threading.Timer(150, child.kill); watchdog.start()
        child._watchdog = watchdog
        ready = json.loads(child.stdout.readline())
        if ready.get('ready') is not True:
            raise RuntimeError('host not ready')
        return child, ('127.0.0.1', ready['port'])

    def control(child, value):
        child.stdin.write(json.dumps(value)+'\n'); child.stdin.flush()
        return json.loads(child.stdout.readline())

    def stop(child):
        if child.poll() is None:
            control(child, {'action': 'shutdown'})
        child.wait(timeout=10); child._watchdog.cancel()
        child.stdin.close(); child.stdout.close()

    def request(address, method, params, name='phone'):
        return exchange(address, context(name), {'method': method, 'params': params})

    try:
        process, address = start(crash=True)
        cursor = request(address, 'sync', {'thread': 'codex', 'cursor': 0})['result']['cursor']
        try:
            request(address, 'submit', {'thread': 'codex', 'id': 'live-recovery-v1', 'payload': {'text': text}})
        except (EOFError, OSError):
            report['phoneDisconnectedWithoutReply'] = True
        process.wait(timeout=35)
        if process.returncode != 23:
            raise RuntimeError('expected crash after positive runtime evidence did not occur')
        report['actualHostCrashAfterRuntimeDelivery'] = True
        stop(process); process = None
        process, address = start()
        assert request(address, 'status', {'id': 'live-recovery-v1'})['result']['state'] == 'unknown'
        report['restartRecoveredUnknown'] = True
        result = request(address, 'reconcile', {'id': 'live-recovery-v1'})['result']
        assert result['state'] == 'completed' and result['result']['delivery'] == 'observed'
        report['originalMessageIdReconciled'] = True
        duplicate = request(address, 'submit', {'thread': 'codex', 'id': 'live-recovery-v1', 'payload': {'text': text}})['result']
        assert duplicate['state'] == 'completed'
        report['duplicateSubmitDidNotResend'] = True
        reset = request(address, 'sync', {'thread': 'codex', 'cursor': cursor})['result']
        assert reset['resetRequired'] is True
        report['expiredCursorRecoveredSnapshot'] = True
        with socket.create_connection(address, timeout=3) as plain:
            with context('phone').wrap_socket(plain, server_hostname='127.0.0.1') as held:
                assert control(process, {'action': 'revoke', 'device': 'phone'})['revoked'] is True
                held.sendall(b'{"method":"status","params":{"id":"live-recovery-v1"}}\n')
                with held.makefile('rb') as reader:
                    assert json.loads(reader.readline(8193)) == {'ok': False, 'error': 'denied'}
        report['revocationBlockedExistingConnection'] = True
        for method, params in [('status', {'id': 'live-recovery-v1'}), ('reconcile', {'id': 'live-recovery-v1'}),
                               ('sync', {'thread': 'codex', 'cursor': 0}),
                               ('submit', {'thread': 'codex', 'id': 'queued-revocation', 'payload': {'text': text}})]:
            assert request(address, method, params).get('error') == 'denied'
        report['revocationBlockedReadReconcileAndQueuedSend'] = True
        stop(process); process = None
        process, address = start()
        assert request(address, 'status', {'id': 'live-recovery-v1'}).get('error') == 'denied'
        assert request(address, 'sync', {'thread': 'codex', 'cursor': 0}, 'observer')['ok'] is True
        report['revocationPersistedAcrossRestart'] = True
        stop(process); process = None
        journal = Journal(Path(config['journal']))
        queued = journal.db.execute("SELECT state FROM operations WHERE id='queued-revocation'").fetchone()['state']
        journal.close()
        assert queued == 'cancelled'
        attempts = list(Path(config['attempts']).glob('*.attempt.json'))
        assert len(attempts) == 1
        report.update({'queuedOperationCancelled': True, 'durableRuntimeAttempts': len(attempts), 'passed': True})
    except Exception as error:
        report.update({'passed': False, 'errorType': type(error).__name__})
        raise
    finally:
        if process is not None:
            try:
                stop(process)
            except Exception:
                process.kill(); process.wait(timeout=5); process._watchdog.cancel()
        (private / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf8')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--child-crash':
        config = json.loads(Path(sys.argv[2]).read_text(encoding='utf8'))
        policy = json.loads(Path(config['policy']).read_text(encoding='utf-8-sig'))
        if policy['targets']['codex'].get('testFixture') is not True:
            raise RuntimeError('crash fixture required')
        serve(config, runtime_factory=CrashAfterPositiveRuntimeEvidence)
    else:
        run()
