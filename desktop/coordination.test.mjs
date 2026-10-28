import assert from 'node:assert/strict';
import test from 'node:test';
import net from 'node:net';
import { once } from 'node:events';
import { CoordinationClient, approvalActionDigest } from './coordination.mjs';
import { encodeFrame } from './transport.mjs';

const policy = { targets: { test: { threadId: 'synthetic-thread', kind: 'codex', hostId: 'local', testFixture: true,
  rights: ['read', 'test-approval-policy', 'approval-decline'] } } };

async function fixture(t, options = {}) {
  const calls = [];
  const state = { id: 'synthetic-thread', threadRuntimeStatus: { type: 'idle' },
    latestThreadSettings: { approvalPolicy: 'on-request', approvalsReviewer: 'auto_review', model: 'unchanged' },
    requests: [{ id: 7, method: 'item/commandExecution/requestApproval', params: {
      threadId: 'synthetic-thread', turnId: 'synthetic-turn', command: 'synthetic print only', availableDecisions: ['accept', 'decline'],
    } }] };
  let owner = 'synthetic-owner';
  const sockets = new Set();
  const server = net.createServer(socket => {
    sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket));
    let buffered = Buffer.alloc(0);
    socket.on('data', chunk => {
      buffered = Buffer.concat([buffered, chunk]);
      while (buffered.length >= 4) {
        const length = buffered.readUInt32LE();
        if (buffered.length < length+4) return;
        const msg = JSON.parse(buffered.subarray(4, length+4).toString());
        buffered = buffered.subarray(length+4); calls.push(msg);
        if (msg.type !== 'request') continue;
        let result;
        if (msg.method === 'initialize') result = { clientId: 'synthetic-bridge' };
        if (msg.method === 'thread-owner-discovery') result = { supportsUntrustedAppInput: true };
        if (msg.method === 'thread-follower-load-complete-history') {
          socket.write(encodeFrame({ type: 'broadcast', method: 'thread-stream-state-changed',
            sourceClientId: options.badSource ? 'unrelated-client' : owner, version: options.badVersion ? 10 : 11,
            params: { hostId: 'local', conversationId: 'synthetic-thread',
              change: { type: 'snapshot', revision: 1, conversationState: state } } }));
          result = { revision: 1 };
        }
        if (msg.method === 'thread-follower-update-thread-settings') {
          Object.assign(state.latestThreadSettings, msg.params.threadSettings);
          result = { applied: true };
        }
        if (msg.method === 'thread-follower-command-approval-decision') {
          state.requests = state.requests.filter(r => r.id !== msg.params.requestId);
          result = { ok: true };
          if (options.dropDecisionReply) continue;
        }
        if (msg.method === 'thread-follower-start-turn') result = { result: { accepted: true } };
        socket.write(encodeFrame({ type: 'response', method: msg.method, requestId: msg.requestId,
          resultType: 'success', handledByClientId: msg.method === 'initialize' ? 'synthetic-bridge' : owner, result }));
      }
    });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); });
  const client = await CoordinationClient.open({ policy: options.policy ?? policy, timeoutMs: 300,
    connect: () => net.createConnection({ host: '127.0.0.1', port: server.address().port }) });
  t.after(() => client.close());
  return { client, calls, state, changeOwner: value => { owner = value; } };
}

test('coordination registers its own client type and observes without claiming ownership', async t => {
  const f = await fixture(t);
  assert.equal((await f.client.snapshot('test')).conversationState.id, 'synthetic-thread');
  assert.equal(f.calls.find(c => c.method === 'initialize').params.clientType, 'codex-device-bridge');
  assert.equal(f.calls.some(c => /claim|start-turn|approval-decision/.test(c.method)), false);
});

test('test-only policy update tightens approval and preserves unrelated settings', async t => {
  const f = await fixture(t);
  assert.deepEqual(await f.client.tightenTestApprovals('test'), { tightened: true });
  const update = f.calls.find(c => c.method === 'thread-follower-update-thread-settings');
  assert.deepEqual(update.params.threadSettings, { approvalPolicy: 'untrusted', approvalsReviewer: 'user' });
  assert.equal(f.state.latestThreadSettings.model, 'unchanged');
});

test('current command approval is declined with an opaque one-shot ticket', async t => {
  const f = await fixture(t);
  const [approval] = await f.client.commandApprovals('test');
  assert.equal(approval.decision, 'decline');
  assert.equal(Object.hasOwn(approval, 'requestId'), false);
  assert.deepEqual(await f.client.decline('test', approval.ticket), { decisionSubmitted: true, decision: 'decline' });
  await assert.rejects(f.client.decline('test', approval.ticket), { code: 'invalid_approval_ticket' });
  assert.equal(f.calls.filter(c => c.method === 'thread-follower-command-approval-decision').length, 1);
  assert.equal(f.state.requests.length, 0);
});

test('changed action and vanished requests invalidate cached approval tickets', async t => {
  const f = await fixture(t);
  const [approval] = await f.client.commandApprovals('test');
  f.state.requests[0].params.command = 'different action';
  await assert.rejects(f.client.decline('test', approval.ticket), { code: 'approval_no_longer_current' });
  f.state.requests = [];
  await assert.rejects(f.client.decline('test', approval.ticket), { code: 'approval_no_longer_current' });
  assert.equal(f.calls.some(c => c.method === 'thread-follower-command-approval-decision'), false);
});

