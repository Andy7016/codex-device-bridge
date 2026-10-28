"""Durable recovery state for synthetic operations; never calls Codex or a shell."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3
import threading
import time

from .lease import WriterLease


class Denied(Exception):
    pass


class Conflict(Exception):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


class Journal:
    def __init__(self, path: Path, *, clock=time.time, retention=8):
        if retention < 1:
            raise ValueError("retention must be positive")
        path = Path(path).resolve()
        self.clock, self.retention = clock, retention
        self.lock = threading.RLock()
        self.lease = WriterLease(str(path)+".lock")
        try:
            self.db = sqlite3.connect(path, check_same_thread=False)
        except Exception:
            self.lease.close()
            raise
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, enabled INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS grants(device TEXT, thread TEXT, rights TEXT,
            PRIMARY KEY(device,thread));
          CREATE TABLE IF NOT EXISTS operations(device TEXT, id TEXT, thread TEXT,
            digest TEXT, payload TEXT, state TEXT, result TEXT,
            PRIMARY KEY(device,id));
          CREATE TABLE IF NOT EXISTS streams(thread TEXT PRIMARY KEY, seq INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS events(thread TEXT, seq INTEGER, body TEXT,
            PRIMARY KEY(thread,seq));
          CREATE TABLE IF NOT EXISTS approvals(id TEXT PRIMARY KEY, thread TEXT,
            digest TEXT, expires REAL, state TEXT, decision TEXT);
        """)
        # The process-scoped writer lease makes startup recovery exclusive.
        with self.db:
            rows = self.db.execute("SELECT * FROM operations WHERE state='dispatching'").fetchall()
            for row in rows:
                self.db.execute("UPDATE operations SET state='unknown' WHERE device=? AND id=?",
                                (row["device"], row["id"]))
                self._event(row["thread"], {"type": "operation", "device": row["device"], "id": row["id"], "state": "unknown"})

    def close(self):
        with self.lock:
            try:
                self.db.close()
            finally:
                self.lease.close()

    def provision(self, device, thread, rights=("read", "send", "approve")):
        """Test harness only; never exposed over the network."""
        with self.lock, self.db:
            self.db.execute("INSERT INTO devices VALUES(?,1) ON CONFLICT(id) DO UPDATE SET enabled=1", (device,))
            self.db.execute("INSERT INTO grants VALUES(?,?,?) ON CONFLICT(device,thread) DO UPDATE SET rights=excluded.rights",
                            (device, thread, canonical(sorted(rights))))

    def revoke(self, device):
        with self.lock, self.db:
            self.db.execute("UPDATE devices SET enabled=0 WHERE id=?", (device,))

    def _authorize(self, device, thread, right):
        row = self.db.execute("SELECT rights FROM grants JOIN devices ON devices.id=grants.device "
                              "WHERE device=? AND thread=? AND enabled=1", (device, thread)).fetchone()
        if not row or right not in json.loads(row["rights"]):
            raise Denied("operation not authorized")

    def _event(self, thread, body):
        row = self.db.execute("SELECT seq FROM streams WHERE thread=?", (thread,)).fetchone()
        seq = (row["seq"] if row else 0) + 1
        self.db.execute("INSERT INTO streams VALUES(?,?) ON CONFLICT(thread) DO UPDATE SET seq=excluded.seq", (thread, seq))
        self.db.execute("INSERT INTO events VALUES(?,?,?)", (thread, seq, canonical(body)))
        self.db.execute("DELETE FROM events WHERE thread=? AND seq<=?", (thread, seq-self.retention))

    def _get_operation(self, device, operation_id):
        row = self.db.execute("SELECT * FROM operations WHERE device=? AND id=?", (device, operation_id)).fetchone()
        if not row:
            raise Denied("operation not authorized")
        return row

    @staticmethod
    def _public(row):
        return {"device": row["device"], "id": row["id"], "state": row["state"],
                "result": json.loads(row["result"]) if row["result"] else None}

    def submit(self, device, thread, operation_id, payload):
        if not isinstance(operation_id, str) or not 1 <= len(operation_id) <= 128:
            raise ValueError("invalid operation id")
        if not isinstance(payload, dict) or set(payload) != {"text"} or not isinstance(payload["text"], str):
            raise ValueError("only synthetic text payloads are supported")
        digest = hashlib.sha256(canonical({"thread": thread, "payload": payload}).encode()).hexdigest()
        with self.lock, self.db:
            self._authorize(device, thread, "send")
            existing = self.db.execute("SELECT * FROM operations WHERE device=? AND id=?", (device, operation_id)).fetchone()
            if existing:
                if existing["digest"] != digest:
                    raise Conflict("operation id reused with a different request")
                return self._public(existing)
            self.db.execute("INSERT INTO operations VALUES(?,?,?,?,?,'accepted',NULL)",
                            (device, operation_id, thread, digest, canonical(payload)))
            self._event(thread, {"type": "operation", "device": device, "id": operation_id, "state": "accepted"})
            return {"device": device, "id": operation_id, "state": "accepted", "result": None}

    def dispatch(self, device, operation_id, adapter):
        with self.lock, self.db:
            row = self._get_operation(device, operation_id)
            self._authorize(device, row["thread"], "send")
            if row["state"] != "accepted":
                return self._public(row)
            self.db.execute("UPDATE operations SET state='dispatching' WHERE device=? AND id=?", (device, operation_id))
            self._event(row["thread"], {"type": "operation", "device": device, "id": operation_id, "state": "dispatching"})
            payload = json.loads(row["payload"])
        try:
            # The durable dispatch marker precedes the external call. An error
            # after this point is an unknown outcome and must never auto-retry.
            result = adapter(payload)
            encoded = canonical(result)
        except Exception:
            with self.lock, self.db:
                self.db.execute("UPDATE operations SET state='unknown' WHERE device=? AND id=?", (device, operation_id))
                self._event(row["thread"], {"type": "operation", "device": device, "id": operation_id, "state": "unknown"})
            return {"device": device, "id": operation_id, "state": "unknown", "result": None}
        with self.lock, self.db:
            self.db.execute("UPDATE operations SET state='completed', result=? WHERE device=? AND id=?", (encoded, device, operation_id))
            self._event(row["thread"], {"type": "operation", "device": device, "id": operation_id, "state": "completed"})
            return self._public(self._get_operation(device, operation_id))

    def status(self, device, operation_id):
        with self.lock:
            row = self._get_operation(device, operation_id)
            self._authorize(device, row["thread"], "read")
            return self._public(row)

    def sync(self, device, thread, cursor):
        if type(cursor) is not int or cursor < 0:
            raise ValueError("invalid cursor")
        with self.lock:
            self._authorize(device, thread, "read")
            stream = self.db.execute("SELECT seq FROM streams WHERE thread=?", (thread,)).fetchone()
            high = stream["seq"] if stream else 0
            if cursor > high:
                raise ValueError("cursor is ahead of stream")
            oldest = self.db.execute("SELECT MIN(seq) AS seq FROM events WHERE thread=?", (thread,)).fetchone()["seq"]
            if oldest is not None and cursor < oldest-1:
                rows = self.db.execute("SELECT * FROM operations WHERE thread=?", (thread,)).fetchall()
                return {"resetRequired": True, "cursor": high, "snapshot": [self._public(row) for row in rows], "events": []}
            rows = self.db.execute("SELECT seq,body FROM events WHERE thread=? AND seq>? ORDER BY seq", (thread, cursor)).fetchall()
            return {"resetRequired": False, "cursor": high,
                    "events": [{"seq": row["seq"], "body": json.loads(row["body"])} for row in rows]}

    def create_approval(self, approval_id, thread, action, expires):
        """Synthetic host fixture; real approval IDs must come from the runtime."""
        digest = hashlib.sha256(canonical(action).encode()).hexdigest()
        with self.lock, self.db:
            self.db.execute("INSERT INTO approvals VALUES(?,?,?,?,'pending',NULL)", (approval_id, thread, digest, expires))
        return digest

    def approve(self, device, approval_id, digest, decision):
        if decision not in {"allow", "deny"}:
            raise ValueError("invalid decision")
        with self.lock, self.db:
            row = self.db.execute("SELECT * FROM approvals WHERE id=?", (approval_id,)).fetchone()
            if not row:
                raise Denied("approval not authorized")
            self._authorize(device, row["thread"], "approve")
            if digest != row["digest"]:
                raise Conflict("approval action changed")
            if row["state"] != "pending":
                return {"state": "resolved", "decision": row["decision"]}
            if self.clock() >= row["expires"]:
                raise Conflict("approval expired")
            self.db.execute("UPDATE approvals SET state='resolved', decision=? WHERE id=?", (decision, approval_id))
            self._event(row["thread"], {"type": "approval", "id": approval_id, "decision": decision})
            return {"state": "resolved", "decision": decision}
