// Synthetic stdio peer for NodeDesktopRuntime tests. Never connects to a desktop.
import { createInterface } from 'node:readline';
const lines = createInterface({ input: process.stdin });
const iterator = lines[Symbol.asyncIterator]();
const request = JSON.parse((await iterator.next()).value);
if (process.env.CODEX_THREAD_ID || process.env.CODEX_APP_TOOLS_PIPE_PATH || process.env.NODE_OPTIONS) throw new Error('unexpected inherited identity');
if (request.action === 'send') {
  console.log(JSON.stringify({ ready: true, targetId: request.targetId, messageId: request.messageId, executionId: request.executionId }));
  if ((await iterator.next()).value !== '{"commit":true}') throw new Error('no handoff permit');
}
console.log(JSON.stringify({ ok: true, observation: { observed: true, targetId: request.targetId,
  messageId: request.messageId, turnId: 'synthetic-turn', turnStatus: 'completed' } }));
lines.close(); process.stdin.destroy();
