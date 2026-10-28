from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import unittest
import uuid

from prototype.journal import Conflict, Denied, Journal
from prototype.loopback import LoopbackServer, MAX_FRAME, exchange


ROOT = Path(__file__).resolve().parents[1]


class CountingAdapter:
    def __init__(self):
        self.calls = 0
        self.lock = threading.Lock()

    def __call__(self, payload):
        with self.lock:
            self.calls += 1
        return {"syntheticEcho": payload["text"]}


class RecoveryLab(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = (ROOT / "test-results").resolve()
        cls.root.mkdir(exist_ok=True)
        cls.scratch = cls.root / ("loopback-"+uuid.uuid4().hex)
        cls.scratch.mkdir()
        openssl = shutil.which("openssl")
        if not openssl:
            raise RuntimeError("OpenSSL CLI is required to generate ephemeral lab certificates")
        for name in ["server", "phone", "desktop", "outsider"]:
            subprocess.run([
                openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-days", "1", "-subj", "/CN="+name,
                "-addext", "subjectAltName=IP:127.0.0.1",
                "-keyout", str(cls.scratch / (name+".key")),
                "-out", str(cls.scratch / (name+".pem")),
            ], check=True, capture_output=True, timeout=20, cwd=cls.scratch)
        cls.trust = cls.scratch / "devices.pem"
        cls.trust.write_text("".join((cls.scratch / (name+".pem")).read_text()
                                    for name in ["phone", "desktop"]), encoding="ascii")
        cls.identities = {}
        for name in ["phone", "desktop"]:
            der = ssl.PEM_cert_to_DER_cert((cls.scratch / (name+".pem")).read_text())
            cls.identities[hashlib.sha256(der).hexdigest()] = name

    @classmethod
    def tearDownClass(cls):
        # Inspect the exact task-owned directory, then unlink direct files only.
        # No recursive cleanup, symlinks, parent traversal or profile writes.
        if cls.scratch.parent != cls.root or not cls.scratch.name.startswith("loopback-"):
            raise RuntimeError("unexpected cleanup target")
        contents = list(cls.scratch.iterdir())
        if any(p.is_symlink() or not p.is_file() or p.resolve().parent != cls.scratch
               for p in contents):
            raise RuntimeError("unexpected cleanup contents; preserved for inspection")
        for path in contents:
            path.unlink()
        cls.scratch.rmdir()

    def setUp(self):
        self.path = self.scratch / (uuid.uuid4().hex+".sqlite")
        self.now = 1000.0
        self.journal = Journal(self.path, clock=lambda: self.now, retention=5)
        self.journal.provision("phone", "thread-a")
        self.journal.provision("desktop", "thread-a")
        self.adapter = CountingAdapter()
        self.servers = []

    def tearDown(self):
        for server, thread in reversed(self.servers):
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
        self.journal.close()

    def client_context(self, name="phone", trust="server"):
        context = ssl.create_default_context(cafile=str(self.scratch / (trust+".pem")))
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(self.scratch / (name+".pem")), str(self.scratch / (name+".key")))
        return context

    def server_context(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(self.scratch / "server.pem"), str(self.scratch / "server.key"))
        context.verify_mode = ssl.CERT_REQUIRED
        context.load_verify_locations(cafile=str(self.trust))
        return context

    def start_server(self):
        server = LoopbackServer(("127.0.0.1", 0), self.server_context(), self.identities, self.journal, self.adapter)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02})
        thread.start()
        self.servers.append((server, thread))
        return server

    def request(self, server, method, params, name="phone"):
        return exchange(server.server_address, self.client_context(name), {"method": method, "params": params})

    @staticmethod
    def operation(operation_id="op-1", text="synthetic hello", thread="thread-a"):
        return {"thread": thread, "id": operation_id, "payload": {"text": text}}

    def test_lost_ack_reconnect_query_and_duplicate_execute_once(self):
        server = self.start_server()
        server.drop_ack_once.add("op-1")
        with self.assertRaises((EOFError, ssl.SSLError)):
            self.request(server, "submit", self.operation())
        self.assertEqual(self.request(server, "status", {"id": "op-1"})["result"]["state"], "completed")
        self.assertEqual(self.request(server, "submit", self.operation())["result"]["state"], "completed")
        self.assertEqual(self.adapter.calls, 1)

    def test_restart_preserves_deduplication(self):
        self.journal.submit("phone", "thread-a", "op-1", {"text": "synthetic"})
        self.journal.dispatch("phone", "op-1", self.adapter)
        self.journal.close()
        self.journal = Journal(self.path)
        self.journal.submit("phone", "thread-a", "op-1", {"text": "synthetic"})
        self.assertEqual(self.journal.dispatch("phone", "op-1", self.adapter)["state"], "completed")
        self.assertEqual(self.adapter.calls, 1)

    def test_network_host_restart_restores_events_and_operation(self):
        server = self.start_server()
        self.request(server, "submit", self.operation())
        initial = self.request(server, "sync", {"thread": "thread-a", "cursor": 0})["result"]
        _, thread = self.servers.pop()
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)
        self.journal.close()
        self.journal = Journal(self.path)
        server = self.start_server()
        resumed = self.request(server, "sync", {"thread": "thread-a", "cursor": initial["cursor"]})["result"]
        self.assertEqual(resumed["events"], [])
        self.assertEqual(resumed["cursor"], initial["cursor"])
        self.assertEqual(self.request(server, "submit", self.operation())["result"]["state"], "completed")
        self.assertEqual(self.adapter.calls, 1)

    def test_concurrent_duplicate_submissions_dispatch_once(self):
        server = self.start_server()
        with ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [pool.submit(self.request, server, "submit", self.operation()) for _ in range(2)]
            results = [job.result(timeout=5) for job in jobs]
        self.assertTrue(all(result["ok"] for result in results))
        self.assertEqual(self.request(server, "status", {"id": "op-1"})["result"]["state"], "completed")
        self.assertEqual(self.adapter.calls, 1)

    def test_same_operation_id_from_different_devices_has_distinct_identity(self):
        for device in ["phone", "desktop"]:
            self.journal.submit(device, "thread-a", "same-id", {"text": "synthetic"})
        events = self.journal.sync("phone", "thread-a", 0)["events"]
        self.assertEqual({(e["body"]["device"], e["body"]["id"]) for e in events},
                         {("phone", "same-id"), ("desktop", "same-id")})

    def test_second_writer_cannot_recover_an_active_journal(self):
        with self.assertRaises(RuntimeError):
            Journal(self.path)
        self.journal.submit("phone", "thread-a", "op-1", {"text": "synthetic"})
        self.assertEqual(self.journal.status("phone", "op-1")["state"], "accepted")

    def test_actual_child_crash_after_side_effect_is_unknown_and_not_retried(self):
        crash_db = self.scratch / (uuid.uuid4().hex+".sqlite")
        marker = self.scratch / (uuid.uuid4().hex+".marker")
        code = """import os, sys
from pathlib import Path
from prototype.journal import Journal
j=Journal(Path(sys.argv[1]))
j.provision('phone','thread-a')
j.submit('phone','thread-a','crash-op',{'text':'synthetic'})
def crash(payload):
    Path(sys.argv[2]).write_text('one synthetic side effect', encoding='utf-8')
    os._exit(23)
j.dispatch('phone','crash-op',crash)
"""
        process = subprocess.run([sys.executable, "-c", code, str(crash_db), str(marker)],
                                 cwd=ROOT, capture_output=True, timeout=10)
        self.assertEqual(process.returncode, 23)
        self.assertTrue(marker.exists())
        recovered = Journal(crash_db)
        try:
            self.assertEqual(recovered.status("phone", "crash-op")["state"], "unknown")
            self.assertEqual(recovered.dispatch("phone", "crash-op", self.adapter)["state"], "unknown")
            self.assertEqual(self.adapter.calls, 0)
        finally:
            recovered.close()

    def test_missing_events_trigger_authoritative_snapshot(self):
        for i in range(3):
            self.journal.submit("phone", "thread-a", f"op-{i}", {"text": "synthetic"})
            self.journal.dispatch("phone", f"op-{i}", self.adapter)
        result = self.journal.sync("phone", "thread-a", 0)
        self.assertTrue(result["resetRequired"])
        self.assertEqual(len(result["snapshot"]), 3)
        self.assertTrue(all(op["state"] == "completed" for op in result["snapshot"]))
        self.assertEqual(self.journal.sync("phone", "thread-a", result["cursor"])["events"], [])

    def test_retained_events_resume_in_order_without_overlap(self):
        self.journal.submit("phone", "thread-a", "op-1", {"text": "synthetic"})
        first = self.journal.sync("phone", "thread-a", 0)
        self.journal.dispatch("phone", "op-1", self.adapter)
        resumed = self.journal.sync("phone", "thread-a", first["cursor"])
        self.assertFalse(resumed["resetRequired"])
        self.assertEqual([e["seq"] for e in resumed["events"]], [2, 3])

    def test_operation_id_with_changed_payload_is_rejected(self):
        server = self.start_server()
        self.request(server, "submit", self.operation())
        result = self.request(server, "submit", self.operation(text="changed"))
        self.assertEqual(result["error"], "conflict")
        self.assertEqual(self.adapter.calls, 1)

    def test_unpaired_certificate_is_rejected_before_dispatch(self):
        server = self.start_server()
        with self.assertRaises((ssl.SSLError, OSError, EOFError)):
            self.request(server, "submit", self.operation(), name="outsider")
        self.assertEqual(self.adapter.calls, 0)

    def test_wrong_server_trust_is_rejected(self):
        server = self.start_server()
        with self.assertRaises(ssl.SSLCertVerificationError):
            exchange(server.server_address, self.client_context(trust="outsider"),
                     {"method": "submit", "params": self.operation()})
        self.assertEqual(self.adapter.calls, 0)

    def test_certificate_identity_cannot_be_overridden_by_body(self):
        server = self.start_server()
        params = self.operation()
        params["device"] = "desktop"
        self.assertEqual(self.request(server, "submit", params)["error"], "denied")
        self.assertEqual(self.adapter.calls, 0)

    def test_cross_thread_access_and_scope_expansion_are_denied(self):
        server = self.start_server()
        self.assertEqual(self.request(server, "submit", self.operation(thread="thread-b"))["error"], "denied")
        self.assertEqual(self.request(server, "sync", {"thread": "thread-b", "cursor": 0})["error"], "denied")
        self.assertEqual(self.request(server, "process/spawn", {"command": "never executed"})["error"], "denied")
        self.assertEqual(self.adapter.calls, 0)

    def test_revocation_blocks_reconnect_pending_dispatch_and_old_results(self):
        server = self.start_server()
        self.journal.submit("phone", "thread-a", "queued", {"text": "synthetic"})
        self.journal.revoke("phone")
        self.assertEqual(self.request(server, "submit", self.operation())["error"], "denied")
        self.assertEqual(self.request(server, "status", {"id": "queued"})["error"], "denied")
        with self.assertRaises(Denied):
            self.journal.dispatch("phone", "queued", self.adapter)
        self.assertEqual(self.adapter.calls, 0)

    def test_read_only_device_cannot_send_or_approve(self):
        self.journal.provision("phone", "thread-a", rights=("read",))
        with self.assertRaises(Denied):
            self.journal.submit("phone", "thread-a", "op-1", {"text": "synthetic"})
        digest = self.journal.create_approval("approval", "thread-a", {"synthetic": True}, 1100)
        with self.assertRaises(Denied):
            self.journal.approve("phone", "approval", digest, "allow")

    def test_expired_and_changed_approval_rejected(self):
        digest = self.journal.create_approval("expired", "thread-a", {"synthetic": True}, 999)
        with self.assertRaises(Conflict):
            self.journal.approve("phone", "expired", digest, "allow")
        self.journal.create_approval("current", "thread-a", {"synthetic": True}, 1100)
        with self.assertRaises(Conflict):
            self.journal.approve("phone", "current", "different-action", "allow")

    def test_concurrent_approval_decisions_resolve_once(self):
        server = self.start_server()
        digest = self.journal.create_approval("approval", "thread-a", {"synthetic": True}, 1100)
        def decide(name, choice):
            return self.request(server, "approve", {"id": "approval", "digest": digest, "decision": choice}, name)["result"]
        with ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [pool.submit(decide, "phone", "allow"), pool.submit(decide, "desktop", "deny")]
            results = [job.result(timeout=5) for job in jobs]
        self.assertEqual(results[0], results[1])
        events = self.journal.sync("phone", "thread-a", 0)["events"]
        self.assertEqual(sum(e["body"]["type"] == "approval" for e in events), 1)

    def test_duplicate_json_keys_and_oversized_frames_fail_without_dispatch(self):
        server = self.start_server()
        for frame in [b'{"method":"status","method":"submit","params":{}}\n', b"x"*(MAX_FRAME+1)+b"\n"]:
            with socket.create_connection(server.server_address, timeout=3) as plain:
                with self.client_context().wrap_socket(plain, server_hostname="127.0.0.1") as conn:
                    conn.sendall(frame)
                    with conn.makefile("rb") as reader:
                        reply = json.loads(reader.readline(MAX_FRAME+1))
                    self.assertEqual(reply["error"], "invalid_request")
        self.assertEqual(self.adapter.calls, 0)
        self.assertTrue(self.request(server, "submit", self.operation())["ok"])

    def test_forged_cursor_rejected(self):
        for cursor in [-1, True, 999, "0"]:
            with self.assertRaises(ValueError):
                self.journal.sync("phone", "thread-a", cursor)

    def test_public_bind_and_missing_mtls_are_refused(self):
        with self.assertRaises(ValueError):
            LoopbackServer(("0.0.0.0", 0), self.server_context(), {}, self.journal, self.adapter)
        with self.assertRaises(ValueError):
            LoopbackServer(("127.0.0.1", 0), ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER), {}, self.journal, self.adapter)


if __name__ == "__main__":
    unittest.main()
