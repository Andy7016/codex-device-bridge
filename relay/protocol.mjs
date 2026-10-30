/** Shared admission and bounded opaque forwarding. No desktop RPC or TLS keys. */
export const SUBPROTOCOL = 'device-bridge.v1';
export const MAX_CHUNK = 16384;
export const MAX_BYTES = 1024 * 1024;
export const MAX_PEERS = 8;
export const MAX_TICKET_AGE = 30 * 86400;
const AUDIENCE = 'device-bridge-relay-v1';
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });
const HEX = /^[0-9a-f]{64}$/;
const HEADER = 'eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9';

export class Rejection extends Error {
  constructor(status = 403) { super('relay_rejected'); this.status = status; }
}

export function health() {
  return new Response(JSON.stringify({ service: 'device-bridge-relay', version: '0.3.0', protocol: SUBPROTOCOL }),
    { headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
}

export function rejection(error) {
  return new Response('relay_unavailable', { status: error instanceof Rejection ? error.status : 503,
    headers: { 'cache-control': 'no-store', 'retry-after': '5' } });
}

export function registrations(source) {
  if (typeof source !== 'string' || source.length > 32768) throw new Rejection(503);
  let value;
  try { value = JSON.parse(source); } catch { throw new Rejection(503); }
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length > 16) throw new Rejection(503);
  for (const [id, item] of Object.entries(value)) {
    if (!HEX.test(id) || !item || Object.keys(item).sort().join(',') !== 'publicKeyJwk,tokenSha256' ||
        !HEX.test(item.tokenSha256) || item.publicKeyJwk?.kty !== 'EC' || item.publicKeyJwk?.crv !== 'P-256' ||
        !/^[A-Za-z0-9_-]{43}$/.test(item.publicKeyJwk.x) || !/^[A-Za-z0-9_-]{43}$/.test(item.publicKeyJwk.y) ||
        Object.keys(item.publicKeyJwk).sort().join(',') !== 'crv,kty,x,y') throw new Rejection(503);
  }
  return value;
}

function unbase64(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Rejection();
  const binary = atob(value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4));
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}

export async function sha256(value) {
  const data = typeof value === 'string' ? encoder.encode(value) : value;
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', data)), x => x.toString(16).padStart(2, '0')).join('');
}

export async function authorize(request, source, now = Math.floor(Date.now() / 1000)) {
  const url = new URL(request.url);
  const match = /^\/v1\/relay\/([0-9a-f]{64})\/(host|client)$/.exec(url.pathname);
  if (!match || url.protocol !== 'https:' || url.search || request.method !== 'GET' || request.headers.has('origin') ||
      request.headers.get('upgrade')?.toLowerCase() !== 'websocket' ||
      request.headers.get('sec-websocket-protocol') !== SUBPROTOCOL) throw new Rejection();
  const [, hostId, role] = match;
  const registration = registrations(source)[hostId];
  if (!registration) throw new Rejection(); // No object allocation for arbitrary host IDs.
  const authorization = request.headers.get('authorization') || '';
  if (!authorization.startsWith('Bearer ') || authorization.length > 2055) throw new Rejection();
  const token = authorization.slice(7);
  if (role === 'host') {
    if (!HEX.test(token) || await sha256(token) !== registration.tokenSha256) throw new Rejection();
    return { hostId, role, subject: 'host', expires: now + 120 };
  }
  const parts = token.split('.');
  if (parts.length !== 3 || parts[0] !== HEADER || !/^[A-Za-z0-9_-]{86}$/.test(parts[2])) throw new Rejection();
  let claims;
  try { claims = JSON.parse(decoder.decode(unbase64(parts[1]))); } catch { throw new Rejection(); }
  if (!claims || Object.keys(claims).sort().join(',') !== 'aud,exp,iat,iss,jti,sub' ||
      claims.iss !== hostId || claims.aud !== AUDIENCE ||
      !/^(invite:[0-9a-f]{32}|phone:[0-9a-f]{64})$/.test(claims.sub) ||
      !/^[0-9a-f]{32}$/.test(claims.jti) || !Number.isSafeInteger(claims.iat) || !Number.isSafeInteger(claims.exp) ||
      claims.iat > now + 30 || claims.exp <= now || claims.exp <= claims.iat ||
      claims.exp - claims.iat > MAX_TICKET_AGE) throw new Rejection();
  const key = await crypto.subtle.importKey('jwk', registration.publicKeyJwk, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify']);
  if (await sha256(await crypto.subtle.exportKey('spki', key)) !== hostId ||
      !await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, unbase64(parts[2]), encoder.encode(parts[0] + '.' + parts[1]))) throw new Rejection();
  return { hostId, role, subject: claims.sub, expires: claims.exp };
}

