"""Typed Codex adapter integration. Local policy and TLS identities are host-owned."""

from __future__ import annotations

from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading

from .journal import Conflict, Denied, canonical
from .child_job import KillOnCloseJob


class DesktopUnavailable(Exception):
    """No positive delivery evidence. Never authorizes a resend."""


class NodeDesktopRuntime:
    def __init__(self, policy_path, attempts_path, *, node=None, timeout=20):
        self.policy_path = Path(policy_path).resolve()
        self.attempts_path = Path(attempts_path).resolve()
        self.worker = Path(__file__).resolve().parents[1] / "desktop/runtime_worker.mjs"
        self.node = str(Path(node or shutil.which("node") or "missing-node").resolve())
        self.timeout = timeout

    def _exchange(self, action, operation, commit_guard=None):
        binding = operation["binding"]
        request = {"action": action, "label": operation["thread"], "targetId": binding["target"],
                   "messageId": binding["message_id"], "executionId": binding["execution_id"],
                   "text": json.loads(operation["payload"])["text"]}
        encoded = json.dumps(request, ensure_ascii=False, separators=(",", ":")).encode()+b"\n"
        if len(encoded) > 8192:
            raise DesktopUnavailable("worker frame limit")
        # Do not inherit agent identity, auth tokens or NODE_OPTIONS into the child.
        allowed_env = {"systemroot", "windir", "path", "temp", "tmp", "pathext"}
        environment = {k: v for k, v in os.environ.items() if k.lower() in allowed_env}
        process = subprocess.Popen([self.node, str(self.worker), str(self.policy_path), str(self.attempts_path)],
                                   cwd=self.worker.parent.parent, env=environment,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        timer = threading.Timer(self.timeout, process.kill)
        timer.start()
        job = None
        cleaned = False

        def cleanup():
            nonlocal cleaned
            if cleaned:
                return
            try:
                if job is not None:
                    job.close()
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=3)
                cleaned = True

        def read_reply():
            line = process.stdout.readline(8193)
            if not line.endswith(b"\n") or len(line) > 8192:
                raise DesktopUnavailable("worker reply unavailable")
            try:
                value = json.loads(line)
            except (ValueError, UnicodeError) as error:
                raise DesktopUnavailable("invalid worker reply") from error
            if not isinstance(value, dict):
                raise DesktopUnavailable("invalid worker reply")
            return value

        try:
            # Before the worker receives any input or commit permit. Closing the
            # parent's non-inherited job handle also kills it after host crashes.
            job = KillOnCloseJob(process)
            process.stdin.write(encoded)
            process.stdin.flush()
            reply = read_reply()
            if action == "send":
                expected = {"ready": True, "targetId": binding["target"], "messageId": binding["message_id"],
                            "executionId": binding["execution_id"]}
                if reply != expected or commit_guard is None:
                    raise DesktopUnavailable("handoff not ready")
                # Per-device gate: reauthorize immediately before releasing the
                # one-use child handoff permit; hold through its bounded reply.
                with commit_guard():
                    try:
                        process.stdin.write(b'{"commit":true}\n')
                        process.stdin.flush()
                        reply = read_reply()
                    finally:
                        cleanup()  # terminate/reap before releasing the device gate
            if reply.get("ok") is not True or not isinstance(reply.get("observation"), dict):
                raise DesktopUnavailable("desktop evidence unavailable")
            observation = reply["observation"]
            if observation.get("observed") is True:
                if observation.get("targetId") != binding["target"] or observation.get("messageId") != binding["message_id"]:
                    raise DesktopUnavailable("unexpected runtime identity")
            return observation
        except (OSError, ValueError) as error:
            raise DesktopUnavailable("desktop transport unavailable") from error
        finally:
            timer.cancel()
            cleanup()
            for stream in (process.stdin, process.stdout):
                try:
                    stream.close()
                except OSError:
                    pass

    def send(self, operation, commit_guard):
        observation = self._exchange("send", operation, commit_guard)
        if observation.get("observed") is not True:
            raise DesktopUnavailable("runtime receipt is not positive message evidence")
        return {"delivery": "observed", "turnId": observation["turnId"],
                "turnStatus": observation["turnStatus"], "evidence": "runtime-message-id"}

    def inspect(self, operation):
        return self._exchange("inspect", operation)


