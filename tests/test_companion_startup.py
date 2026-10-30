"""Synthetic offline initialization and local GUI flows; no desktop conversation writes."""
import hashlib
import json
from pathlib import Path
import socket
import tempfile
import time
import unittest
from unittest.mock import patch

try:
    import cryptography
except ImportError:
    raise unittest.SkipTest('install companion dependencies for startup tests')

from companion.configuration import load_configuration, policy_snapshot, prepare, read_json, relay_endpoint, remembered_configuration, remember_configuration, update_configuration
from companion.host import Host
from companion.security import unprotect

ROOT = Path(__file__).resolve().parents[1]
POLICY = {'targets': {'codex': {'kind': 'codex', 'hostId': 'local', 'threadId': 'synthetic-startup-thread',
                              'rights': ['read', 'send'], 'allowedMessages': ['synthetic text']}}}


class StartupTests(unittest.TestCase):
    def setUp(self):
        (ROOT/'test-results').mkdir(exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix='startup-', dir=ROOT/'test-results'))
        self.output = self.directory/'computer'
    def make(self, **kwargs):
        prepare(self.output, **kwargs)
        return self.output/'host.json'
    def test_offline_preparation_and_import_need_no_network(self):
        with patch('socket.create_connection', side_effect=AssertionError('network not permitted')):
            path = self.make()
            config, policy = load_configuration(path)
            self.assertIsNone(config['identity']['relay'])
            self.assertEqual(read_json(policy), {'targets': {}})
            self.assertFalse(Path(config['policy']).is_absolute())
            self.assertIn('keyPem', json.loads(unprotect((self.output/'private.dpapi').read_bytes())))
            host = Host(path)
            try:
                host.start()
                self.assertEqual(host.threads, [])
                self.assertIsNone(host.listener)
                self.assertIn('未配置中继', host.status)
                with self.assertRaises(ValueError):
                    host.pairing.invite()
            finally:
                host.close()
    def test_existing_identity_is_never_overwritten(self):
        path = self.make()
        original = path.read_bytes(), (self.output/'private.dpapi').read_bytes()
        with self.assertRaises(FileExistsError):
            self.make()
        self.assertEqual(original, (path.read_bytes(), (self.output/'private.dpapi').read_bytes()))
    def test_optional_policy_is_snapshotted_and_source_preserved(self):
        source = self.directory/'source-policy.json'
        source.write_text(json.dumps(POLICY))
        original = source.read_bytes()
        path = self.make(policy=source)
        source.write_text('{"targets":{}}')
        _, local_policy = load_configuration(path)
        self.assertEqual(read_json(local_policy), POLICY)
        self.assertEqual(json.loads(original), POLICY)
    def test_bad_fields_do_not_create_an_identity(self):
        for kwargs in ({'display_name': ''}, {'relay_host': 'https://example.com'}, {'relay_host': 'relay.example', 'relay_port': 0}):
            with self.assertRaises(ValueError):
                self.make(**kwargs)
            self.assertFalse(self.output.exists())
    def test_incomplete_import_is_rejected_without_regeneration(self):
        path = self.make()
        private = self.output/'private.dpapi'
        private.rename(self.output/'private.saved')
        with self.assertRaisesRegex(ValueError, 'identity_files_missing'):
            load_configuration(path)
        self.assertFalse(private.exists())
        self.assertTrue((self.output/'private.saved').exists())
    def test_settings_preserve_identity_private_data_and_journal(self):
        path = self.make()
        host = Host(path)
        host.close()
        protected = {name: (self.output/name).read_bytes() for name in ['private.dpapi', 'server.pem', 'journal.sqlite']}
        identity = read_json(path)['identity']['hostId']
        update_configuration(path, relay_host='127.0.0.1', relay_port=65534, use_system_trust=True)
        config, _ = load_configuration(path)
        self.assertEqual(identity, config['identity']['hostId'])
        self.assertEqual(config['identity']['relay']['port'], 65534)
        self.assertTrue(list(self.output.glob('host-*.json.bak')))
        for name, value in protected.items():
            self.assertEqual((self.output/name).read_bytes(), value)
        update_configuration(path)
        self.assertIsNone(read_json(path)['identity']['relay'])
    def test_unreachable_relay_does_not_block_startup(self):
        endpoint = socket.socket()
        endpoint.bind(('127.0.0.1', 0))
        port = endpoint.getsockname()[1]
        endpoint.close()
        path = self.make(relay_host='127.0.0.1', relay_port=port)
        host = Host(path)
        try:
            started = time.monotonic()
            host.start()
            self.assertLess(time.monotonic()-started, 1)
            deadline = time.monotonic()+6
            while host.status != '中继连接待恢复' and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertEqual(host.status, '中继连接待恢复')
        finally:
            host.close()
    def test_remembered_configuration_survives_restart_but_invalid_pointer_is_ignored(self):
        path = self.make()
        location = self.directory/'launcher'
        remember_configuration(location, path)
        self.assertEqual(remembered_configuration(location), path)
        (location/'startup.json').write_text('{"schemaVersion":1,"configPath":"not-a-real-file"}')
        self.assertIsNone(remembered_configuration(location))
        self.assertTrue(path.exists())
    def test_setting_changes_validate_before_writing(self):
        path = self.make()
        original = path.read_bytes()
        with self.assertRaises(ValueError):
            update_configuration(path, relay_host='bad address')
        self.assertEqual(path.read_bytes(), original)
        self.assertEqual(list(self.output.glob('*.bak')), [])

    def test_saved_relay_certificate_is_retained_unless_system_trust_is_explicit(self):
        path = self.make()
        update_configuration(path, relay_host='relay.example', relay_certificate=self.output/'server.pem')
        original = read_json(path)['identity']['relay']['certificatePem']
        update_configuration(path, relay_host='relay.example')
        self.assertEqual(read_json(path)['identity']['relay']['certificatePem'], original)
        update_configuration(path, relay_host='relay.example', use_system_trust=True)
        self.assertIsNone(read_json(path)['identity']['relay']['certificatePem'])

    def test_larger_response_does_not_relax_default_request_or_json_validation(self):
        from prototype.loopback import parse_frame
        frame = (json.dumps({'text':'x'*9000})+'\n').encode()
        with self.assertRaises(ValueError): parse_frame(frame)
        self.assertEqual(parse_frame(frame, limit=65536)['text'], 'x'*9000)
        with self.assertRaises(ValueError): parse_frame(frame, limit=65537)
        duplicate = ('{"text":"'+('x'*9000)+'","text":"duplicate"}\n').encode()
        with self.assertRaises(ValueError): parse_frame(duplicate, limit=65536)


