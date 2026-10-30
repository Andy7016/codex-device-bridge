"""Bounded JSONL plus TLS MemoryBIO over an already encrypted relay socket."""

import json
import socket
import ssl
import time

from prototype.loopback import parse_frame


def send_frame(stream, value, limit=8192):
    data = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode()+b'\n'
    if len(data) > limit:
        raise ValueError('frame_too_large')
    stream.sendall(data)


def read_frame(stream, limit=8192, *, timeout=8):
    result = bytearray()
    deadline = time.monotonic()+timeout
    while len(result) < limit:
        if time.monotonic() >= deadline:
            raise TimeoutError('frame_deadline')
        if hasattr(stream, 'settimeout'):
            stream.settimeout(max(.01, deadline-time.monotonic()))
        byte = stream.recv(1)
        if not byte:
            raise EOFError('reply_unavailable')
        result.extend(byte)
        if byte == b'\n':
            return parse_frame(bytes(result))
    raise ValueError('frame_too_large')


class TLSStream:
    """Inner TLS independent of socket fd; supports TLS-over-TLS correctly."""
    def __init__(self, transport, context, *, server_side=False, server_hostname=None, timeout=35):
        self.transport = transport
        self.deadline = time.monotonic()+timeout
        self.incoming, self.outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
        self.tls = context.wrap_bio(self.incoming, self.outgoing, server_side=server_side, server_hostname=server_hostname)
        self._call(self.tls.do_handshake)

    def _timeout(self):
        remaining = self.deadline-time.monotonic()
        if remaining <= 0:
            raise TimeoutError('tunnel_deadline')
        self.transport.settimeout(remaining)

    def _flush(self):
        while self.outgoing.pending:
            self._timeout()
            self.transport.sendall(self.outgoing.read())

    def _call(self, action):
        while True:
            try:
                result = action()
                self._flush()
                return result
            except ssl.SSLWantWriteError:
                self._flush()
            except ssl.SSLWantReadError:
                self._flush()
                self._timeout()
                data = self.transport.recv(16384)
                if not data:
                    raise EOFError('inner_tls_closed')
                self.incoming.write(data)

    def recv(self, size):
        return self._call(lambda: self.tls.read(size))

    def sendall(self, data):
        view = memoryview(data)
        while view:
            count = self._call(lambda: self.tls.write(view))
            view = view[count:]

    def getpeercert(self, binary_form=False):
        return self.tls.getpeercert(binary_form=binary_form)

    def close(self):
        self.transport.close()


def relay_connect(endpoint, preface, *, timeout=50, on_socket=None, on_waiting=None):
    if endpoint.get('transport') == 'wss':
        from .websocket_transport import connect
        return connect(endpoint, preface, timeout=timeout, on_socket=on_socket, on_waiting=on_waiting)
    from .security import client_context, check_pin
    plain = socket.create_connection((endpoint['host'], endpoint['port']), timeout=8)
    try:
        outer = client_context(endpoint).wrap_socket(plain, server_hostname=endpoint['host'])
        check_pin(outer, endpoint.get('certificatePem'))
        if on_socket:
            on_socket(outer)
        outer.settimeout(timeout)
        send_frame(outer, preface, 2048)
        if on_waiting:
            on_waiting()
        if read_frame(outer, 2048, timeout=timeout) != {'ready': True}:
            raise ConnectionError('relay_unavailable')
        return outer
    except BaseException:
        plain.close()
        if 'outer' in locals():
            outer.close()
        raise
