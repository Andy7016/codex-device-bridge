import test from 'node:test';
import assert from 'node:assert/strict';
import { authorize, sha256, registrations, RelayHub, SUBPROTOCOL, MAX_BYTES } from '../protocol.mjs';

const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
const hostId = await sha256(await crypto.subtle.exportKey('spki', pair.publicKey));
const fullJwk = await crypto.subtle.exportKey('jwk', pair.publicKey);
const publicKeyJwk = Object.fromEntries(['kty', 'crv', 'x', 'y'].map(k => [k, fullJwk[k]]));
const token = 'a'.repeat(64);
const source = JSON.stringify({ [hostId]: { publicKeyJwk, tokenSha256: await sha256(token) } });
const now = Math.floor(Date.now() / 1000);
const subject = 'phone:' + 'b'.repeat(64);
const b64 = value => Buffer.from(value).toString('base64url');
async function ticket(change = {}) {
  const claims = { iss: hostId, sub: subject, aud: 'device-bridge-relay-v1', iat: now, exp: now + 600, jti: 'c'.repeat(32), ...change };
  const input = b64('{"alg":"ES256","typ":"JWT"}') + '.' + b64(JSON.stringify(claims));
  const signature = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, pair.privateKey, Buffer.from(input));
  return input + '.' + b64(signature);
}
function request(role, bearer, extra = {}) {
  return new Request(`https://relay.invalid/v1/relay/${hostId}/${role}`, { headers: {
    upgrade: 'websocket', 'sec-websocket-protocol': SUBPROTOCOL, authorization: 'Bearer ' + bearer, ...extra } });
}

test('host and host-signed phone admission; roles cannot be exchanged', async () => {
  assert.equal((await authorize(request('host', token), source)).role, 'host');
  assert.equal((await authorize(request('client', await ticket()), source)).subject, subject);
  await assert.rejects(authorize(request('client', token), source));
  await assert.rejects(authorize(request('host', await ticket()), source));
});

test('missing, forged, expired, wrong audience and excessive lifetime are denied', async () => {
  await assert.rejects(authorize(request('host', 'd'.repeat(64)), source));
  for (const change of [{ exp: now }, { aud: 'other' }, { iat: now + 300 }, { exp: now + 31 * 86400 }, { sub: 'host' }]) {
    await assert.rejects(authorize(request('client', await ticket(change)), source));
  }
  const signed = await ticket();
  await assert.rejects(authorize(request('client', signed.slice(0, -5) + 'abcde'), source));
  await assert.rejects(authorize(request('client', signed, { origin: 'https://untrusted.invalid' }), source));
  await assert.rejects(authorize(request('client', signed, { 'sec-websocket-protocol': 'other' }), source));
});

test('registration key must match stable SPKI identity', async () => {
  const wrong = JSON.stringify({ [hostId]: { publicKeyJwk: { ...publicKeyJwk, x: 'A'.repeat(43) }, tokenSha256: await sha256(token) } });
  await assert.rejects(authorize(request('client', await ticket()), wrong));
  assert.throws(() => registrations('[]'));
  assert.throws(() => registrations('{"x":{}}'));
});

function fixture() {
  let clock = now * 1000;
  const peers = [];
  let hub = new RelayHub(() => peers, {}, () => clock);
  function open(role, sub = role === 'host' ? 'host' : subject) {
    const p = { data: null, sent: [], closed: false, bufferedAmount: 0,
      get() { return this.data; }, set(v) { this.data = structuredClone(v); },
      send(v) { this.sent.push(v); }, close() { this.closed = true; } };
    hub.open(p, { hostId, role, subject: sub, expires: now + 1000 }); peers.push(p); return p;
  }
  const hello = (p, revoked = []) => hub.message(p, JSON.stringify({ version: 2, hostId, role: p.get().role,
    ...(p.get().role === 'host' ? { revoked } : {}) }));
  return { peers, open, hello, get hub() { return hub; },
    advance(ms) { clock += ms; hub.expire(); },
    hibernate() { hub = new RelayHub(() => peers, structuredClone(hub.metadata), () => clock); } };
}

test('waits for both authenticated hellos then forwards opaque ordered bytes', () => {
  const f = fixture(), client = f.open('client'), host = f.open('host');
  f.hello(client); assert.deepEqual(client.sent, []);
  f.hello(host); assert.equal(client.sent[0], '{"ready":true}\n');
  for (const chunk of [new Uint8Array([22, 3]), new Uint8Array([3, 0, 2])]) f.hub.message(client, chunk);
  assert.deepEqual(host.sent.slice(1).map(x => [...x]), [[22, 3], [3, 0, 2]]);
});

test('attachment state survives simulated hibernation without re-pairing', () => {
  const f = fixture(), host = f.open('host'), phone = f.open('client'); f.hello(host); f.hello(phone);
  const id = phone.get().id; f.hibernate(); f.hub.message(phone, new Uint8Array([1]));
  assert.equal(phone.get().id, id); assert.deepEqual([...host.sent.at(-1)], [1]);
});

test('revocation closes active peer and cannot be cleared by an older host snapshot', () => {
  const f = fixture(), h = f.open('host'), p = f.open('client'); f.hello(h); f.hello(p);
  const h2 = f.open('host'); f.hello(h2, [subject]); assert.equal(p.closed, true);
  f.hibernate(); assert.throws(() => f.open('client'));
  const h3 = f.open('host'); f.hello(h3, []); assert.throws(() => f.open('client'));
});

test('binary before ready, text after ready, and oversized binary close the tunnel', () => {
  const a = fixture(), unauth = a.open('client'); a.hub.message(unauth, new Uint8Array([1])); assert.ok(unauth.closed);
  for (const message of ['plaintext', new Uint8Array(16385)]) {
    const f = fixture(), h = f.open('host'), p = f.open('client'); f.hello(h); f.hello(p);
    f.hub.message(p, message); assert.ok(h.closed && p.closed);
  }
});

test('hard lifetime byte budget and slow consumer bound buffered bytes', () => {
  for (const slow of [false, true]) {
    const f = fixture(), h = f.open('host'), p = f.open('client'); f.hello(h); f.hello(p);
    if (slow) h.bufferedAmount = 65537;
    else p.set({ ...p.get(), bytes: MAX_BYTES });
    f.hub.message(p, new Uint8Array([1])); assert.ok(h.closed && p.closed);
  }
});

test('hello deadline, waiting deadline and paired deadline are absolute', () => {
  const a = fixture(), p = a.open('client'); a.advance(5001); assert.ok(p.closed);
  const b = fixture(), q = b.open('client'); b.hello(q); b.advance(45001); assert.ok(q.closed);
  const c = fixture(), h = c.open('host'), r = c.open('client'); c.hello(h); c.hello(r);
  c.advance(44000); c.hub.message(r, new Uint8Array([1])); c.advance(1001); assert.ok(h.closed && r.closed);
});

test('capacity and rate caps remain after hibernation', () => {
  const f = fixture(); f.open('host'); f.open('host'); assert.throws(() => f.open('host'));
  f.hub.metadata.connections = 120; f.hibernate(); assert.throws(() => f.open('client'));
});

test('closing sockets count against physical capacity until the runtime removes them', () => {
  const f = fixture();
  for (let i = 0; i < 8; i++) { const peer = f.open('client'); f.hub.close(peer); }
  assert.throws(() => f.open('client'));
});
