from concurrent.futures import ThreadPoolExecutor
from contextlib import nullcontext
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from prototype.desktop import DesktopRecoveryService, DesktopUnavailable, NodeDesktopRuntime
from prototype.journal import Conflict, Denied, Journal

ROOT = Path(__file__).resolve().parents[1]
POLICY = {"targets": {"codex": {"kind": "codex", "hostId": "local", "threadId": "synthetic-thread",
          "rights": ["read", "send"], "allowedMessages": ["synthetic text"]}}}


class Runtime:
    def __init__(self):
        self.sends = []
        self.fault = False
        self.absent = False
        self.prepare = lambda: None
        self.after_send = lambda: None

    def observation(self, op):
        return {"observed": not self.absent, "targetId": op["binding"]["target"],
                "messageId": op["binding"]["message_id"], "turnId": "synthetic-turn", "turnStatus": "completed"}

    def send(self, op, commit_guard):
        self.prepare()
        with commit_guard():
            self.sends.append(op["binding"].copy())
        self.after_send()
        if self.fault:
            raise DesktopUnavailable("reply lost after handoff")
        return {"delivery": "observed", "turnId": "synthetic-turn", "turnStatus": "completed", "evidence": "runtime-message-id"}

    def inspect(self, op):
        return self.observation(op)

    def history(self, label, target, before, limit):
        self.prepare()
        return {'messages': [{'id': 'synthetic-message', 'role': 'assistant', 'text': 'synthetic reply', 'status': 'completed'}],
                'nextBefore': None, 'hasMore': False, 'runtimeStatus': 'idle', 'historyComplete': True}


