import assert from 'node:assert/strict';
import test from 'node:test';
import { randomUUID } from 'node:crypto';
import { mkdirSync, readdirSync, realpathSync, lstatSync, unlinkSync, rmdirSync } from 'node:fs';
import path from 'node:path';
import { DesktopAdapter } from './adapter.mjs';
import { ActivationBarrier } from './activation.mjs';

const message = 'synthetic hello';
const context = { threadId: 'caller', turnId: 'turn-1', hostId: 'local', callerSource: 'codex', expiresAt: 2000 };
const policy = { targets: { test: { threadId: 'target', hostId: 'local', kind: 'codex', rights: ['read', 'send'], allowedMessages: [message] } } };
const result = value => ({ success: true, contentItems: [{ type: 'inputText', text: JSON.stringify(value) }] });

function fixture(options = {}) {
  const calls = [];
  let executions = 0;
  const transport = { async request(method, params) {
    calls.push({ method, params: structuredClone(params) });
    if (method === 'tools/list') return { tools: ['read_thread', 'send_message_to_thread', 'list_projects', 'approve_thread'].map(name => ({ namespace: 'codex_app', name })) };
    if (params.tool === 'read_thread') return result(params.arguments.threadId === 'caller'
      ? { thread: { id: 'caller' }, turns: [{ id: 'turn-1', status: options.inactive ? 'completed' : 'inProgress' }] }
      : { thread: { id: 'target', kind: 'codex' }, turns: [] });
    if (params.tool === 'send_message_to_thread') {
      executions++;
      if (options.dropReply) throw new Error('simulated lost acknowledgement');
      return result({ threadId: options.wrongTarget ? 'other' : 'target' });
    }
    throw new Error('unexpected call');
  } };
  const activations = [];
  const activation = options.activation ?? { begin(id, target, prompt) {
    activations.push({ id, target, prompt });
    return () => activations.push('accepted');
  } };
  const adapter = new DesktopAdapter({ transport, context: options.context ?? context,
    policy: options.policy ?? policy, environmentThread: options.environmentThread ?? 'caller',
    now: () => 1000, activation: options.noGuard ? undefined : activation });
  return { adapter, calls, activations, executions: () => executions };
}

function scratch(t) {
  const parent = path.resolve('test-results');
  mkdirSync(parent, { recursive: true });
  const dir = path.join(parent, 'activation-' + randomUUID());
  mkdirSync(dir);
  t.after(() => {
    assert.equal(path.dirname(dir), parent);
    const files = readdirSync(dir).map(name => path.join(dir, name));
    for (const file of files) {
      assert.equal(lstatSync(file).isSymbolicLink(), false);
      assert.equal(lstatSync(file).isFile(), true);
      assert.equal(path.dirname(realpathSync(file)), dir);
    }
    for (const file of files) unlinkSync(file);
    rmdirSync(dir);
  });
  return dir;
}

test('read uses the native tool and the genuine originating context', async () => {
  const f = fixture();
  assert.equal((await f.adapter.read('test')).thread.kind, 'codex');
  const call = f.calls.find(c => c.method === 'tools/call');
  assert.equal(call.params.threadId, 'caller');
  assert.equal(call.params.turnId, 'turn-1');
  assert.equal(call.params.arguments.threadId, 'target');
  assert.equal(f.executions(), 0);
});

test('discovery never enables approval control from guessed tool names', async () => {
  const f = fixture();
  const capabilities = await f.adapter.discover();
  assert.equal(capabilities.sendMessage, true);
  assert.equal(capabilities.decideApproval, false);
  assert.throws(() => f.adapter.approve(), { code: 'approval_control_not_exposed' });
  assert.equal(f.calls.length, 1);
});

test('unknown targets, prototype keys and ungranted writes are denied locally', async () => {
  const f = fixture({ policy: { targets: { test: { threadId: 'target', hostId: 'local', kind: 'codex', rights: ['read'] } } } });
  await assert.rejects(f.adapter.read('__proto__'), { code: 'target_not_authorized' });
  await assert.rejects(f.adapter.send('test', message, 'op-1'), { code: 'operation_not_authorized' });
  assert.equal(f.calls.length, 0);
});

test('arbitrary prompts, missing activation guard and expired context fail before send', async () => {
  const prompt = fixture();
  await assert.rejects(prompt.adapter.send('test', 'not authorized', 'op-1'), { code: 'message_not_authorized' });
  const guard = fixture({ noGuard: true });
  await assert.rejects(guard.adapter.send('test', message, 'op-1'), { code: 'durable_activation_guard_required' });
  const expired = fixture({ context: { ...context, expiresAt: 500 } });
  await assert.rejects(expired.adapter.send('test', message, 'op-1'), { code: 'invalid_or_expired_desktop_context' });
  assert.equal(prompt.executions() + guard.executions() + expired.executions(), 0);
});

test('foreign originating task and completed turn cannot authorize send', async () => {
  const foreign = fixture({ environmentThread: 'another-task' });
  await assert.rejects(foreign.adapter.read('test'), { code: 'invalid_or_expired_desktop_context' });
  const inactive = fixture({ inactive: true });
  await assert.rejects(inactive.adapter.send('test', message, 'op-1'), { code: 'originating_turn_not_active' });
  assert.equal(inactive.activations.length, 0);
  assert.equal(inactive.executions(), 0);
});

test('accepted native message is dispatched once after the durable barrier', async () => {
  const f = fixture();
  assert.deepEqual(await f.adapter.send('test', message, 'op-1'), { threadId: 'target' });
  assert.equal(f.executions(), 1);
  assert.equal(f.activations[0].id, 'op-1');
  assert.equal(f.activations[1], 'accepted');
});

test('lost native reply is never automatically resent, including a fresh adapter', async t => {
  const directory = scratch(t);
  const first = fixture({ dropReply: true, activation: new ActivationBarrier(directory) });
  await assert.rejects(first.adapter.send('test', message, 'op-1'), error => error.deliveryUnknown === true);
  assert.equal(first.executions(), 1);
  const restarted = fixture({ activation: new ActivationBarrier(directory) });
  await assert.rejects(restarted.adapter.send('test', message, 'op-1'), { code: 'already_attempted_query_history' });
  assert.equal(restarted.executions(), 0);
});

test('operation ID conflicts and path traversal are rejected', t => {
  const barrier = new ActivationBarrier(scratch(t));
  barrier.begin('op-1', 'target', 'text');
  assert.throws(() => barrier.begin('op-1', 'different-target', 'text'), { code: 'operation_id_conflict' });
  assert.throws(() => barrier.begin('../outside', 'target', 'text'));
});

test('ChatGPT destination omits the Codex execution host routing hint', async () => {
  const chatPolicy = structuredClone(policy);
  chatPolicy.targets.test.kind = 'chatgpt';
  const f = fixture({ policy: chatPolicy });
  await f.adapter.send('test', message, 'chat-op');
  const send = f.calls.find(c => c.params.tool === 'send_message_to_thread');
  assert.equal(Object.hasOwn(send.params.arguments, 'hostId'), false);
});

test('mismatched send destination is unknown and never recorded as accepted', async () => {
  const f = fixture({ wrongTarget: true });
  await assert.rejects(f.adapter.send('test', message, 'op-1'), error => error.code === 'unexpected_target_identity' && error.deliveryUnknown);
  assert.equal(f.activations.includes('accepted'), false);
});
