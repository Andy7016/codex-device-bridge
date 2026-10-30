"""Five hidden Tk close/reload cycles using an entirely synthetic offline host."""
import sys
import threading
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import companion.app as app

closed = []
created = []
original_window = app.Window


class Host:
    def __init__(self, path):
        self.config_path = Path(path)
        self.identity = {'hostId': 'synthetic-lifecycle', 'relay': None}
        self.service = SimpleNamespace(policy={'targets': {}})
        self.pairing = SimpleNamespace(pending=lambda: [], devices=lambda: [])
        self.status = 'synthetic offline'
        self.stop_event = threading.Event()
        self.phone_status = SimpleNamespace(text=lambda: 'synthetic offline')
        created.append(self)

    def start(self):
        pass

    def close(self):
        closed.append(threading.get_ident())
        self.stop_event.set()


class Window(original_window):
    def __init__(self, root, host):
        super().__init__(root, host)
        root.withdraw()
        if len(created) < 5:
            self.reopen_config = 'synthetic-host.json'
        root.after(15, self.close)


with patch.object(sys, 'argv', ['probe', '--config', 'synthetic-host.json']), \
        patch.object(app, 'Host', Host), patch.object(app, 'Window', Window), \
        patch.object(app, 'remember_configuration'):
    app.main()
assert len(created) == len(closed) == 5
assert all(ident != threading.get_ident() for ident in closed)
assert all(host.stop_event.is_set() for host in created)
assert not [t for t in threading.enumerate() if t is not threading.main_thread()]
print('PASS: 5 offline Tk close/reload cycles; 0 live worker threads')
