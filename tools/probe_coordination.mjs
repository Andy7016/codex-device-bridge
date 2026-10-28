// Connect-only, read-only discovery. Never starts a router or claims a thread.
import net from 'node:net';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { encodeFrame } from '../desktop/transport.mjs';

const policy = JSON.parse(readFileSync('.local/desktop-target-policy.json', 'utf8'));
const target = policy.targets.codex;
if (target?.kind !== 'codex' || !target.rights.includes('read')) throw new Error('missing_test_target');
const socket = net.createConnection('\\\\.\\pipe\\codex-ipc');
const pending = new Map();
const checks = [];
let buffer = Buffer.alloc(0), clientId = 'initializing-client';
let expectedOwner;
let snapshot;
let snapshotArrived;
const snapshotReady = new Promise(resolve => { snapshotArrived = resolve; });
let finished = false;
const fail = code => {
  if (finished) return;
  finished = true;
  for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(new Error(code)); }
  pending.clear();
  socket.destroy();
};
function request(method, params, targetClientId) {
  // This probe cannot transmit follower mutations, approval decisions or claims.
  if (!['initialize', 'thread-owner-discovery', 'thread-follower-read-model-settings', 'thread-follower-load-complete-history'].includes(method)) throw new Error('read_only_probe');
  const requestId = randomUUID();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(requestId); reject(new Error('request_timeout')); }, 4000);
    pending.set(requestId, { resolve, reject, timer, method, targetClientId });
    socket.write(encodeFrame({ type: 'request', method, requestId, sourceClientId: clientId,
      version: method === 'initialize' ? 0 : 1, params, targetClientId, timeoutMs: 3500 }));
  });
}
socket.on('data', chunk => {
  try {
    if (buffer.length + chunk.length > 8 * 1024 * 1024 + 4) throw new Error('buffer_limit');
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 4) {
      const size = buffer.readUInt32LE();
      if (!size || size > 8 * 1024 * 1024) throw new Error('frame_limit');
      if (buffer.length < size + 4) break;
      const msg = JSON.parse(buffer.subarray(4, 4 + size).toString('utf8'));
      buffer = buffer.subarray(4 + size);
      if (msg.type === 'client-discovery-request') {
        socket.write(encodeFrame({ type: 'client-discovery-response', requestId: msg.requestId, response: { canHandle: false } }));
      } else if (msg.type === 'response') {
        const entry = pending.get(msg.requestId);
        if (!entry) continue;
        clearTimeout(entry.timer); pending.delete(msg.requestId);
        if (msg.resultType !== 'success') entry.reject(new Error('router_request_rejected'));
        else if (msg.method !== entry.method || entry.targetClientId && msg.handledByClientId !== entry.targetClientId) entry.reject(new Error('response_identity_mismatch'));
        else entry.resolve(msg);
      } else if (msg.type === 'broadcast' && msg.method === 'thread-stream-state-changed' &&
                 msg.version === 11 && msg.sourceClientId === expectedOwner &&
                 msg.params?.hostId === 'local' && msg.params?.conversationId === target.threadId &&
                 msg.params?.change?.type === 'snapshot') {
        snapshot = msg.params.change;
        snapshotArrived();
      }
      // All unsolicited broadcasts, including unrelated conversation data, are
      // discarded immediately and are never logged or persisted.
    }
  } catch { fail('invalid_response'); }
});
socket.on('error', () => fail('connection_error'));
socket.on('close', () => fail('connection_closed'));

const connectTimeout = setTimeout(() => fail('connection_timeout'), 4000);
try {
  await new Promise((resolve, reject) => {
    socket.once('connect', resolve);
    socket.once('error', () => reject(new Error('connection_error')));
    socket.once('close', () => reject(new Error('connection_closed')));
  });
  clearTimeout(connectTimeout);
  const initialized = await request('initialize', { clientType: 'codex-device-bridge' });
  clientId = initialized.result.clientId;
  if (typeof clientId !== 'string' || !clientId) throw new Error('missing_client_identity');
  checks.push({ method: 'initialize', ok: true, clientType: 'codex-device-bridge' });
  const owner = await request('thread-owner-discovery', { hostId: 'local', conversationId: target.threadId });
  expectedOwner = owner.handledByClientId;
  checks.push({ method: 'thread-owner-discovery', ok: true, ownerFound: Boolean(owner.handledByClientId) });
  const settings = await request('thread-follower-read-model-settings', { conversationId: target.threadId }, owner.handledByClientId);
  checks.push({ method: 'thread-follower-read-model-settings', ok: true, resultKeys: Object.keys(settings.result ?? {}) });
  socket.write(encodeFrame({ type: 'broadcast', method: 'thread-stream-following-changed',
    sourceClientId: clientId, version: 1, targetClientIds: [expectedOwner],
    params: { hostId: 'local', conversationId: target.threadId, following: true } }));
  await request('thread-follower-load-complete-history', { conversationId: target.threadId }, expectedOwner);
  let snapshotTimeout;
  try { await Promise.race([snapshotReady, new Promise((_, reject) => {
    snapshotTimeout = setTimeout(() => reject(new Error('snapshot_timeout')), 4000);
  })]); } finally { clearTimeout(snapshotTimeout); }
  writeFileSync('.local/coordination-test-snapshot.json', JSON.stringify(snapshot, null, 2));
  checks.push({ method: 'thread-stream-state-changed', ok: true, snapshotRevision: snapshot.revision,
    stateKeys: Object.keys(snapshot.conversationState ?? {}) });
} catch (error) {
  checks.push({ ok: false, error: error.message });
  process.exitCode = 1;
} finally {
  clearTimeout(connectTimeout);
  fail('probe_finished');
  const report = { readOnly: true, startedRouter: false, claimedOwnership: false, checks };
  writeFileSync('.local/coordination-probe.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}