test('owner changes invalidate decisions rather than transferring approval authority', async t => {
  const f = await fixture(t);
  const [approval] = await f.client.commandApprovals('test');
  f.changeOwner('new-owner');
  await assert.rejects(f.client.decline('test', approval.ticket), { code: 'approval_owner_changed' });
});

test('unrelated source and incompatible stream version cannot create approval evidence', async t => {
  for (const option of [{ badSource: true }, { badVersion: true }]) {
    const f = await fixture(t, option);
    await assert.rejects(f.client.commandApprovals('test'), { code: 'fresh_snapshot_unavailable' });
  }
});

test('ungranted scopes fail before any thread request is sent', async t => {
  const readOnly = structuredClone(policy); readOnly.targets.test.rights = ['read'];
  const f = await fixture(t, { policy: readOnly });
  await assert.rejects(f.client.commandApprovals('test'), { code: 'operation_not_authorized' });
  await assert.rejects(f.client.tightenTestApprovals('test'), { code: 'operation_not_authorized' });
  assert.equal(f.calls.length, 1);
});

test('lost approval reply consumes the ticket and never retries the decision', async t => {
  const f = await fixture(t, { dropDecisionReply: true });
  const [approval] = await f.client.commandApprovals('test');
  await assert.rejects(f.client.decline('test', approval.ticket), error => error.deliveryUnknown === true);
  await assert.rejects(f.client.decline('test', approval.ticket), { code: 'invalid_approval_ticket' });
  assert.equal(f.calls.filter(c => c.method === 'thread-follower-command-approval-decision').length, 1);
});

test('cancel is used when the actual runtime offers no decline decision', async t => {
  const f = await fixture(t);
  f.state.requests[0].params.availableDecisions = ['accept', 'cancel'];
  const [approval] = await f.client.commandApprovals('test');
  assert.equal(approval.decision, 'cancel');
  assert.equal((await f.client.decline('test', approval.ticket)).decision, 'cancel');
});

test('concurrent decisions through duplicate tickets submit only once', async t => {
  const f = await fixture(t);
  const [first] = await f.client.commandApprovals('test');
  const [second] = await f.client.commandApprovals('test');
  const results = await Promise.allSettled([
    f.client.decline('test', first.ticket), f.client.decline('test', second.ticket),
  ]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(results.find(r => r.status === 'rejected').reason.code, 'approval_already_attempted');
  assert.equal(f.calls.filter(c => c.method === 'thread-follower-command-approval-decision').length, 1);
});

test('single accept requires the exact reviewed action digest and never grants rules', async t => {
  const acceptPolicy = structuredClone(policy);
  acceptPolicy.targets.test.rights.push('approval-accept');
  const initial = await fixture(t);
  const action = approvalActionDigest(initial.state.requests[0]);
  acceptPolicy.targets.test.allowedActionDigests = [action];
  const f = await fixture(t, { policy: acceptPolicy });
  const [approval] = await f.client.commandApprovals('test');
  assert.equal((await f.client.acceptOnce('test', approval.ticket)).decision, 'accept');
  const sent = f.calls.find(c => c.method === 'thread-follower-command-approval-decision');
  assert.equal(sent.params.decision, 'accept');
  assert.equal(typeof sent.params.decision, 'string');
});

test('unreviewed actions and rules-only acceptance are refused', async t => {
  const acceptPolicy = structuredClone(policy);
  acceptPolicy.targets.test.rights.push('approval-accept');
  acceptPolicy.targets.test.allowedActionDigests = [];
  const f = await fixture(t, { policy: acceptPolicy });
  const [approval] = await f.client.commandApprovals('test');
  await assert.rejects(f.client.acceptOnce('test', approval.ticket), { code: 'action_not_authorized_for_accept' });
  assert.equal(f.calls.some(c => c.method === 'thread-follower-command-approval-decision'), false);
  const rulesPolicy = structuredClone(acceptPolicy);
  rulesPolicy.targets.test.allowedActionDigests = [approvalActionDigest(f.state.requests[0])];
  const rules = await fixture(t, { policy: rulesPolicy });
  rules.state.requests[0].params.availableDecisions = [{ acceptWithExecpolicyAmendment: { execpolicy_amendment: ['synthetic'] } }, 'cancel'];
  const [rulesApproval] = await rules.client.commandApprovals('test');
  await assert.rejects(rules.client.acceptOnce('test', rulesApproval.ticket), { code: 'single_accept_not_available' });
  assert.equal(rules.calls.some(c => c.method === 'thread-follower-command-approval-decision'), false);
});

test('independent text send constructs only a user message and inherits existing settings', async t => {
  const sendPolicy = structuredClone(policy);
  sendPolicy.targets.test.rights.push('send');
  sendPolicy.targets.test.allowedMessages = ['synthetic message'];
  const f = await fixture(t, { policy: sendPolicy });
  let began = 0, accepted = 0;
  const activation = { begin() { began++; return () => accepted++; } };
  assert.equal((await f.client.sendText('test', 'synthetic message', 'op-1', activation)).ownerReplied, true);
  const call = f.calls.find(c => c.method === 'thread-follower-start-turn');
  assert.deepEqual(Object.keys(call.params.turnStart.request).sort(), ['clientUserMessageId', 'input', 'threadId']);
  assert.deepEqual(call.params.turnStart.context, { inheritThreadSettings: true });
  assert.equal(call.params.turnStart.request.input[0].type, 'text');
  assert.equal(began, 1); assert.equal(accepted, 1);
});
