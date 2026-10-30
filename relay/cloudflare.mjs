import { DurableObject } from 'cloudflare:workers';
import { authorize, health, rejection, RelayHub, SUBPROTOCOL } from './protocol.mjs';

function peer(socket) {
  return { get: () => socket.deserializeAttachment(), set: value => socket.serializeAttachment(value),
    send: value => socket.send(value), close: (code, reason) => socket.close(code, reason) };
}

export class DeviceRelay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.hub = new RelayHub(() => ctx.getWebSockets().map(peer));
    ctx.blockConcurrencyWhile(async () => {
      this.hub.metadata = { ...this.hub.metadata, ...(await ctx.storage.get('metadata') || {}) };
    });
  }

  async save(metadata = false) {
    if (metadata) await this.ctx.storage.put('metadata', this.hub.metadata);
    const deadline = this.hub.nextDeadline();
    if (deadline === null) await this.ctx.storage.deleteAlarm();
    else await this.ctx.storage.setAlarm(deadline);
  }

  async fetch(request) {
    let server;
    try {
      // Recheck admission at the object boundary; never trust internal-looking request headers.
      const identity = await authorize(request, this.env.RELAY_REGISTRATIONS_JSON);
      const pair = Object.values(new WebSocketPair());
      const client = pair[0];
      server = pair[1];
      // Check capacity before acceptance, then transfer the bounded attachment.
      const pending = { value: null, get() { return this.value; }, set(value) { this.value = value; } };
      this.hub.open(pending, identity);
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment(pending.value);
      await this.save(true);
      return new Response(null, { status: 101, webSocket: client, headers: { 'Sec-WebSocket-Protocol': SUBPROTOCOL } });
    } catch (error) {
      try { server?.close(1008, 'rejected'); } catch { /* Not yet accepted. */ }
      return rejection(error);
    }
  }

  async webSocketMessage(socket, message) {
    const hello = peer(socket).get()?.phase === 'hello';
    this.hub.message(peer(socket), message);
    if (hello) await this.save(true); // Ciphertext never writes persistent storage.
  }

  async webSocketClose(socket) { this.hub.close(peer(socket)); await this.save(); }
  async webSocketError(socket) { this.hub.close(peer(socket), 1011); await this.save(); }
  async alarm() { this.hub.expire(); await this.save(); }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === 'GET' && !url.search && ['/', '/healthz'].includes(url.pathname)) return health();
    try {
      const identity = await authorize(request, env.RELAY_REGISTRATIONS_JSON);
      return await env.RELAYS.getByName(identity.hostId).fetch(request);
    } catch (error) { return rejection(error); }
  },
};
