import assert from 'node:assert/strict';
import test from 'node:test';
import { observeMessage } from './observation.mjs';

const original = { params: { threadId: 'synthetic-thread', clientUserMessageId: 'synthetic-message',
  input: [{ type: 'text', text: 'synthetic text' }] }, turnId: 'synthetic-turn', status: 'completed' };
const snapshot = turns => ({ revision: 3, conversationState: { id: 'synthetic-thread',
  turnHistory: { history: { isComplete: true, entitiesByKey: Object.fromEntries(turns.map((t, i) => [i, t])) } } } });
const observe = s => observeMessage(s, 'synthetic-thread', 'synthetic-message', 'synthetic text');

test('positive observation binds original target, persistent message identity and exact input', () => {
  assert.deepEqual(observe(snapshot([original])), { observed: true, targetId: 'synthetic-thread',
    messageId: 'synthetic-message', turnId: 'synthetic-turn', turnStatus: 'completed', revision: 3, evidence: 'runtime-message-id' });
});
test('same text with another identity is not delivery evidence, including incomplete history', () => {
  const other = structuredClone(original); other.params.clientUserMessageId = 'another-message';
  const s = snapshot([other]); s.conversationState.turnHistory.history.isComplete = false;
  assert.equal(observe(s).observed, false);
});
test('foreign target, changed input and duplicate correlated turns fail closed', () => {
  const s = snapshot([original]); s.conversationState.id = 'another-thread';
  assert.throws(() => observe(s), { code: 'invalid_observation_target' });
  const changed = structuredClone(original); changed.params.input[0].text = 'changed';
  assert.throws(() => observe(snapshot([changed])), { code: 'correlation_content_mismatch' });
  const duplicate = structuredClone(original); duplicate.turnId = 'another-turn';
  assert.throws(() => observe(snapshot([original, duplicate])), { code: 'duplicate_correlated_turns' });
});
test('optimistic entries without a server turn and unsupported history never establish delivery', () => {
  const pending = structuredClone(original); pending.turnId = null;
  assert.equal(observe(snapshot([pending])).observed, false);
  assert.throws(() => observe({ revision: 1, conversationState: { id: 'synthetic-thread' } }), { code: 'unsupported_history_shape' });
});
