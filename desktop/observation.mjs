// Pinned original-runtime snapshot schema. Positive ID + input evidence only.
import { CapabilityError } from './adapter.mjs';

export function observeMessage(snapshot, targetId, messageId, text) {
  const state = snapshot?.conversationState;
  if (state?.id !== targetId || !Number.isSafeInteger(snapshot.revision)) throw new CapabilityError('invalid_observation_target');
  const history = state.turnHistory?.history;
  const entities = history?.entitiesByKey;
  if (!entities || typeof entities !== 'object' || Array.isArray(entities)) throw new CapabilityError('unsupported_history_shape');
  const matches = new Map();
  for (const turn of Object.values(entities)) {
    if (turn?.params?.clientUserMessageId !== messageId) continue;
    const params = turn.params;
    if (params.threadId !== targetId || !Array.isArray(params.input) || params.input.length !== 1 ||
        params.input[0]?.type !== 'text' || params.input[0].text !== text) throw new CapabilityError('correlation_content_mismatch');
    if (typeof turn.turnId !== 'string' || !turn.turnId ||
        !['inProgress', 'completed', 'failed', 'interrupted'].includes(turn.status)) continue;
    const prior = matches.get(turn.turnId);
    if (prior && prior.status !== turn.status) throw new CapabilityError('ambiguous_runtime_evidence');
    matches.set(turn.turnId, turn);
  }
  if (matches.size > 1) throw new CapabilityError('duplicate_correlated_turns');
  const turn = matches.values().next().value;
  return turn ? { observed: true, targetId, messageId, turnId: turn.turnId, turnStatus: turn.status,
    revision: snapshot.revision, evidence: 'runtime-message-id' } :
    { observed: false, historyComplete: history.isComplete === true, revision: snapshot.revision };
}