class DesktopRecovery(unittest.TestCase):
    def setUp(self):
        root = ROOT / 'test-results'
        root.mkdir(exist_ok=True)
        self.scratch = Path(tempfile.mkdtemp(prefix='desktop-', dir=root)).resolve()
        self.path = self.scratch / 'journal.sqlite'
        self.journal = Journal(self.path)
        for device in ('phone', 'desktop'):
            self.journal.provision(device, 'codex', rights=('read', 'send'))
        self.runtime = Runtime()
        self.service = self.make_service()

    def make_service(self, policy=POLICY):
        return DesktopRecoveryService(self.journal, self.runtime, policy, devices=('phone', 'desktop'))

    def tearDown(self):
        self.journal.close()
        # Only direct task-owned files. Preserve unexpected contents for inspection.
        scratch = self.scratch
        self.assertEqual(scratch.parent, (ROOT / 'test-results').resolve())
        files = list(scratch.iterdir())
        self.assertTrue(all(p.is_file() and not p.is_symlink() and p.resolve().parent == scratch for p in files))
        for p in files:
            p.unlink()
        scratch.rmdir()

    def submit(self, device='phone', operation='op-1'):
        return self.service.call(device, 'submit', {'thread': 'codex', 'id': operation, 'payload': {'text': 'synthetic text'}})

    def test_unknown_reply_reconciles_without_resend_after_host_restart(self):
        self.runtime.fault = True
        self.assertEqual(self.submit()['state'], 'unknown')
        binding = self.journal.runtime_operation('phone', 'op-1')['binding']
        self.journal.close()
        self.journal = Journal(self.path)
        self.service = self.make_service()
        self.assertEqual(self.submit()['state'], 'unknown')
        result = self.service.call('phone', 'reconcile', {'id': 'op-1'})
        self.assertEqual((result['state'], result['result']['delivery']), ('completed', 'observed'))
        self.assertEqual(self.journal.runtime_operation('phone', 'op-1')['binding'], binding)
        self.assertEqual(len(self.runtime.sends), 1)

    def test_absent_evidence_does_not_claim_failure_or_authorize_retry(self):
        self.runtime.fault = self.runtime.absent = True
        self.submit()
        self.assertEqual(self.service.reconcile('phone', 'op-1')['state'], 'unknown')
        self.assertEqual(self.submit()['state'], 'unknown')
        self.assertEqual(len(self.runtime.sends), 1)

    def test_operation_ids_are_namespaced_and_binding_is_host_generated(self):
        for device in ('phone', 'desktop'):
            self.submit(device, '../same caller id')
        self.assertEqual(len({x['execution_id'] for x in self.runtime.sends}), 2)
        self.assertEqual(len({x['message_id'] for x in self.runtime.sends}), 2)

    def test_revocation_during_preparation_blocks_handoff(self):
        ready, release = threading.Event(), threading.Event()
        self.runtime.prepare = lambda: (ready.set(), release.wait(2))
        with ThreadPoolExecutor(1) as pool:
            future = pool.submit(self.submit)
            self.assertTrue(ready.wait(2))
            self.service.revoke('phone')
            release.set()
            with self.assertRaises(Denied):
                future.result(timeout=3)
        self.assertEqual(self.runtime.sends, [])

    def test_revocation_after_handoff_blocks_result_release(self):
        sent, release = threading.Event(), threading.Event()
        self.runtime.after_send = lambda: (sent.set(), release.wait(2))
        with ThreadPoolExecutor(1) as pool:
            future = pool.submit(self.submit)
            self.assertTrue(sent.wait(2))
            self.service.revoke('phone')
            release.set()
            with self.assertRaises(Denied):
                future.result(timeout=3)
        self.assertEqual(len(self.runtime.sends), 1)
        with self.assertRaises(Denied):
            with self.service.response_guard('phone'):
                self.fail('revoked response released')

    def test_queued_work_is_cancelled_and_revoked_identity_cannot_be_reenabled(self):
        self.service.enqueue('phone', 'codex', 'queued', {'text': 'synthetic text'})
        self.service.revoke('phone')
        self.assertEqual(self.journal.db.execute("SELECT state FROM operations WHERE id='queued'").fetchone()['state'], 'cancelled')
        with self.assertRaises(Conflict):
            self.journal.provision('phone', 'codex')
        with self.assertRaises(Denied):
            self.service.dispatch('phone', 'queued')
        self.assertEqual(self.runtime.sends, [])

    def test_policy_alias_cannot_retarget_existing_operation(self):
        self.service.enqueue('phone', 'codex', 'queued', {'text': 'synthetic text'})
        policy = json.loads(json.dumps(POLICY)); policy['targets']['codex']['threadId'] = 'another-thread'
        self.service = self.make_service(policy)
        with self.assertRaises(Conflict):
            self.service.dispatch('phone', 'queued')
        with self.assertRaises(Conflict):
            self.submit(operation='queued')
        for method, params in [('status', {'id': 'queued'}), ('sync', {'thread': 'codex', 'cursor': 0}), ('reconcile', {'id': 'queued'})]:
            with self.assertRaises(Conflict):
                self.service.call('phone', method, params)
        self.assertEqual(self.runtime.sends, [])

    def test_concurrent_reconciliation_records_one_observation_and_no_send(self):
        self.runtime.fault = True
        self.submit()
        before = self.journal.sync('phone', 'codex', 0)['cursor']
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda _: self.service.reconcile('phone', 'op-1'), range(2)))
        self.assertTrue(all(r['state'] == 'completed' for r in results))
        events = self.journal.sync('phone', 'codex', before)['events']
        self.assertEqual(len(events), 1)
        self.assertEqual(len(self.runtime.sends), 1)

    def test_unsupported_fields_and_caller_chosen_identity_are_refused(self):
        with self.assertRaises(ValueError):
            self.service.call('phone', 'submit', {'thread': 'codex', 'id': 'x', 'payload': {'text': 'synthetic text'}, 'messageId': 'forged'})
        for method in ('approve', 'revoke', 'tools/call'):
            with self.assertRaises(Denied):
                self.service.call('phone', method, {})
        self.journal.provision('phone', 'codex', rights=('read',))
        with self.assertRaises(Denied):
            self.submit()
        self.assertEqual(self.runtime.sends, [])

    @unittest.skipUnless(os.name == 'nt', 'Windows desktop runtime')
    def test_child_stdio_handoff_receives_persisted_binding_and_no_agent_identity(self):
        self.service.enqueue('phone', 'codex', 'stdio', {'text': 'synthetic text'})
        operation = self.journal.runtime_operation('phone', 'stdio')
        runtime = NodeDesktopRuntime(self.path, self.path.parent, timeout=2)
        runtime.worker = ROOT / 'tests/fixtures/desktop_worker_stub.mjs'
        result = runtime.send(operation, nullcontext)
        self.assertEqual(result['delivery'], 'observed')
        self.assertEqual(runtime.inspect(operation)['messageId'], operation['binding']['message_id'])

    def test_child_lifetime_guard_failure_never_releases_a_handoff_permit(self):
        self.service.enqueue('phone', 'codex', 'no-job', {'text': 'synthetic text'})
        operation = self.journal.runtime_operation('phone', 'no-job')
        runtime = NodeDesktopRuntime(self.path, self.path.parent, timeout=2)
        runtime.worker = ROOT / 'tests/fixtures/desktop_worker_stub.mjs'
        released = []
        def guard():
            released.append(True)
            return nullcontext()
        with patch('prototype.desktop.KillOnCloseJob', side_effect=OSError('job unavailable')):
            with self.assertRaises(DesktopUnavailable):
                runtime.send(operation, guard)
        self.assertEqual(released, [])

    def test_catalog_exposes_only_authorized_sessions_and_effective_send_right(self):
        self.journal.provision('phone', 'codex', rights=('read',))
        result = self.service.call('phone', 'catalog', {})
        self.assertEqual(len(result['sessions']), 1)
        self.assertFalse(result['sessions'][0]['canSend'])
        self.assertEqual(result['sessions'][0]['allowedMessages'], [])
        self.assertFalse(result['capabilities']['chatgpt'])
        self.service.revoke('phone')
        with self.assertRaises(Denied): self.service.call('phone', 'catalog', {})

    def test_history_requires_scope_and_valid_bounded_page(self):
        result = self.service.call('phone', 'history', {'thread': 'codex', 'before': None, 'limit': 6})
        self.assertEqual(result['messages'][0]['text'], 'synthetic reply')
        with self.assertRaises(Denied): self.service.call('phone', 'history', {'thread': 'other', 'before': None, 'limit': 6})
        for limit in (True, 0, 9):
            with self.assertRaises(ValueError): self.service.call('phone', 'history', {'thread': 'codex', 'before': None, 'limit': limit})

    def test_oversize_worker_frame_retains_only_a_fixed_unavailable_code(self):
        runtime = NodeDesktopRuntime(self.path, self.path.parent, timeout=2)
        runtime.worker = ROOT / 'tests/fixtures/desktop_worker_stub.mjs'
        with self.assertRaises(DesktopUnavailable) as result:
            runtime.history('codex', 'synthetic-oversized-frame', None, 1)
        self.assertEqual(result.exception.code, 'desktop_history_too_large')
        self.assertEqual(DesktopUnavailable('synthetic', code='untrusted-code').code, 'desktop_unavailable')

    def test_history_hash_detects_same_id_content_and_status_changes(self):
        first = self.service.call('phone', 'history', {'thread': 'codex', 'before': None, 'limit': 6})
        second = self.service.call('phone', 'history', {'thread': 'codex', 'before': None, 'limit': 6, 'ifRevision': first['revision']})
        self.assertTrue(second['notModified'])
        self.assertNotIn('messages', second)
        self.runtime.history = lambda *args: {'messages': [{'id': 'synthetic-message', 'role': 'assistant',
            'text': 'changed text', 'status': 'inProgress'}], 'nextBefore': None, 'hasMore': False,
            'runtimeStatus': 'unknown', 'historyComplete': True}
        changed = self.service.call('phone', 'history', {'thread': 'codex', 'before': None, 'limit': 6, 'ifRevision': first['revision']})
        self.assertFalse(changed['notModified'])
        self.assertNotEqual(changed['revision'], first['revision'])
        self.service.revoke('phone')
        with self.assertRaises(Denied):
            self.service.call('phone', 'history', {'thread': 'codex', 'before': None, 'limit': 6, 'ifRevision': first['revision']})

    def test_revocation_during_history_read_suppresses_returned_content(self):
        self.runtime.prepare = lambda: self.service.revoke('phone')
        with self.assertRaises(Denied): self.service.call('phone', 'history', {'thread': 'codex', 'before': None, 'limit': 6})

    def test_free_text_requires_explicit_host_flag_and_device_send_grant(self):
        with self.assertRaises(Denied): self.service.enqueue('phone', 'codex', 'free', {'text': 'different text'})
        policy = json.loads(json.dumps(POLICY)); policy['targets']['codex']['allowFreeText'] = True
        self.service = self.make_service(policy)
        self.service.enqueue('phone', 'codex', 'free', {'text': 'different text'})
        self.assertTrue(self.service.call('phone', 'catalog', {})['sessions'][0]['freeText'])
        self.journal.provision('desktop', 'codex', rights=('read',))
        with self.assertRaises(Denied): self.service.enqueue('desktop', 'codex', 'free', {'text': 'different text'})


if __name__ == '__main__':
    unittest.main()
