import net from 'node:net';
import { randomUUID } from 'node:crypto';

export class TransportFailure extends Error {
  constructor(code, deliveryUnknown = false) {
    super(code);
    this.code = code;
    this.deliveryUnknown = deliveryUnknown;
  }
}

export function encodeFrame(message, maxBytes = 8 * 1024 * 1024) {
  const body = Buffer.from(JSON.stringify(message));
  if (body.length > maxBytes) throw new TransportFailure('frame_too_large');
  const frame = Buffer.alloc(4 + body.length);
  frame.writeUInt32LE(body.length);
  body.copy(frame, 4);
  return frame;
}

export class FrameDecoder {
  constructor(maxBytes = 8 * 1024 * 1024) {
    this.maxBytes = maxBytes;
    this.buffer = Buffer.alloc(0);
  }
  push(chunk) {
    // One response per connection; bound buffering before allocating it.
    if (this.buffer.length + chunk.length > this.maxBytes + 4) throw new TransportFailure('frame_too_large');
    this.buffer = Buffer.concat([this.buffer, chunk]);
    if (this.buffer.length < 4) return null;
    const size = this.buffer.readUInt32LE();
    if (size > this.maxBytes) throw new TransportFailure('frame_too_large');
    if (this.buffer.length < 4 + size) return null;
    if (this.buffer.length !== 4 + size) throw new TransportFailure('unexpected_extra_data');
    let result;
    try { result = JSON.parse(this.buffer.subarray(4).toString('utf8')); }
    catch { throw new TransportFailure('invalid_json'); }
    this.buffer = Buffer.alloc(0);
    return result;
  }
}

export class NativePipeTransport {
  constructor({ endpoint = process.env.CODEX_APP_TOOLS_PIPE_PATH, timeoutMs = 8000,
                connect = value => net.createConnection(value) } = {}) {
    if (typeof endpoint !== 'string' || !endpoint.startsWith('\\\\.\\pipe\\codex-app-tools-')) {
      throw new TransportFailure('missing_inherited_desktop_endpoint');
    }
    this.endpoint = endpoint;
    this.timeoutMs = timeoutMs;
    this.connect = connect;
  }
  request(method, params) {
    if (!['tools/list', 'tools/call'].includes(method)) throw new TransportFailure('unsupported_transport_method');
    const id = randomUUID();
    const frame = encodeFrame({ jsonrpc: '2.0', id, method, params });
    return new Promise((resolve, reject) => {
      const socket = this.connect(this.endpoint);
      const decoder = new FrameDecoder();
      let settled = false;
      let written = false;
      const finish = (error, result) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        socket.destroy();
        error ? reject(error) : resolve(result);
      };
      const timer = setTimeout(() => finish(new TransportFailure('response_timeout', written)), this.timeoutMs);
      socket.once('connect', () => { written = true; socket.write(frame); });
      socket.on('data', chunk => {
        try {
          const reply = decoder.push(chunk);
          if (reply === null) return;
          if (reply?.id !== id || reply?.jsonrpc !== '2.0') throw new TransportFailure('invalid_response_identity', written);
          if (reply.error) throw new TransportFailure('desktop_rpc_error', written);
          if (!Object.hasOwn(reply, 'result')) throw new TransportFailure('missing_result', written);
          finish(null, reply.result);
        } catch (error) { finish(error); }
      });
      socket.once('error', () => finish(new TransportFailure('connection_error', written)));
      socket.once('close', () => finish(new TransportFailure('closed_before_response', written)));
    });
  }
}
