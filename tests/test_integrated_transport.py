from concurrent.futures import ThreadPoolExecutor
import json
import socket
import threading
import unittest
import uuid

import test_recovery_lab as tls_lab
from test_desktop_recovery import POLICY, Runtime
from prototype.desktop import DesktopRecoveryService
from prototype.journal import Journal
from prototype.loopback import LoopbackServer, exchange


class IntegratedTransport(unittest.TestCase):
    # Reuse certificate generation without inheriting/rerunning the base tests.
    setUpClass = classmethod(tls_lab.RecoveryLab.setUpClass.__func__)
    tearDownClass = classmethod(tls_lab.RecoveryLab.tearDownClass.__func__)
    client_context = tls_lab.RecoveryLab.client_context
    server_context = tls_lab.RecoveryLab.server_context

    def setUp(self):
        self.path = self.scratch / (uuid.uuid4().hex + '.sqlite')
        self.journal = Journal(self.path, retention=2)
        for device in ('phone', 'desktop'):
            self.journal.provision(device, 'codex', rights=('read', 'send'))
        self.runtime = Runtime()
        self.servers = []
        self.service = self.make_service()

    def make_service(self):
        return DesktopRecoveryService(self.journal, self.runtime, POLICY, devices=('phone', 'desktop'))

    def tearDown(self):
        self.stop_servers()
        self.journal.close()

    def stop_servers(self):
        for server, thread in reversed(self.servers):
            server.shutdown(); server.server_close(); thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
        self.servers.clear()

    def start(self):
        server = LoopbackServer(('127.0.0.1', 0), self.server_context(), self.identities,
                                self.journal, service=self.service)
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.02})
        thread.start(); self.servers.append((server, thread))
        return server

    def request(self, server, method, params):
        return exchange(server.server_address, self.client_context(), {'method': method, 'params': params})

    def test_lost_phone_ack_unknown_reconcile_and_snapshot_reset_across_restart(self):
        self.runtime.fault = True
        server = self.start(); server.drop_ack_once.add('network-op')
        params = {'thread': 'codex', 'id': 'network-op', 'payload': {'text': 'synthetic text'}}
        with self.assertRaises((EOFError, OSError)):
            self.request(server, 'submit', params)
        self.stop_servers(); self.journal.close()
        self.journal = Journal(self.path, retention=2)
        self.service = self.make_service(); server = self.start()
        self.assertEqual(self.request(server, 'status', {'id': 'network-op'})['result']['state'], 'unknown')
        result = self.request(server, 'reconcile', {'id': 'network-op'})['result']
        self.assertEqual(result['result']['delivery'], 'observed')
        self.assertEqual(self.request(server, 'submit', params)['result']['state'], 'completed')
        reset = self.request(server, 'sync', {'thread': 'codex', 'cursor': 0})['result']
        self.assertTrue(reset['resetRequired'])
        self.assertEqual(len(self.runtime.sends), 1)

    def test_revoked_existing_tls_connection_cannot_read_or_dispatch_queued_work(self):
        self.service.enqueue('phone', 'codex', 'queued', {'text': 'synthetic text'})
        server = self.start()
        with socket.create_connection(server.server_address, timeout=3) as plain:
            with self.client_context().wrap_socket(plain, server_hostname='127.0.0.1') as held:
                self.service.revoke('phone')
                held.sendall(b'{"method":"status","params":{"id":"queued"}}\n')
                with held.makefile('rb') as reader:
                    self.assertEqual(json.loads(reader.readline(8193)), {'ok': False, 'error': 'denied'})
        self.assertEqual(self.request(server, 'submit', {'thread': 'codex', 'id': 'queued', 'payload': {'text': 'synthetic text'}})['error'], 'denied')
        self.assertEqual(self.runtime.sends, [])

    def test_revocation_between_service_result_and_socket_write_suppresses_result(self):
        server = self.start()
        self.request(server, 'submit', {'thread': 'codex', 'id': 'completed', 'payload': {'text': 'synthetic text'}})
        ready, release = threading.Event(), threading.Event()
        original = self.service.call

        def pause(device, method, params):
            result = original(device, method, params)
            ready.set(); release.wait(2)
            return result

        self.service.call = pause
        with ThreadPoolExecutor(1) as pool:
            future = pool.submit(self.request, server, 'status', {'id': 'completed'})
            self.assertTrue(ready.wait(2))
            self.service.revoke('phone'); release.set()
            self.assertEqual(future.result(timeout=3), {'ok': False, 'error': 'denied'})
