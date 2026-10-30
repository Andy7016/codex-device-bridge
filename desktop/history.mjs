import { createHash } from 'node:crypto';
import { CapabilityError } from './adapter.mjs';

function boundedText(text, bytes = 2400) {
  let result = '', used = 0;
  for (const character of String(text ?? '')) {
    const length = Buffer.byteLength(character);
    if (used + length > bytes) return result + '\n…（内容已截断）';
    result += character; used += length;
  }
  return result;
}

export function projectHistory(snapshot, { before = null, limit = 6 } = {}) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 8 ||
      before !== null && (typeof before !== 'string' || before.length > 128)) throw new CapabilityError('invalid_history_page');
  const state = snapshot?.conversationState;
  const history = state?.turnHistory?.history;
  if (!state?.id || !history?.entitiesByKey || !Number.isSafeInteger(snapshot.revision)) throw new CapabilityError('unsupported_history_shape');
  const turns = Object.values(history.entitiesByKey).sort((a, b) => (a.turnStartedAtMs ?? 0) - (b.turnStartedAtMs ?? 0));
  const messages = [];
  for (const turn of turns) {
    const items = Array.isArray(turn.items) ? turn.items : [];
    if (!items.some(item => item.type === 'userMessage')) {
      const input = turn.params?.input?.filter(item => item.type === 'text').map(item => item.text).join('\n');
      if (input) messages.push({ id: `${turn.turnId}:input`, role: 'user', text: boundedText(input), status: turn.status ?? '' });
    }
    for (let index = 0; index < items.length; index++) {
      const item = items[index];
      let role, text;
      if (item.type === 'userMessage') {
        role = 'user'; text = item.content?.filter(c => c.type === 'text').map(c => c.text).join('\n');
      } else if (item.type === 'agentMessage') {
        role = 'assistant'; text = item.text;
      } else if (item.type === 'commandExecution') {
        role = 'tool'; text = `命令执行 · ${item.status ?? '状态未知'}`;
      } else if (item.type === 'functionCallOutput') {
        role = 'tool'; text = `桌面工具消息 · ${item.name ?? 'tool'}`;
      }
      if (text) messages.push({ id: `${turn.turnId}:${item.id ?? index}`, role, text: boundedText(text), status: turn.status ?? '' });
    }
  }
  const generation = createHash('sha256').update(JSON.stringify([state.id, messages])).digest('hex').slice(0, 24);
  let end = messages.length;
  if (before !== null) {
    const match = /^h1\.([a-f0-9]{24})\.(\d+)$/.exec(before);
    if (!match || match[1] !== generation) throw new CapabilityError('history_changed');
    end = Number(match[2]);
    if (!Number.isSafeInteger(end) || end < 0 || end > messages.length) throw new CapabilityError('invalid_history_page');
  }
  let start = Math.max(0, end - limit);
  let page = messages.slice(start, end);
  while (page.length > 1 && Buffer.byteLength(JSON.stringify(page)) > 6200) {
    start++; page = messages.slice(start, end);
  }
  return { messages: page, nextBefore: start > 0 ? `h1.${generation}.${start}` : null, hasMore: start > 0,
    runtimeStatus: state.threadRuntimeStatus?.type ?? 'unknown', historyComplete: history.isComplete === true };
}