/** Peers expose send/close/get/set; attachments are the sole connection state. */
export class RelayHub {
  constructor(peers, metadata = {}, clock = Date.now) {
    this.peers = peers;
    this.clock = clock;
    this.metadata = { revoked: [], window: 0, connections: 0, ...metadata };
  }

  open(peer, identity) {
    this.expire();
    const now = this.clock();
    const physical = this.peers();
    const active = physical.filter(p => p.get()?.phase !== 'closed');
    const window = Math.floor(now / 60000);
    if (window !== this.metadata.window) { this.metadata.window = window; this.metadata.connections = 0; }
    if (physical.length >= MAX_PEERS || active.filter(p => p.get()?.role === identity.role).length >= (identity.role === 'host' ? 2 : 4) ||
        this.metadata.connections >= 120) throw new Rejection(429);
    if (this.metadata.revoked.includes(identity.subject)) throw new Rejection();
    this.metadata.connections++;
    peer.set({ ...identity, id: crypto.randomUUID(), phase: 'hello', peer: null, bytes: 0, deadline: now + 5000 });
  }

  find(id) { return this.peers().find(p => p.get()?.id === id); }

  close(peer, code = 1008) {
    const state = peer.get();
    if (!state || state.phase === 'closed') return;
    const other = state.peer && this.find(state.peer);
    peer.set({ ...state, phase: 'closed' });
    if (other) { const data = other.get(); other.set({ ...data, phase: 'closed' }); }
    try { peer.close(code, 'tunnel_closed'); } catch { /* Already disconnected. */ }
    if (other) { try { other.close(code, 'tunnel_closed'); } catch { /* Already disconnected. */ } }
  }

  message(peer, message) {
    this.expire();
    let state = peer.get();
    if (!state || state.phase === 'closed') return;
    if (state.phase === 'hello') {
      let hello;
      try {
        if (typeof message !== 'string' || encoder.encode(message).length > 20000) throw new Error();
        hello = JSON.parse(message);
        const fields = state.role === 'host' ? 'hostId,revoked,role,version' : 'hostId,role,version';
        if (Object.keys(hello).sort().join(',') !== fields || hello.version !== 2 || hello.role !== state.role || hello.hostId !== state.hostId) throw new Error();
        if (state.role === 'host') {
          if (!Array.isArray(hello.revoked) || hello.revoked.length > 256 || hello.revoked.some(s => !/^phone:[0-9a-f]{64}$/.test(s))) throw new Error();
          const revoked = [...new Set([...this.metadata.revoked, ...hello.revoked])];
          if (revoked.length > 256) throw new Error();
          this.metadata.revoked = revoked;
          for (const p of this.peers()) if (revoked.includes(p.get()?.subject)) this.close(p);
        }
      } catch { this.close(peer); return; }
      state = { ...state, phase: 'waiting', deadline: this.clock() + 45000 };
      peer.set(state);
      const other = this.peers().find(p => {
        const s = p.get();
        return s?.phase === 'waiting' && s.hostId === state.hostId && s.role !== state.role && !this.metadata.revoked.includes(s.subject);
      });
      if (other) {
        const s = other.get();
        const deadline = Math.min(this.clock() + 45000, state.expires * 1000, s.expires * 1000);
        peer.set({ ...state, phase: 'paired', peer: s.id, deadline });
        other.set({ ...s, phase: 'paired', peer: state.id, deadline });
        try { other.send('{"ready":true}\n'); peer.send('{"ready":true}\n'); }
        catch { this.close(peer, 1011); }
      }
      return;
    }
    if (state.phase !== 'paired' || typeof message === 'string') { this.close(peer); return; }
    const data = message instanceof ArrayBuffer ? new Uint8Array(message) : message;
    const other = this.find(state.peer);
    const target = other?.get();
    if (!(data instanceof Uint8Array) || !data.byteLength || data.byteLength > MAX_CHUNK ||
        !target || target.phase !== 'paired' || target.peer !== state.id ||
        state.bytes + target.bytes + data.byteLength > MAX_BYTES ||
        (other.bufferedAmount || 0) > 65536) { this.close(peer); return; }
    peer.set({ ...state, bytes: state.bytes + data.byteLength });
    // The lifetime budget also bounds queued bytes on runtimes without bufferedAmount.
    try { other.send(data); } catch { this.close(peer, 1011); }
  }

  expire() {
    const now = this.clock();
    for (const peer of this.peers()) {
      const state = peer.get();
      if (state && state.phase !== 'closed' && (state.deadline <= now || state.expires * 1000 <= now)) this.close(peer);
    }
  }

  nextDeadline() {
    const deadlines = this.peers().map(p => p.get()).filter(s => s && s.phase !== 'closed').map(s => Math.min(s.deadline, s.expires * 1000));
    return deadlines.length ? Math.min(...deadlines) : null;
  }
}
