"""Loopback-only mTLS harness for the synthetic Journal.

No public bind, desktop adapter, relay, real account, or arbitrary command API.
TLS protects this test connection; end-to-end relay encryption is not implemented.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import socket
import socketserver
import ssl
import threading

from .journal import Conflict, Denied, canonical

MAX_FRAME = 8192


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def reject_constant(_):
    raise ValueError("non-finite JSON number")


def parse_frame(frame):
    if len(frame) > MAX_FRAME or not frame.endswith(b"\n"):
        raise ValueError("invalid frame length")
    obj = json.loads(frame, object_pairs_hook=unique_pairs, parse_constant=reject_constant)
    if not isinstance(obj, dict):
        raise ValueError("frame must be an object")
    return obj


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            self.request.do_handshake()
            fingerprint = hashlib.sha256(self.request.getpeercert(binary_form=True)).hexdigest()
            device = self.server.identities.get(fingerprint)
            if not device:
                raise Denied("unknown peer")
            with self.request.makefile("rb") as reader:
                frame = reader.readline(MAX_FRAME+1)
            request = parse_frame(frame)
            method, params = request.get("method"), request.get("params")
            if not isinstance(method, str) or not isinstance(params, dict):
                raise ValueError("invalid request")
            # Peer identity always comes from the TLS certificate, never JSON.
            if "device" in params or "role" in params:
                raise Denied("client cannot choose its authority")
            result = self.server.call(device, method, params)
            with self.server.fault_lock:
                if method == "submit" and params.get("id") in self.server.drop_ack_once:
                    self.server.drop_ack_once.remove(params["id"])
                    return
            response = {"ok": True, "result": result}
        except (ssl.SSLError, OSError):
            return
        except Denied:
            response = {"ok": False, "error": "denied"}
        except Conflict:
            response = {"ok": False, "error": "conflict"}
        except (ValueError, TypeError, KeyError, RecursionError):
            response = {"ok": False, "error": "invalid_request"}
        except Exception:
            response = {"ok": False, "error": "internal_error"}
        encoded = (canonical(response)+"\n").encode()
        if len(encoded) > MAX_FRAME:
            encoded = b'{"ok":false,"error":"response_too_large"}\n'
        try:
            self.request.sendall(encoded)
        except OSError:
            pass


class LoopbackServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = False

    def __init__(self, address, context, identities, journal, adapter):
        if not ipaddress.ip_address(address[0]).is_loopback:
            raise ValueError("laboratory server requires a loopback address")
        if context.verify_mode != ssl.CERT_REQUIRED:
            raise ValueError("mutual TLS is required")
        self.context, self.identities = context, dict(identities)
        self.journal, self.adapter = journal, adapter
        self.workers = threading.BoundedSemaphore(4)
        self.fault_lock = threading.Lock()
        self.drop_ack_once = set()
        super().__init__(address, Handler)

    def get_request(self):
        conn, addr = super().get_request()
        conn.settimeout(2)
        try:
            wrapped = self.context.wrap_socket(conn, server_side=True, do_handshake_on_connect=False)
        except Exception:
            conn.close()
            raise
        return wrapped, addr

    def process_request(self, request, client_address):
        if not self.workers.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.workers.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.workers.release()

    def call(self, device, method, params):
        if method == "submit":
            self.journal.submit(device, params["thread"], params["id"], params["payload"])
            return self.journal.dispatch(device, params["id"], self.adapter)
        if method == "status":
            return self.journal.status(device, params["id"])
        if method == "sync":
            return self.journal.sync(device, params["thread"], params["cursor"])
        if method == "approve":
            return self.journal.approve(device, params["id"], params["digest"], params["decision"])
        raise Denied("unsupported method")


def exchange(address, context, request):
    """One connection per attempt deliberately exercises reconnect behavior."""
    encoded = (canonical(request)+"\n").encode()
    if len(encoded) > MAX_FRAME:
        raise ValueError("request too large")
    with socket.create_connection(address, timeout=3) as plain:
        with context.wrap_socket(plain, server_hostname=address[0]) as conn:
            conn.sendall(encoded)
            with conn.makefile("rb") as reader:
                frame = reader.readline(MAX_FRAME+1)
            if not frame:
                raise EOFError("reply unavailable; query status before any retry")
            return parse_frame(frame)
