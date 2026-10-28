import assert from 'node:assert/strict';
import test from 'node:test';
import net from 'node:net';
import { once } from 'node:events';
import { FrameDecoder, NativePipeTransport, encodeFrame } from './transport.mjs';

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
