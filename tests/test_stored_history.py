"""Synthetic paging, content revision and isolation; no original chats."""
from copy import deepcopy
import unittest
import json
from pathlib import Path
import tempfile
from unittest.mock import patch
from types import SimpleNamespace
import threading
from companion.host import Host

from prototype.stored_history import BridgeDesktopRuntime, HistoryChanged, ReadOnlyHistoryProcess, StoredHistoryReader
from prototype.desktop import DesktopUnavailable, NodeDesktopRuntime


class Client:
    def __init__(self):
        self.calls = []
        self.turns = [
            {'id': 'new', 'status': 'completed', 'items': [
                {'id': str(i), 'type': 'agentMessage', 'text': ('message '+str(i)+' ')*200}
                for i in range(11)]},
            {'id': 'old', 'status': 'completed', 'items': [
                {'id': 'old-user', 'type': 'userMessage', 'content': [{'type': 'text', 'text': 'old question'}]}]},
        ]

    def request(self, method, params):
        self.calls.append((method, params))
        assert method == 'thread/turns/list'
        assert params['limit'] == 1 and params['itemsView'] == 'summary'
        index = int(params['cursor'] or 0)
        return {'data': [deepcopy(self.turns[index])], 'nextCursor': str(index+1) if index+1 < len(self.turns) else None}

    def close(self):
        pass


class StoredHistoryTests(unittest.TestCase):
    def test_host_shutdown_cancels_and_cleans_runtime_even_if_a_worker_stalls(self):
        calls = []
        runtime = SimpleNamespace(cancel=lambda: calls.append('cancel'), close=lambda: calls.append('close'))
        worker = SimpleNamespace(join=lambda timeout: calls.append('join'), is_alive=lambda: True)
        host = Host.__new__(Host)
        host.stop_event, host.listener = threading.Event(), None
        host.active_lock, host.sockets, host.threads = threading.RLock(), set(), [worker]
        host.service = SimpleNamespace(runtime=runtime)
        host.journal = SimpleNamespace(close=lambda: calls.append('journal'))
        with self.assertRaisesRegex(RuntimeError, 'host_worker_did_not_stop'):
            host.close()
        self.assertEqual(calls, ['cancel', 'join', 'close'])

    def test_read_service_rejects_mutations_without_starting_a_process(self):
        client = ReadOnlyHistoryProcess()
        with patch('prototype.stored_history.subprocess.Popen') as start:
            for method in ('turn/start', 'thread/resume', 'thread/start', 'command/exec'):
                with self.assertRaises(ValueError): client.request(method, {})
            client.cancel()
            with self.assertRaises(DesktopUnavailable): client._start()
            start.assert_not_called()

    def test_explicit_send_targets_keep_original_owner_readiness_path(self):
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory)/'policy.json'
            policy.write_text(json.dumps({'targets': {'read': {'rights': ['read']}, 'live': {'rights': ['read', 'send']}}}))
            runtime = BridgeDesktopRuntime(policy, directory)
            with patch.object(runtime.stored, 'history', return_value={'runtimeStatus': 'unknown'}) as stored, \
                    patch.object(NodeDesktopRuntime, 'history', return_value={'runtimeStatus': 'idle'}) as live:
                self.assertEqual(runtime.history('read', 'target', None, 1)['runtimeStatus'], 'unknown')
                self.assertEqual(runtime.history('live', 'target', None, 1)['runtimeStatus'], 'idle')
                stored.assert_called_once(); live.assert_called_once()
            runtime.close()

    def test_large_turn_is_split_without_skipping_messages(self):
        client = Client()
        reader = StoredHistoryReader(client)
        before, ids = None, []
        for _ in range(20):
            page = reader.history('target', before, 6)
            ids = [message['id'] for message in page['messages']]+ids
            self.assertEqual(page['runtimeStatus'], 'unknown')
            before = page['nextBefore']
            if before is None:
                break
        self.assertIsNone(before)
        self.assertEqual(ids, ['old:old-user']+['new:'+str(i) for i in range(11)])
        self.assertEqual(len(set(ids)), 12)

    def test_same_id_edit_invalidates_an_intra_turn_cursor(self):
        client = Client()
        reader = StoredHistoryReader(client)
        page = reader.history('target', None, 1)
        client.turns[0]['items'][0]['text'] = 'edited under the same ID'
        with self.assertRaises(HistoryChanged):
            reader.history('target', page['nextBefore'], 1)

    def test_cursors_are_target_bound_and_missing_cursors_fail_closed(self):
        reader = StoredHistoryReader(Client())
        page = reader.history('first', None, 1)
        with self.assertRaises(HistoryChanged): reader.history('second', page['nextBefore'], 1)
        with self.assertRaises(HistoryChanged): reader.history('first', 's1.'+'0'*64, 1)
        self.assertEqual(reader.history('first', None, 1)['nextBefore'], page['nextBefore'])

    def test_projection_excludes_tool_output_and_bounds_unicode_text(self):
        client = Client()
        client.turns = [{'id': 'one', 'status': 'completed', 'items': [
            {'id': 'tool', 'type': 'functionCallOutput', 'output': 'synthetic tool content'},
            {'id': 'reply', 'type': 'agentMessage', 'text': '中'*2000}]}]
        page = StoredHistoryReader(client).history('target', None, 6)
        self.assertEqual(len(page['messages']), 1)
        self.assertLess(len(page['messages'][0]['text'].encode()), 2500)
        self.assertNotIn('synthetic tool content', str(page))

    def test_tool_only_turns_return_a_continuation_instead_of_hiding_older_messages(self):
        client = Client()
        client.turns = [{'id': str(i), 'items': []} for i in range(10)]+client.turns[-1:]
        reader = StoredHistoryReader(client)
        first = reader.history('target', None, 6)
        self.assertEqual(first['messages'], [])
        self.assertTrue(first['hasMore'])
        older = reader.history('target', first['nextBefore'], 6)
        self.assertEqual(older['messages'][0]['text'], 'old question')
        self.assertIsNone(older['nextBefore'])


if __name__ == '__main__': unittest.main()
