// Private stdio child of the trusted recovery host. No listener or agent context.
import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { CoordinationClient } from './coordination.mjs';
import { ActivationBarrier } from './activation.mjs';
import { observeMessage } from './observation.mjs';

delete process.env.CODEX_THREAD_ID;
delete process.env.CODEX_APP_TOOLS_PIPE_PATH;
const [policyPath, attemptsPath] = process.argv.slice(2);
const policy = JSON.parse(readFileSync(policyPath, 'utf8'));
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
const iterator = lines[Symbol.asyncIterator]();
const output = value => process.stdout.write(JSON.stringify(value) + '\n');
let client;
try {
  const line = (await iterator.next()).value;
  if (typeof line !== 'string' || Buffer.byteLength(line) > 8192) throw new Error('invalid_worker_frame');
  const request = JSON.parse(line);
  if (!['send', 'inspect'].includes(request.action) || !Object.hasOwn(policy.targets ?? {}, request.label)) throw new Error('unsupported_worker_action');
  const target = policy.targets[request.label];
  if (target.kind !== 'codex' || target.hostId !== 'local' || target.threadId !== request.targetId ||
      !target.rights?.includes('read') || !target.allowedMessages?.includes(request.text)) throw new Error('worker_target_not_authorized');
  client = await CoordinationClient.open({ policy });
  if (request.action === 'send') {
    await client.sendText(request.label, request.text, request.executionId, new ActivationBarrier(attemptsPath), {
      clientUserMessageId: request.messageId,
      beforeDispatch: async () => {
        output({ ready: true, targetId: request.targetId, messageId: request.messageId, executionId: request.executionId });
        const next = (await iterator.next()).value;
        if (next !== '{"commit":true}') throw new Error('handoff_not_committed');
      },
    });
  }
  const snapshot = await client.snapshot(request.label);
  const observation = observeMessage(snapshot, request.targetId, request.messageId, request.text);
  output({ ok: true, observation });
} catch (error) {
  output({ ok: false, error: error.code || 'desktop_worker_failed', deliveryUnknown: true });
  process.exitCode = 1;
} finally {
  client?.close(); lines.close(); process.stdin.destroy();
}
