"""Read-only, redacted capability probe for an installed Codex app-server.

Only terminate the process this probe starts. Proxy mode never starts or stops
the shared daemon. No raw response, conversation body, credential, or ID is
written into the report. Target IDs are supplied by the operator, not discovered
by scanning conversation history.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time


READ_METHODS = frozenset({
    "initialize", "thread/read", "thread/turns/list", "thread/loaded/list",
    "project/list", "project/read", "model/list",
})


def error_class(message: str) -> str:
    lower = message.lower()
    for category, terms in {
        "no_home": ["home directory"],
        "control_socket_unavailable": ["control.sock", "socket", "10050"],
        "not_found": ["not found", "no such", "does not exist", "unable to find"],
        "unsupported": ["not supported", "unsupported", "unknown method", "method not found"],
        "requires_experimental": ["experimentalapi"],
        "permission_denied": ["permission denied", "access is denied", "os error 5"],
        "authentication": ["unauthorized", "authentication", "not logged"],
    }.items():
        if any(term in lower for term in terms):
            return category
    return "other"


class ReadOnlyProbe:
    def __init__(self, executable: str, transport: str):
        args = [executable, "app-server"]
        if transport == "proxy":
            args.append("proxy")
        else:
            args += ["--listen", "stdio://"]
        self.proc = subprocess.Popen(
            args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        self.messages: queue.Queue = queue.Queue()
        self.stderr_classes: Counter = Counter()
        self.notifications: Counter = Counter()
        self.next_id = 0
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    def _read_stdout(self):
        for line in self.proc.stdout:
            try:
                self.messages.put(json.loads(line))
            except json.JSONDecodeError:
                self.messages.put({"_invalid_json": True})
        self.messages.put({"_eof": True})

    def _read_stderr(self):
        for line in self.proc.stderr:
            self.stderr_classes[error_class(line)] += 1

    def request(self, method: str, params: dict, timeout: float = 15):
        if method not in READ_METHODS:
            raise ValueError("Probe refuses a method outside the read-only allowlist")
        self.next_id += 1
        request_id = self.next_id
        self.proc.stdin.write(json.dumps({"id": request_id, "method": method, "params": params}) + "\n")
        self.proc.stdin.flush()
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("RPC response timed out")
            msg = self.messages.get(timeout=remaining)
            if msg.get("_eof"):
                raise EOFError("Probe child closed stdout")
            if msg.get("id") == request_id and "method" not in msg:
                return msg
            # Never answer server-initiated approvals or tool requests.
            # Store only event method names, never parameters or content.
            if isinstance(msg.get("method"), str):
                self.notifications[msg["method"]] += 1

    def initialized(self):
        self.proc.stdin.write('{"method":"initialized","params":{}}\n')
        self.proc.stdin.flush()

    def close(self):
        if self.proc.stdin and not self.proc.stdin.closed:
            try:
                self.proc.stdin.close()
            except OSError:
                pass
        try:
            self.proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=3)


def summarize_reply(reply: dict) -> dict:
    if "error" in reply:
        error = reply["error"]
        message = str(error.get("message", ""))
        message = re.sub(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", "<id>", message)
        message = message.replace(str(Path.home()), "<user-home>")
        return {"ok": False, "errorCode": error.get("code"),
                "errorClass": error_class(message), "errorMessage": message[:300]}
    return {"ok": True}


def run_probe(args) -> dict:
    report = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "transport": args.transport,
        "newRuntime": args.transport == "stdio",
        "readOnlyRpc": True,
        "checks": [],
    }
    client = ReadOnlyProbe(args.codex, args.transport)
    try:
        init = client.request("initialize", {"clientInfo": {
            "name": "codex_device_bridge_probe", "version": "0.1.0",
        }, "capabilities": {"experimentalApi": True}}, timeout=25)
        report["checks"].append({"method": "initialize", **summarize_reply(init)})
        if "error" in init:
            return report
        client.initialized()

        loaded = client.request("thread/loaded/list", {})
        report["checks"].append({"method": "thread/loaded/list", **summarize_reply(loaded),
                                 "count": len(loaded.get("result", {}).get("data", []))})
        projects = client.request("project/list", {"limit": 50})
        listed_projects = projects.get("result", {}).get("data", [])
        project_check = {"method": "project/list", **summarize_reply(projects),
                         "count": len(listed_projects)}
        if args.project_id:
            project_check["expectedIdInList"] = any(p.get("id") == args.project_id for p in listed_projects)
        if args.project_root:
            expected_root = str(Path(args.project_root).resolve()).casefold()
            project_check["matchingRootCount"] = sum(
                any(str(Path(root.get("path", "")).resolve()).casefold() == expected_root
                    for root in p.get("roots", [])) for p in listed_projects)
        report["checks"].append(project_check)
        if args.project_id:
            project = client.request("project/read", {"projectId": args.project_id})
            report["checks"].append({"method": "project/read", **summarize_reply(project),
                                     "idMatches": project.get("result", {}).get("project", {}).get("id") == args.project_id})
        models = client.request("model/list", {"includeHidden": False})
        report["checks"].append({"method": "model/list", **summarize_reply(models),
                                 "count": len(models.get("result", {}).get("data", []))})

        for label, thread_id in args.target:
            read = client.request("thread/read", {"threadId": thread_id, "includeTurns": False})
            check = {"method": "thread/read", "target": label, **summarize_reply(read)}
            if "result" in read:
                thread = read["result"].get("thread", {})
                check.update({"idMatches": thread.get("id") == thread_id,
                              "status": thread.get("status", {}).get("type"),
                              "hasProjectId": bool(thread.get("projectId"))})
            report["checks"].append(check)
            history = client.request("thread/turns/list", {
                "threadId": thread_id, "limit": 1, "itemsView": "summary",
            })
            history_check = {"method": "thread/turns/list", "target": label,
                             **summarize_reply(history)}
            if "result" in history:
                turns = history["result"].get("data", [])
                history_check.update({"returnedTurns": len(turns),
                                      "itemTypes": dict(Counter(
                                          item.get("type", "unknown")
                                          for turn in turns for item in turn.get("items", [])
                                      ))})
            report["checks"].append(history_check)
    except (OSError, EOFError, TimeoutError, queue.Empty) as exc:
        report["failure"] = type(exc).__name__
    finally:
        client.close()
        report["childExitCode"] = client.proc.returncode
        report["stderrClasses"] = dict(client.stderr_classes)
        report["notifications"] = dict(client.notifications)
    return report


def parse_target(value: str):
    label, sep, thread_id = value.partition("=")
    if not sep or label not in {"codex", "chatgpt"} or not thread_id:
        raise argparse.ArgumentTypeError("Use codex=ID or chatgpt=ID")
    return label, thread_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True)
    parser.add_argument("--transport", choices=["proxy", "stdio"], required=True)
    parser.add_argument("--target", action="append", type=parse_target, default=[])
    parser.add_argument("--project-id")
    parser.add_argument("--project-root")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = run_probe(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if report.get("failure") else 0


if __name__ == "__main__":
    raise SystemExit(main())
