import assert from 'node:assert/strict';
import test from 'node:test';
import net from 'node:net';
import { once } from 'node:events';
import { FrameDecoder, StreamFrameDecoder, NativePipeTransport, encodeFrame } from './transport.mjs';

test('coordination stream handles split headers and several coalesced frames', () => {
  const frames = [{ n: 1 }, { n: 2 }, { n: 3 }];
  const encoded = Buffer.concat(frames.map(value => encodeFrame(value)));
  for (const width of [1, 3, 7, encoded.length]) {
    const decoder = new StreamFrameDecoder(64), received = [];
    for (let offset = 0; offset < encoded.length; offset += width) {
      decoder.push(encoded.subarray(offset, offset + width), body => received.push(JSON.parse(body.toString())));
    }
    assert.deepEqual(received, frames);
    assert.equal(decoder.body, null);
  }
});

test('coordination stream accepts a bounded history frame above the old 8MiB limit', () => {
  const value = { synthetic: 'x'.repeat(9 * 1024 * 1024) };
  const encoded = encodeFrame(value, 16 * 1024 * 1024);
  const decoder = new StreamFrameDecoder();
  let received = 0;
  for (let offset = 0; offset < encoded.length; offset += 65536) {
    decoder.push(encoded.subarray(offset, offset + 65536), body => {
      assert.equal(JSON.parse(body.toString()).synthetic.length, value.synthetic.length);
      received++;
    });
  }
  assert.equal(received, 1);
  assert.equal(decoder.body, null);
});

test('coordination rejects oversize declarations before allocating a body', () => {
  for (const size of [0, 16 * 1024 * 1024 + 1, 0xffffffff]) {
    const decoder = new StreamFrameDecoder(), header = Buffer.alloc(4);
    header.writeUInt32LE(size);
    assert.throws(() => decoder.push(header, () => assert.fail('unexpected frame')), { code: 'frame_too_large' });
    assert.equal(decoder.body, null);
  }
  for (const limit of [0, -1, 1.5, 16 * 1024 * 1024 + 1]) assert.throws(() => new StreamFrameDecoder(limit), RangeError);
});

async function mockServer(t, handle) {
  const sockets = new Set();
  const server = net.createServer(socket => {
    sockets.add(socket);
    socket.on('error', () => {});
    socket.on('close', () => sockets.delete(socket));
    const decoder = new FrameDecoder();
    socket.on('data', chunk => {
      const request = decoder.push(chunk);
      if (request) handle(socket, request);
    });
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => server.close(resolve));
  });
  const address = server.address();
  return { transport: new NativePipeTransport({ endpoint: '\\\\.\\pipe\\codex-app-tools-synthetic',
    connect: () => net.createConnection({ host: '127.0.0.1', port: address.port }), timeoutMs: 100 }) };
}

test('framing accepts fragmented native-pipe responses', () => {
  const frame = encodeFrame({ id: 'synthetic', result: {} });
  const decoder = new FrameDecoder();
  for (const byte of frame.subarray(0, -1)) assert.equal(decoder.push(Buffer.from([byte])), null);
  assert.deepEqual(decoder.push(frame.subarray(-1)), { id: 'synthetic', result: {} });
});

test('oversized and concatenated responses are rejected', () => {
  const header = Buffer.alloc(4); header.writeUInt32LE(4096);
  assert.throws(() => new FrameDecoder(16).push(header), { code: 'frame_too_large' });
  const frame = encodeFrame({ result: {} });
  assert.throws(() => new FrameDecoder().push(Buffer.concat([frame, frame])), { code: 'unexpected_extra_data' });
});

test('request matches the response identity', async t => {
  const { transport } = await mockServer(t, (socket, request) => socket.write(encodeFrame({ jsonrpc: '2.0', id: request.id, result: { tools: [] } })));
  assert.deepEqual(await transport.request('tools/list', {}), { tools: [] });
});

test('mismatched response identity fails closed', async t => {
  const { transport } = await mockServer(t, socket => socket.write(encodeFrame({ jsonrpc: '2.0', id: 'other', result: {} })));
  await assert.rejects(transport.request('tools/list', {}), { code: 'invalid_response_identity' });
});

test('timeout after writing has an unknown delivery result and no automatic retry', async t => {
  let received = 0;
  const { transport } = await mockServer(t, () => received++);
  await assert.rejects(transport.request('tools/call', {}), error => error.code === 'response_timeout' && error.deliveryUnknown);
  assert.equal(received, 1);
});

test('arbitrary remote endpoints and raw runtime methods are rejected', () => {
  assert.throws(() => new NativePipeTransport({ endpoint: 'wss://outside.invalid' }), { code: 'missing_inherited_desktop_endpoint' });
  const transport = new NativePipeTransport({ endpoint: '\\\\.\\pipe\\codex-app-tools-synthetic' });
  assert.throws(() => transport.request('turn/start', {}), { code: 'unsupported_transport_method' });
});
