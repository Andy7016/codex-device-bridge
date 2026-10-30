// Isolated fixture only. Never logs headers, tokens, payloads or real identities.
import fs from 'node:fs';
import { createRelay } from '../render.mjs';
const relay = createRelay({ source: process.env.RELAY_REGISTRATIONS_JSON,
  tls: { cert: fs.readFileSync(process.env.RELAY_TLS_CERT), key: fs.readFileSync(process.env.RELAY_TLS_KEY) },
  observe: event => console.log(Date.now(), event) });
relay.server.listen(Number(process.env.PORT), '127.0.0.1', () => console.log('fixture_ready'));
relay.server.on('connection', socket => console.log(Date.now(), 'tcp', socket.remotePort));
relay.server.on('secureConnection', socket => { console.log(Date.now(), 'tls', socket.remotePort); socket.on('data', data => console.log(Date.now(), 'input_bytes', data.length)); });
relay.server.on('tlsClientError', () => console.log(Date.now(), 'tls_error'));
setTimeout(() => relay.close().then(() => process.exit(24)), 180000).unref();
