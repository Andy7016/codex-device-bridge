import assert from 'node:assert/strict';
import test from 'node:test';
import { projectHistory } from './history.mjs';

function snapshot(count = 4, content = 'synthetic') {
  return { revision: 1, conversationState: { id: 'synthetic-thread', threadRuntimeStatus: { type: 'idle' },
    turnHistory: { history: { isComplete: true, entitiesByKey: Object.fromEntries(Array.from({ length: count }, (_, i) => [i, {
      turnId: `turn-${i}`, turnStartedAtMs: i, status: 'completed', params: { input: [] },
      items: [{ id: `user-${i}`, type: 'userMessage', content: [{ type: 'text', text: content }] },
        { id: `assistant-${i}`, type: 'agentMessage', text: `reply-${i}` }],
    }])) } } } };
}

test('recent history returns original message roles and bounded older pages', () => {
  const s = snapshot(); const page = projectHistory(s, { limit: 3 });
  assert.equal(page.messages.length, 3); assert.equal(page.hasMore, true);
  const older = projectHistory(s, { limit: 3, before: page.nextBefore });
  assert.equal(older.messages.at(-1).id, 'turn-2:user-2');
  assert.equal(page.messages.at(-1).role, 'assistant');
});
test('cursor cannot cross targets or survive a changed message sequence', () => {
  const s = snapshot(); const cursor = projectHistory(s, { limit: 2 }).nextBefore;
  assert.throws(() => projectHistory(snapshot(5), { before: cursor }), { code: 'history_changed' });
  s.conversationState.id = 'another-target';
  assert.throws(() => projectHistory(s, { before: cursor }), { code: 'history_changed' });
});
test('UTF-8 long messages are visibly bounded and stay inside transport frame', () => {
  const page = projectHistory(snapshot(8, '长文本'.repeat(4000)), { limit: 8 });
  assert.ok(Buffer.byteLength(JSON.stringify({ ok: true, history: page })) < 8192);
  assert.ok(page.messages.some(m => m.text.includes('内容已截断')));
});
test('same-ID content and status edits invalidate the history cursor', () => {
  for (const mutate of [state => { state.items[0].content[0].text = 'edited'; }, state => { state.status = 'inProgress'; }]) {
    const s = snapshot();
    const cursor = projectHistory(s, { limit: 1 }).nextBefore;
    mutate(s.conversationState.turnHistory.history.entitiesByKey[0]);
    assert.throws(() => projectHistory(s, { before: cursor }), { code: 'history_changed' });
  }
});
test('invalid pages and unsupported history fail without fabricated transcript', () => {
  for (const limit of [0, 9, true]) assert.throws(() => projectHistory(snapshot(), { limit }), { code: 'invalid_history_page' });
  assert.throws(() => projectHistory({}), { code: 'unsupported_history_shape' });
});
