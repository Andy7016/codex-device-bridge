"""TLS rendezvous and bounded opaque byte forwarding; no desktop RPC parser."""
import argparse
from collections import defaultdict, deque
import hashlib
import hmac
import ipaddress
import json
from pathlib import Path
import re
import select
import signal
import socket
import socketserver
import ssl
import threading
import time

from .wire import read_frame, send_frame


class Waiter:
    def __init__(self, stream):
        self.stream = stream
        self.claimed, self.done = threading.Event(), threading.Event()
        self.expires = time.monotonic()+45
        self.expired = False


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        stream, matched = None, False
        try:
            self.request.settimeout(4)
            stream = self.server.context.wrap_socket(self.request, server_side=True)
            with self.server.lock:
                self.server.connections.add(stream)
            frame = read_frame(stream, 2048)
            identity = frame.get('hostId')
            if type(frame.get('version')) is not int or frame['version'] != 1 or not isinstance(identity, str) or not re.fullmatch('[0-9a-f]{64}', identity):
                raise ValueError('invalid_relay_preface')
            role = frame.get('role')
            if role == 'host' and set(frame) == {'version', 'role', 'hostId', 'token'}:
                token = frame['token']
                expected = self.server.tokens.get(identity)
                if not isinstance(token, str) or not 32 <= len(token) <= 128 or not expected or not hmac.compare_digest(expected, hashlib.sha256(token.encode()).hexdigest()):
                    raise ValueError('relay_auth_denied')
                waiter = Waiter(stream)
                with self.server.lock:
                    queue = self.server.waiters[identity]
                    if len(queue) >= 2:
                        raise ValueError('relay_capacity')
                    queue.append(waiter)
                    self.server.available.notify_all()
                try:
                    while not waiter.claimed.wait(.1) and time.monotonic() < waiter.expires:
                        # A disconnected host must not leave a stale routing
                        # slot until the 45s admission deadline. Claim and EOF
                        # detection share the same lock; no concurrent SSL read.
                        with self.server.lock:
                            if not waiter.claimed.is_set() and select.select([stream], [], [], 0)[0]:
                                stream.settimeout(.1)
                                try:
                                    if not stream.recv(1):
                                        return
                                    raise ValueError('unexpected_waiter_data')
                                except ssl.SSLWantReadError:
                                    pass
                    with self.server.lock:
                        if not waiter.claimed.is_set():
                            waiter.expired = True
                            if waiter in queue:
                                queue.remove(waiter)
                        claimed = waiter.claimed.is_set() and not waiter.expired
                    if claimed:
                        waiter.done.wait(46)
                finally:
                    with self.server.lock:
                        queue = self.server.waiters[identity]
                        if waiter in queue:
                            queue.remove(waiter)
                return
            if role != 'client' or set(frame) != {'version', 'role', 'hostId'}:
                raise ValueError('invalid_relay_preface')
            with self.server.lock:
                waiter = None
                admission_deadline = time.monotonic()+2
                while waiter is None and identity in self.server.tokens and not self.server.stopping.is_set():
                    queue = self.server.waiters.get(identity)
                    while queue:
                        candidate = queue.popleft()
                        if not candidate.expired and time.monotonic() < candidate.expires:
                            waiter = candidate
                            break
                        candidate.expired = True
                    remaining = admission_deadline-time.monotonic()
                    if waiter or remaining <= 0:
                        break
                    self.server.available.wait(remaining)
                if waiter is None:
                    raise ValueError('relay_unavailable')
                waiter.claimed.set()
            try:
                matched = True
                stream.settimeout(4)
                waiter.stream.settimeout(4)
                send_frame(waiter.stream, {'ready': True}, 2048)
                send_frame(stream, {'ready': True}, 2048)
                self.forward(stream, waiter.stream)
            finally:
                waiter.done.set()
        except (OSError, ValueError, EOFError, KeyError, TypeError):
            if stream and not matched:
                try:
                    send_frame(stream, {'ready': False}, 2048)
                except OSError:
                    pass
        finally:
            if stream:
                with self.server.lock:
                    self.server.connections.discard(stream)
                stream.close()

    def forward(self, left, right):
        deadline = time.monotonic()+45
        transferred = 0
        # One thread owns all SSL calls for the two endpoints. Bounded send
        # timeout and total bytes protect the relay from stalled consumers.
        while time.monotonic() < deadline and not self.server.stopping.is_set():
            ready = [s for s in (left, right) if s.pending()]
            if not ready:
                ready, _, _ = select.select([left, right], [], [], min(1, max(0, deadline-time.monotonic())))
            for source in ready:
                data = source.recv(16384)
                if not data:
                    return
                transferred += len(data)
                if transferred > 1024*1024:
                    return
                destination = right if source is left else left
                destination.sendall(data)


class RelayServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = False

    def __init__(self, address, context, registrations, *, allow_public=False):
        if not allow_public and not ipaddress.ip_address(address[0]).is_loopback:
            raise ValueError('public_bind_requires_explicit_opt_in')
        self.context = context
        self.tokens = dict(registrations)
        if any(not re.fullmatch('[0-9a-f]{64}', k) or not re.fullmatch('[0-9a-f]{64}', v) for k, v in self.tokens.items()):
            raise ValueError('invalid_registration')
        self.lock = threading.RLock()
        self.available = threading.Condition(self.lock)
        self.waiters = defaultdict(deque)
        self.connections = set()
        self.capacity = threading.BoundedSemaphore(32)
        self.stopping = threading.Event()
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.capacity.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.capacity.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.capacity.release()

    def stop(self):
        self.stopping.set()
        with self.lock:
            self.available.notify_all()
            for queue in self.waiters.values():
                for waiter in queue:
                    waiter.claimed.set()
                    waiter.done.set()
            for stream in list(self.connections):
                try:
                    stream.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                stream.close()
        self.shutdown()
        self.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8443)
    parser.add_argument('--allow-public', action='store_true')
    parser.add_argument('--service', action='store_true', help='wait for SIGINT/SIGTERM; do not depend on interactive stdin')
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding='utf8'))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(config['certificate'], config['privateKey'])
    server = RelayServer((args.bind, args.port), context, config['registrations'], allow_public=args.allow_public)
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .1})
    thread.start()
    print(json.dumps({'ready': True, 'port': server.server_address[1]}), flush=True)
    try:
        if args.service:
            stop = threading.Event()
            signal.signal(signal.SIGTERM, lambda *_: stop.set())
            signal.signal(signal.SIGINT, lambda *_: stop.set())
            stop.wait()
        else:
            while input().strip() != 'shutdown':
                pass
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        server.stop()
        thread.join(3)


if __name__ == '__main__':
    main()
