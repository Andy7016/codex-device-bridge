import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { WebSocketServer } from 'ws';
import { authorize, health, rejection, registrations, RelayHub, MAX_CHUNK, SUBPROTOCOL } from './protocol.mjs';

/** One instance only. Both endpoints connect here; no cross-instance routing. */
export function createRelay({ source, tls = null, clock = Date.now, observe = () => {} }) {
  registrations(source); // {} deliberately enables only the public health check.
  const groups = new Map();
  const server = (tls ? https : http).createServer(tls || {}, async (req, res) => {
    const response = req.method === 'GET' && ['/', '/healthz'].includes(req.url) ? health() : rejection();
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(await response.text());
  });
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_CHUNK + 4096,
    perMessageDeflate: false, handleProtocols: protocols => protocols.has(SUBPROTOCOL) ? SUBPROTOCOL : false });
  let pending = 0;
  server.on('upgrade', async (req, socket, head) => {
    observe('upgrade');
    socket.setTimeout(8000, () => socket.destroy());
    if (pending >= 32) { socket.destroy(); return; }
    pending++;
    try {
      const request = new Request('https://relay.invalid' + req.url, { method: req.method, headers: req.headers });
      const identity = await authorize(request, source, Math.floor(clock() / 1000));
      observe('admitted:' + identity.role);
      if (socket.destroyed) return;
      let group = groups.get(identity.hostId);
      if (!group) {
        const peers = new Set();
        group = { peers, hub: new RelayHub(() => [...peers], {}, clock) };
        groups.set(identity.hostId, group);
      }
      const adapter = { data: null, get() { return this.data; }, set(v) { this.data = v; },
        send(v) { this.ws.send(v); }, close(code, reason) { this.ws.close(code, reason); },
        get bufferedAmount() { return this.ws?.bufferedAmount || 0; } };
      group.hub.open(adapter, identity);
      wss.handleUpgrade(req, socket, head, ws => {
        adapter.ws = ws;
        socket.setTimeout(0);
        group.peers.add(adapter);
        ws.on('message', (data, binary) => { observe('message:' + identity.role + ':' + (binary ? 'binary' : 'hello')); group.hub.message(adapter, binary ? data : data.toString('utf8')); });
        const closed = () => { observe('closed:' + identity.role); group.hub.close(adapter); group.peers.delete(adapter); };
        ws.on('close', closed);
        ws.on('error', closed);
      });
    } catch (error) {
      observe('rejected:' + (error?.status || 503));
      const status = error?.status || 503;
      if (!socket.destroyed) socket.end(`HTTP/1.1 ${status} Rejected\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    } finally { pending--; }
  });
  const timer = setInterval(() => { for (const group of groups.values()) group.hub.expire(); }, 1000);
  timer.unref();
  return { server, close: async () => {
    clearInterval(timer);
    for (const group of groups.values()) for (const p of group.peers) p.ws.terminate();
    await new Promise(resolve => wss.close(resolve));
    await new Promise(resolve => server.close(resolve));
  } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT || 10000);
  const bind = process.env.RELAY_BIND || '127.0.0.1';
  const tls = process.env.RELAY_TLS_CERT && process.env.RELAY_TLS_KEY
    ? { cert: fs.readFileSync(process.env.RELAY_TLS_CERT), key: fs.readFileSync(process.env.RELAY_TLS_KEY) } : null;
  const relay = createRelay({ source: process.env.RELAY_REGISTRATIONS_JSON || '{}', tls });
  relay.server.listen(port, bind, () => console.log(`device-bridge-relay listening on ${bind}:${port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => relay.close().then(() => process.exit(0)));
}
