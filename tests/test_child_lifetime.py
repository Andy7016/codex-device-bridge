import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from prototype.desktop import DesktopRecoveryService
from prototype.journal import Journal

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.name == 'nt', 'Windows Job Object contract')
class ChildLifetime(unittest.TestCase):
    def test_parent_crash_kills_committed_child_before_restart_revocation(self):
        root = (ROOT / 'test-results').resolve(); root.mkdir(exist_ok=True)
        scratch = Path(tempfile.mkdtemp(prefix='lifetime-', dir=root)).resolve()
        marker, release = scratch / 'effect.marker', scratch / 'release.marker'
        database = scratch / 'journal.sqlite'
        child_code = """import sys,time
from pathlib import Path
assert sys.stdin.readline().strip()=='commit'
print('paused',flush=True)
while not Path(sys.argv[1]).exists():time.sleep(0.01)
Path(sys.argv[2]).write_text('unexpected fake runtime write')
"""
        host_code = """import json,os,subprocess,sys
from pathlib import Path
from prototype.child_job import KillOnCloseJob
from prototype.journal import Journal
j=Journal(Path(sys.argv[1]));j.provision('phone','codex')
j.submit('phone','codex','op',{'text':'synthetic'},runtime_target='synthetic-thread')
def handoff(payload):
    child=subprocess.Popen([sys.executable,'-c',sys.argv[2],sys.argv[3],sys.argv[4]],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
    job=KillOnCloseJob(child)
    child.stdin.write('commit\\n');child.stdin.flush()
    assert child.stdout.readline().strip()=='paused'
    print(json.dumps({'childPid':child.pid}),flush=True)
    assert sys.stdin.readline().strip()=='crash'
    os._exit(23)
j.dispatch('phone','op',handoff)
"""
        host = subprocess.Popen([sys.executable, '-c', host_code, str(database), child_code, str(release), str(marker)],
                                cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        child_handle = None
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.WaitForSingleObject.restype = wintypes.DWORD
        kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        import threading
        watchdog = threading.Timer(8, host.kill); watchdog.start()
        try:
            line = host.stdout.readline()
            self.assertTrue(line, host.stderr.read() if host.poll() is not None else 'missing host readiness')
            child_handle = kernel.OpenProcess(0x00100001, False, json.loads(line)['childPid'])
            self.assertTrue(child_handle)
            host.stdin.write('crash\n'); host.stdin.flush()
            self.assertEqual(host.wait(timeout=4), 23)
            journal = Journal(database)
            try:
                self.assertEqual(journal.status('phone', 'op')['state'], 'unknown')
                service = DesktopRecoveryService(journal, None, {'targets': {}}, devices=('phone',))
                service.revoke('phone')
                release.touch()
                self.assertEqual(kernel.WaitForSingleObject(child_handle, 1500), 0)
                self.assertFalse(marker.exists())
            finally:
                journal.close()
        finally:
            watchdog.cancel()
            if host.poll() is None:
                host.kill()
            host.wait(timeout=3)
            if child_handle:
                if kernel.WaitForSingleObject(child_handle, 0) != 0:
                    kernel.TerminateProcess(child_handle, 1)
                    kernel.WaitForSingleObject(child_handle, 1500)
                kernel.CloseHandle(child_handle)
            for stream in (host.stdin, host.stdout, host.stderr):
                stream.close()
            self.assertEqual(scratch.parent, root)
            files = list(scratch.iterdir())
            self.assertTrue(all(p.is_file() and not p.is_symlink() and p.resolve().parent == scratch for p in files))
            for p in files:
                p.unlink()
            scratch.rmdir()
