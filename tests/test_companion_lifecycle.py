"""Bounded shutdown checks with no network, identities or desktop sessions."""
import threading
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from companion.app import Window


class Root:
    def __init__(self):
        self.pending = []
        self.destroyed_on = None
        self.cancelled = []

    def withdraw(self):
        pass

    def after(self, delay, callback):
        self.pending.append(callback)

    def after_cancel(self, identifier):
        self.cancelled.append(identifier)

    def destroy(self):
        self.destroyed_on = threading.get_ident()


class ShutdownTests(unittest.TestCase):
    def test_native_tk_close_reload_has_no_stale_callbacks_or_workers(self):
        probe = Path(__file__).parent/'fixtures/companion_shutdown_probe.py'
        result = subprocess.run([sys.executable, str(probe)], capture_output=True,
                                text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, '')
        self.assertIn('PASS: 5 offline Tk close/reload cycles', result.stdout)

    def test_gui_waits_for_actual_worker_exit_before_teardown(self):
        host_closed = threading.Event()
        release_worker = threading.Event()
        worker_finished = threading.Event()
        real_thread = threading.Thread
        workers = []

        class DelayedThread(real_thread):
            def run(self):
                try:
                    super().run()
                    # Host.close has returned, but this thread still owns its
                    # runtime state. Teardown must wait for this final unwind.
                    host_closed.set()
                    release_worker.wait(3)
                finally:
                    worker_finished.set()

        def start_worker(*args, **kwargs):
            worker = DelayedThread(*args, **kwargs)
            workers.append(worker)
            return worker

        class Host:
            def close(self):
                pass

        window = Window.__new__(Window)
        window.root, window.host = Root(), Host()
        window.closing, window.reopen_config = False, 'synthetic-host.json'
        window.refresh_after = 'synthetic-refresh'
        try:
            with patch('companion.app.threading.Thread', side_effect=start_worker):
                window.close()
                window.close()
            self.assertTrue(host_closed.wait(2))
            self.assertEqual(len(workers), 1)
            self.assertEqual(window.root.cancelled, ['synthetic-refresh'])
            self.assertIsNone(window.refresh_after)
            window.root.pending.pop(0)()
            self.assertIsNone(window.root.destroyed_on)
            release_worker.set()
            workers[0].join(2)
            self.assertTrue(worker_finished.is_set())
            self.assertFalse(workers[0].is_alive())
            window.root.pending.pop(0)()
            self.assertEqual(window.root.destroyed_on, threading.get_ident())
            self.assertEqual(window.reopen_config, 'synthetic-host.json')
        finally:
            release_worker.set()
            for worker in workers:
                worker.join(3)

    def test_failed_shutdown_cancels_configuration_switch(self):
        class Host:
            def close(self):
                raise OSError('synthetic failure')

        workers = []
        real_thread = threading.Thread

        def start_worker(*args, **kwargs):
            worker = real_thread(*args, **kwargs)
            workers.append(worker)
            return worker

        window = Window.__new__(Window)
        window.root, window.host = Root(), Host()
        window.closing, window.reopen_config = False, 'synthetic-host.json'
        window.refresh_after = None
        with patch('companion.app.threading.Thread', side_effect=start_worker):
            window.close()
        workers[0].join(2)
        self.assertFalse(workers[0].is_alive())
        with patch('companion.app.messagebox.showerror') as error:
            window.root.pending.pop(0)()
        error.assert_called_once()
        self.assertIsNone(window.reopen_config)
        self.assertEqual(window.root.destroyed_on, threading.get_ident())


if __name__ == '__main__':
    unittest.main()
