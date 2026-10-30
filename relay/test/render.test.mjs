import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import WebSocket from 'ws';
import { createRelay } from '../render.mjs';
import { sha256, SUBPROTOCOL } from '../protocol.mjs';

test('Render adapter serves health but refuses anonymous WebSocket upgrade', async () => {
  const relay = createRelay({ source: '{}' });
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  const port = relay.server.address().port;
  try {
    assert.equal((await (await fetch(`http://127.0.0.1:${port}/healthz`)).json()).protocol, SUBPROTOCOL);
    const socket = new WebSocket(`ws://127.0.0.1:${port}/v1/relay/${'a'.repeat(64)}/client`, SUBPROTOCOL);
    const [error] = await once(socket, 'error'); assert.match(error.message, /403/);
  } finally { await relay.close(); }
});

test('Render wire path pairs only authorized host and signed phone', async () => {
  const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const hostId = await sha256(await crypto.subtle.exportKey('spki', keys.publicKey));
  const jwk = await crypto.subtle.exportKey('jwk', keys.publicKey);
  const publicKeyJwk = Object.fromEntries(['kty', 'crv', 'x', 'y'].map(k => [k, jwk[k]]));
  const token = 'a'.repeat(64), now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(JSON.stringify({ iss: hostId, sub: 'phone:' + 'b'.repeat(64), aud: 'device-bridge-relay-v1',
    iat: now, exp: now + 600, jti: 'c'.repeat(32) })).toString('base64url');
  const input = 'eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.' + payload;
  const signature = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, keys.privateKey, Buffer.from(input));
  const ticket = input + '.' + Buffer.from(signature).toString('base64url');
  const relay = createRelay({ source: JSON.stringify({ [hostId]: { publicKeyJwk, tokenSha256: await sha256(token) } }) });
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  const sockets = [];
  try {
    const url = `ws://127.0.0.1:${relay.server.address().port}/v1/relay/${hostId}/`;
    const host = new WebSocket(url + 'host', SUBPROTOCOL, { headers: { Authorization: 'Bearer ' + token } }); sockets.push(host);
    await once(host, 'open');
    const phone = new WebSocket(url + 'client', SUBPROTOCOL, { headers: { Authorization: 'Bearer ' + ticket } }); sockets.push(phone);
    await once(phone, 'open');
    const hostReady = once(host, 'message'), phoneReady = once(phone, 'message');
    host.send(JSON.stringify({ version: 2, hostId, role: 'host', revoked: [] }));
    phone.send(JSON.stringify({ version: 2, hostId, role: 'client' }));
    assert.equal((await hostReady)[0].toString(), '{"ready":true}\n'); await phoneReady;
    const received = once(host, 'message'); phone.send(Buffer.from([22, 3, 3, 0, 1]));
    assert.deepEqual([...(await received)[0]], [22, 3, 3, 0, 1]);
  } finally { sockets.forEach(s => s.terminate()); await relay.close(); }
});