class DesktopRecoveryService:
    METHODS = {"submit": {"thread", "id", "payload"}, "status": {"id"},
               "sync": {"thread", "cursor"}, "reconcile": {"id"}}

    def __init__(self, journal, runtime, policy, *, devices):
        self.journal, self.runtime = journal, runtime
        self.policy = json.loads(canonical(policy))
        self.gates = {device: threading.RLock() for device in devices}

    def _gate(self, device):
        if device not in self.gates:
            raise Denied("unknown device")
        return self.gates[device]

    def _target(self, label):
        if not isinstance(label, str):
            raise ValueError("invalid target label")
        target = self.policy.get("targets", {}).get(label)
        if not target or target.get("kind") != "codex" or target.get("hostId") != "local" or not target.get("threadId"):
            raise Denied("target not authorized")
        return target

    def _check_operation(self, operation):
        target = self._target(operation["thread"])
        if not operation["binding"] or operation["binding"]["target"] != target["threadId"]:
            raise Conflict("runtime target changed")
        if json.loads(operation["payload"])["text"] not in target.get("allowedMessages", []):
            raise Denied("message not authorized")

    @contextmanager
    def response_guard(self, device):
        with self._gate(device):
            self.journal.authorize_device(device)
            yield

    def revoke(self, device):
        """Local administrator only. Never exposed by call()."""
        with self._gate(device):
            self.journal.revoke(device)

    def enqueue(self, device, label, operation_id, payload):
        """Local/submit admission. Persists immutable binding with acceptance."""
        target = self._target(label)
        if not isinstance(payload, dict) or payload.get("text") not in target.get("allowedMessages", []):
            raise Denied("message not authorized")
        if not {"read", "send"}.issubset(target.get("rights", [])):
            raise Denied("host send permission missing")
        with self._gate(device):
            self.journal.authorize(device, label, "read")
            return self.journal.submit(device, label, operation_id, payload, runtime_target=target["threadId"])

    def dispatch(self, device, operation_id):
        operation = self.journal.runtime_operation(device, operation_id)
        self._check_operation(operation)

        @contextmanager
        def commit_guard():
            with self._gate(device):
                self.journal.authorize(device, operation["thread"], "send")
                current = self.journal.runtime_operation(device, operation_id)
                self._check_operation(current)
                if current["state"] != "dispatching" or current["binding"] != operation["binding"]:
                    raise Conflict("operation no longer dispatching")
                yield

        result = self.journal.dispatch(device, operation_id, lambda _: self.runtime.send(operation, commit_guard))
        with self.response_guard(device):
            self.journal.authorize(device, operation["thread"], "read")
            return result

    def reconcile(self, device, operation_id):
        operation = self.journal.runtime_operation(device, operation_id)
        self._check_operation(operation)
        if operation["state"] not in {"unknown", "completed"}:
            return self.journal.status(device, operation_id)
        try:
            observation = self.runtime.inspect(operation)
        except DesktopUnavailable:
            with self.response_guard(device):
                return {**self.journal.status(device, operation_id), "reconciliation": "unavailable"}
        with self.response_guard(device):
            result = self.journal.record_runtime_observation(device, operation_id, operation["binding"], observation)
            return {**result, "reconciliation": "observed" if observation.get("observed") is True else "not_observed"}

    def call(self, device, method, params):
        if method not in self.METHODS:
            raise Denied("unsupported method")
        if not isinstance(params, dict) or set(params) != self.METHODS[method]:
            raise ValueError("unexpected request fields")
        self.journal.authorize_device(device)
        if method == "submit":
            self.enqueue(device, params["thread"], params["id"], params["payload"])
            return self.dispatch(device, params["id"])
        if method == "status":
            self._check_operation(self.journal.runtime_operation(device, params["id"]))
            return self.journal.status(device, params["id"])
        if method == "sync":
            target = self._target(params["thread"])
            self.journal.authorize_runtime_thread(device, params["thread"], target["threadId"])
            return self.journal.sync(device, params["thread"], params["cursor"])
        return self.reconcile(device, params["id"])
