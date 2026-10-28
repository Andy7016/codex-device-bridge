// Read-only discovery of the desktop-owned App Tools pipe inherited by a task.
// Never enumerate pipe names, impersonate callers, or expose this endpoint.
import net from 'node:net';
import { mkdirSync, writeFileSync } from 'node:fs';

const endpoint = process.env.CODEX_APP_TOOLS_PIPE_PATH;
if (!endpoint) throw new Error('Desktop endpoint is not present in this process context');
const MAX_FRAME = 8 * 1024 * 1024;
let buffer = Buffer.alloc(0);
let settled = false;
const socket = net.createConnection(endpoint);
const finish = (result, failed = false) => {
  if (settled) return;
  settled = true;
  clearTimeout(timer);
  socket.destroy();
  console.log(JSON.stringify(result, null, 2));
  process.exitCode = failed ? 1 : 0;
};
const timer = setTimeout(() => finish({ ok: false, error: 'timeout' }, true), 8000);
socket.on('connect', () => {
  const body = Buffer.from(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list', params: { threadStartKind: 'all' } }));
  const header = Buffer.alloc(4);
  header.writeUInt32LE(body.length);
  socket.write(Buffer.concat([header, body]));
});
socket.on('data', chunk => {
  if (settled) return;
  if (buffer.length + chunk.length > MAX_FRAME + 4) return finish({ ok: false, error: 'oversized_response' }, true);
  buffer = Buffer.concat([buffer, chunk]);
  if (buffer.length < 4) return;
  const size = buffer.readUInt32LE();
  if (size > MAX_FRAME) return finish({ ok: false, error: 'oversized_response' }, true);
  if (buffer.length < size + 4) return;
  let reply;
  try { reply = JSON.parse(buffer.subarray(4, 4 + size).toString('utf8')); }
  catch { return finish({ ok: false, error: 'invalid_json' }, true); }
  if (reply.id !== 1) return finish({ ok: false, error: 'unexpected_response' }, true);
  if (reply.error) return finish({ ok: false, errorCode: reply.error.code }, true);
  const tools = reply.result?.tools;
  if (!Array.isArray(tools)) return finish({ ok: false, error: 'invalid_catalog' }, true);
  mkdirSync('.local', { recursive: true });
  writeFileSync('.local/app-tools-catalog.json', JSON.stringify(reply.result, null, 2));
  finish({ ok: true, toolCount: tools.length, namespaces: [...new Set(tools.map(t => t.namespace))],
    relevant: tools.filter(t => /thread|approval|permission|project|model/i.test(t.name)).map(t => ({ namespace: t.namespace, name: t.name })) });
});
socket.on('error', error => finish({ ok: false, error: error.code || 'connection_error' }, true));
socket.on('close', () => { if (!settled) finish({ ok: false, error: 'closed_before_response' }, true); });