class WizardGuiTests(unittest.TestCase):
    def setUp(self):
        import tkinter as tk
        (ROOT/'test-results').mkdir(exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix='wizard-', dir=ROOT/'test-results'))
        self.root = tk.Tk()
        self.root.withdraw()
    def tearDown(self):
        self.root.destroy()
    def test_wizard_creates_and_selects_host_without_relay_or_policy(self):
        from companion.startup import StartupWizard
        from companion.app import Window
        wizard = StartupWizard(self.root, self.directory)
        wizard.output.set(str(self.directory/'created'))
        with patch('companion.startup.messagebox.showerror', side_effect=AssertionError('wizard error')):
            wizard.create()
        self.assertEqual(wizard.selected, self.directory/'created'/'host.json')
        wizard.body.destroy()
        host = Host(wizard.selected)
        try:
            host.start()
            window = Window(self.root, host)
            self.assertTrue(window.invite_button.instate(['disabled']))
            self.assertEqual(window.grants, {})
            window.closing = True
            self.root.update()
        finally:
            host.close()
    def test_existing_configuration_is_selected_without_copy_or_identity_changes(self):
        from companion.startup import StartupWizard
        prepare(self.directory/'existing')
        path = self.directory/'existing'/'host.json'
        original = path.read_bytes()
        wizard = StartupWizard(self.root, self.directory)
        with patch('companion.startup.filedialog.askopenfilename', return_value=str(path)), patch('companion.startup.messagebox.showerror', side_effect=AssertionError('import error')):
            wizard.import_existing()
        self.assertEqual(wizard.selected, path)
        self.assertEqual(path.read_bytes(), original)

    def test_many_sessions_default_to_read_only_and_remain_scrollable(self):
        from companion.app import Window
        source = self.directory/'many-policy.json'
        source.write_text(json.dumps({'targets':{str(index):{'kind':'codex','hostId':'local',
            'threadId':'synthetic-'+str(index),'displayName':'Synthetic '+str(index),'rights':['read']}
            for index in range(100)}}))
        prepare(self.directory/'many', policy=source)
        host = Host(self.directory/'many/host.json')
        try:
            window = Window(self.root, host)
            self.root.update_idletasks()
            self.assertEqual(len(window.grants), 100)
            self.assertTrue(all(read.get() and not send.get() for read,send in window.grants.values()))
            self.assertLess(window.grants_canvas.yview()[1], 1)
            window.grants_canvas.yview_moveto(1)
            self.assertGreater(window.grants_canvas.yview()[0], 0)
            window.closing = True
        finally:
            host.close()


if __name__ == '__main__':
    unittest.main()
