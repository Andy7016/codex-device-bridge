"""Bounded WebSocket carrier for the unchanged inner TLS byte stream."""
import json
import socket
import threading
import ipaddress
import asyncio
import atexit
from concurrent.futures import TimeoutError as FutureTimeout

from .security import check_pin, client_context


class _IoLoop:
    """One TLS I/O thread: no concurrent SSLSocket reads/writes during TLS 1.3 tickets."""
    instance = None
    lock = threading.Lock()

    def __init__(self):
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, name='bridge-wss-io', daemon=True)
        self.thread.start()
        atexit.register(self.stop)

    @classmethod
    def get(cls):
        with cls.lock:
            if cls.instance is None:
                cls.instance = cls()
            return cls.instance

    def call(self, coroutine, timeout):
        future = asyncio.run_coroutine_threadsafe(coroutine, self.loop)
        try:
            return future.result(timeout)
        except FutureTimeout:
            future.cancel()
            raise socket.timeout('websocket_deadline') from None

    def stop(self):
        if self.loop.is_running():
            self.loop.call_soon_threadsafe(self.loop.stop)
            self.thread.join(2)


class _Connection:
    def __init__(self, connection, io):
        self.connection, self.io = connection, io
        self.subprotocol = connection.subprotocol
        self.socket = connection.transport.get_extra_info('ssl_object')

    def send(self, data, timeout=8):
        return self.io.call(self.connection.send(data), timeout)

    def recv(self, timeout):
        return self.io.call(self.connection.recv(), timeout)

    def close(self):
        async def abort():
            self.connection.transport.abort()
            await self.connection.wait_closed()
        try:
            self.io.call(abort(), 2)
        except (OSError, TimeoutError):
            pass


class WebSocketTransport:
    def __init__(self, connection, timeout):
        self.connection = connection
        self.timeout = timeout
        self.pending = bytearray()
        self.tunnel = False
        self.closed = False
        self.lock = threading.Lock()

    def settimeout(self, value):
        self.timeout = value

    def sendall(self, data):
        from websockets.exceptions import WebSocketException
        try:
            if self.closed:
                raise OSError('carrier_closed')
            if not self.tunnel:
                if len(data) > 20000:
                    raise ValueError('relay_preface_too_large')
                self.connection.send(bytes(data).decode('utf8'), timeout=self.timeout)
            else:
                for offset in range(0, len(data), 16384):
                    self.connection.send(bytes(data[offset:offset + 16384]), timeout=self.timeout)
        except WebSocketException as error:
            raise OSError('websocket_send_failed') from error

    def recv(self, size):
        from websockets.exceptions import WebSocketException
        if not self.pending:
            try:
                value = self.connection.recv(timeout=self.timeout)
            except TimeoutError:
                raise socket.timeout('websocket_timeout') from None
            except WebSocketException as error:
                raise EOFError('websocket_closed') from error
            if self.tunnel:
                if not isinstance(value, bytes) or not 0 < len(value) <= 16384:
                    self.close()
                    raise ValueError('invalid_carrier_frame')
                self.pending.extend(value)
            else:
                if not isinstance(value, str) or value != '{"ready":true}\n':
                    self.close()
                    raise ValueError('invalid_relay_ready')
                self.pending.extend(value.encode())
        result = bytes(self.pending[:size])
        del self.pending[:size]
        return result

    def begin_tunnel(self):
        if self.pending:
            raise ValueError('unexpected_relay_bytes')
        self.tunnel = True

    def shutdown(self, how=socket.SHUT_RDWR):
        self.close()

    def close(self):
        with self.lock:
            if self.closed:
                return
            self.closed = True
            # Abort the event-loop transport; do not flush queued application writes.
            self.connection.close()


def connect(endpoint, preface, *, timeout=50, on_socket=None):
    from websockets.asyncio.client import connect as open_websocket
    from websockets.exceptions import WebSocketException
    host, role = endpoint['host'], preface['role']
    authority = '[' + host + ']' if ':' in host else host
    uri = 'wss://' + authority + ':' + str(endpoint['port']) + endpoint['path'] + '/' + preface['hostId'] + '/' + role
    token = preface['token'] if role == 'host' else endpoint['ticket']
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = False
    try:
        async def open_connection():
            return await open_websocket(uri, ssl=client_context(endpoint), additional_headers={'Authorization': 'Bearer ' + token},
                subprotocols=['device-bridge.v1'], compression=None, open_timeout=8, close_timeout=1,
                max_size=20000, max_queue=4, write_limit=65536, ping_interval=20, ping_timeout=20, proxy=None if loopback else True)
        io = _IoLoop.get()
        connection = _Connection(io.call(open_connection(), 9), io)
    except (WebSocketException, OSError) as error:
        raise OSError('websocket_connect_failed') from error
    carrier = WebSocketTransport(connection, timeout)
    try:
        check_pin(connection.socket, endpoint.get('certificatePem'))
        if connection.subprotocol != 'device-bridge.v1':
            raise ValueError('relay_protocol_mismatch')
        if on_socket:
            on_socket(carrier)
        hello = {'version': 2, 'hostId': preface['hostId'], 'role': role}
        if role == 'host':
            hello['revoked'] = preface.get('revoked', [])
        carrier.sendall(json.dumps(hello, separators=(',', ':')).encode() + b'\n')
        from .wire import read_frame
        if read_frame(carrier, 2048, timeout=timeout) != {'ready': True}:
            raise ConnectionError('relay_unavailable')
        carrier.begin_tunnel()
        return carrier
    except BaseException:
        carrier.close()
        raise
