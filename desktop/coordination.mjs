// Version-pinned Windows owner/follower integration. This client only connects
// to an existing router, identifies itself honestly and never claims ownership.
import net from 'node:net';
import { createHash, randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { encodeFrame, TransportFailure } from './transport.mjs';
import { CapabilityError } from './adapter.mjs';

const VERSIONS = Object.freeze({ initialize: 0, 'thread-owner-discovery': 1,
  'thread-follower-start-turn': 2,
  'thread-follower-read-model-settings': 1, 'thread-follower-load-complete-history': 1,
  'thread-follower-update-thread-settings': 2, 'thread-follower-command-approval-decision': 1 });

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]));
  return value;
}
const digest = value => createHash('sha256').update(JSON.stringify(stable(value))).digest('hex');
export const approvalActionDigest = request => digest({ command: request.params?.command,
  cwd: request.params?.cwd, environmentId: request.params?.environmentId,
  commandActions: request.params?.commandActions, additionalPermissions: request.params?.additionalPermissions,
  networkApprovalContext: request.params?.networkApprovalContext,
  proposedExecpolicyAmendment: request.params?.proposedExecpolicyAmendment });

function denialDecision(request) {
  const decisions = request.params?.availableDecisions;
  if (!Array.isArray(decisions)) throw new CapabilityError('explicit_decisions_required');
  if (decisions.includes('decline')) return 'decline';
  if (decisions.includes('cancel')) return 'cancel';
  throw new CapabilityError('safe_denial_not_available');
}

export class CoordinationClient {
  constructor({ policy, connect = endpoint => net.createConnection(endpoint), timeoutMs = 5000 }) {
    this.policy = structuredClone(policy);
    this.socket = connect('\\\\.\\pipe\\codex-ipc');
    this.timeoutMs = timeoutMs;
    this.id = 'initializing-client';
    this.pending = new Map();
    this.owners = new Map();
    this.snapshots = new Map();
    this.tickets = new Map();
    this.decisionsInFlight = new Set();
    this.attemptedDecisions = new Set();
    this.buffer = Buffer.alloc(0);
    this.closed = false;
    this.socket.on('data', chunk => this.#data(chunk));
    this.socket.on('error', () => this.close('connection_error'));
    this.socket.on('close', () => this.close('connection_closed'));
  }
  static async open(options) {
    const client = new CoordinationClient(options);
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new TransportFailure('connect_timeout')), client.timeoutMs);
        const done = fn => { clearTimeout(timer); fn(); };
        client.socket.once('connect', () => done(resolve));
        client.socket.once('error', () => done(() => reject(new TransportFailure('connection_error'))));
        client.socket.once('close', () => done(() => reject(new TransportFailure('connection_closed'))));
      });
      const reply = await client.#request('initialize', { clientType: 'codex-device-bridge' });
      if (typeof reply.result?.clientId !== 'string' || !reply.result.clientId) throw new TransportFailure('invalid_client_identity');
      client.id = reply.result.clientId;
      return client;
    } catch (error) { client.close(); throw error; }
  }
  #target(label, right) {
    const targets = this.policy.targets ?? {};
    if (!Object.hasOwn(targets, label)) throw new CapabilityError('target_not_authorized');
    const target = targets[label];
    if (target.kind !== 'codex' || target.hostId !== 'local' || !target.rights?.includes(right)) throw new CapabilityError('operation_not_authorized');
    return target;
  }
  #request(method, params, owner) {
    if (!Object.hasOwn(VERSIONS, method) || this.closed) return Promise.reject(new CapabilityError('coordination_unavailable'));
    if (this.pending.size >= 16) return Promise.reject(new CapabilityError('coordination_busy'));
    const requestId = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new TransportFailure('coordination_timeout', true));
      }, this.timeoutMs);
      this.pending.set(requestId, { resolve, reject, timer, method, owner });
      this.socket.write(encodeFrame({ type: 'request', requestId, sourceClientId: this.id,
        method, params, version: VERSIONS[method], targetClientId: owner, timeoutMs: this.timeoutMs-200 }));
    });
  }
  #data(chunk) {
    try {
      if (this.buffer.length + chunk.length > 8 * 1024 * 1024 + 4) throw new Error('frame_limit');
      this.buffer = Buffer.concat([this.buffer, chunk]);
      while (this.buffer.length >= 4) {
        const size = this.buffer.readUInt32LE();
        if (!size || size > 8 * 1024 * 1024) throw new Error('frame_limit');
        if (this.buffer.length < size+4) break;
        const msg = JSON.parse(this.buffer.subarray(4, size+4).toString('utf8'));
        this.buffer = this.buffer.subarray(size+4);
        if (msg.type === 'client-discovery-request') {
          // Refuse all ownership/handler discovery: this is an observing client.
          this.socket.write(encodeFrame({ type: 'client-discovery-response', requestId: msg.requestId, response: { canHandle: false } }));
        } else if (msg.type === 'response') {
          const entry = this.pending.get(msg.requestId);
          if (!entry) continue;
          this.pending.delete(msg.requestId); clearTimeout(entry.timer);
          if (msg.resultType !== 'success') entry.reject(new TransportFailure(
            entry.method === 'thread-owner-discovery' && msg.error === 'no-client-found' ? 'owner_not_found' : 'owner_request_rejected', true));
          else if (msg.method !== entry.method || entry.owner && msg.handledByClientId !== entry.owner) entry.reject(new TransportFailure('owner_identity_mismatch', true));
          else entry.resolve(msg);
        } else if (msg.type === 'broadcast' && msg.method === 'thread-stream-state-changed') {
          // Never retain unrelated conversation broadcasts or accept a snapshot
          // supplied by a client other than the discovered owner.
          for (const [label, owner] of this.owners) {
            const target = this.policy.targets[label];
            if (msg.sourceClientId !== owner || msg.version !== 11 ||
                msg.params?.hostId !== 'local' || msg.params?.conversationId !== target.threadId) continue;
            const change = msg.params.change;
            if (change?.type === 'snapshot' && Number.isSafeInteger(change.revision) &&
                change.conversationState?.id === target.threadId) {
              this.snapshots.set(label, structuredClone(change));
            } else this.snapshots.delete(label); // patches invalidate approval evidence
          }
        }
      }
    } catch { this.close('invalid_coordination_frame'); }
  }
  async discoverOwner(label) {
    const target = this.#target(label, 'read');
    const reply = await this.#request('thread-owner-discovery', { hostId: 'local', conversationId: target.threadId });
    const owner = reply.handledByClientId;
    if (typeof owner !== 'string' || !owner) throw new CapabilityError('owner_unavailable');
    if (this.owners.get(label) !== owner) {
      this.snapshots.delete(label);
      for (const [id, ticket] of this.tickets) if (ticket.label === label) this.tickets.delete(id);
    }
    this.owners.set(label, owner);
    this.socket.write(encodeFrame({ type: 'broadcast', method: 'thread-stream-following-changed',
      sourceClientId: this.id, version: 1, targetClientIds: [owner],
      params: { conversationId: target.threadId, hostId: 'local', following: true } }));
    return owner;
  }
  async probeOwner(label) {
    // Discovery only: a ChatGPT policy entry never gains Codex write capability.
    const targets = this.policy.targets ?? {};
    if (!Object.hasOwn(targets, label)) throw new CapabilityError('target_not_authorized');
    const target = targets[label];
    if (!['codex', 'chatgpt'].includes(target.kind) || target.hostId !== 'local' ||
        !target.rights?.includes('read') || typeof target.threadId !== 'string') throw new CapabilityError('operation_not_authorized');
    try {
      const reply = await this.#request('thread-owner-discovery', { hostId: 'local', conversationId: target.threadId });
      if (typeof reply.handledByClientId !== 'string' || !reply.handledByClientId) throw new CapabilityError('owner_unavailable');
      return { ownerFound: true };
    } catch (error) {
      if (error.code === 'owner_not_found') return { ownerFound: false, reason: 'no-client-found' };
      throw error;
    }
  }
  async snapshot(label) {
    const target = this.#target(label, 'read');
    const owner = await this.discoverOwner(label);
    this.snapshots.delete(label);
    const reply = await this.#request('thread-follower-load-complete-history', { conversationId: target.threadId }, owner);
    const snapshot = this.snapshots.get(label);
    if (!snapshot || !Number.isSafeInteger(reply.result?.revision) || snapshot.revision < reply.result.revision) throw new CapabilityError('fresh_snapshot_unavailable');
    return structuredClone(snapshot);
  }
  async tightenTestApprovals(label) {
    const target = this.#target(label, 'test-approval-policy');
    if (target.testFixture !== true) throw new CapabilityError('test_fixture_required');
    const before = await this.snapshot(label);
    const runtime = before.conversationState.threadRuntimeStatus?.type;
    if (runtime !== 'idle') throw new CapabilityError('test_thread_not_idle');
    const owner = this.owners.get(label);
    const reply = await this.#request('thread-follower-update-thread-settings', { conversationId: target.threadId,
      threadSettings: { approvalPolicy: 'untrusted', approvalsReviewer: 'user' } }, owner);
    if (reply.result?.applied !== true) throw new CapabilityError('test_policy_not_applied');
    const after = await this.snapshot(label);
    if (after.conversationState.latestThreadSettings?.approvalPolicy !== 'untrusted' ||
        after.conversationState.latestThreadSettings?.approvalsReviewer !== 'user') throw new CapabilityError('test_policy_not_verified');
    return { tightened: true };
  }
  async sendText(label, prompt, operationId, activation,
    { clientUserMessageId = randomUUID(), beforeDispatch = async () => {} } = {}) {
    const target = this.#target(label, 'send');
    if (typeof prompt !== 'string' || !prompt || Buffer.byteLength(prompt) > 8192 ||
        !(target.allowedMessages?.includes(prompt) || target.allowFreeText === true && Buffer.byteLength(prompt) <= 4096)) throw new CapabilityError('message_not_authorized');
    if (!activation) throw new CapabilityError('durable_activation_guard_required');
    if (typeof clientUserMessageId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(clientUserMessageId)) throw new CapabilityError('invalid_message_identity');
    const snapshot = await this.snapshot(label);
    if (snapshot.conversationState.threadRuntimeStatus?.type !== 'idle') throw new CapabilityError('thread_not_idle');
    const owner = this.owners.get(label);
    const accepted = activation.begin(operationId, target.threadId, prompt, clientUserMessageId);
    // The trusted host gates this final handoff after all snapshot awaits.
    await beforeDispatch();
    try {
      // Construct text input locally. No model, permission, system instruction,
      // raw context or arbitrary turn settings are accepted from a controller.
      const reply = await this.#request('thread-follower-start-turn', { conversationId: target.threadId,
        turnStart: { request: { threadId: target.threadId,
          input: [{ type: 'text', text: prompt, text_elements: [] }], clientUserMessageId },
          context: { inheritThreadSettings: true } } }, owner);
      accepted();
      return { ownerReplied: true, outcomeShape: typeof reply.result?.result };
    } catch (error) { error.deliveryUnknown = true; throw error; }
  }
  async commandApprovals(label) {
    this.#target(label, 'approval-decline');
    const snapshot = await this.snapshot(label);
    const owner = this.owners.get(label);
    const requests = snapshot.conversationState.requests ?? [];
    const result = [];
    for (const request of requests) {
      if (request.method !== 'item/commandExecution/requestApproval' || request.completed === true ||
          request.params?.threadId !== this.policy.targets[label].threadId || !request.params?.turnId) continue;
      const decisionKey = digest([label, owner, request.id]);
      if (this.decisionsInFlight.has(decisionKey) || this.attemptedDecisions.has(decisionKey)) continue;
      const ticket = randomUUID();
      while (this.tickets.size >= 128) this.tickets.delete(this.tickets.keys().next().value);
      const record = { label, owner, request: structuredClone(request), fingerprint: digest(request),
        actionDigest: approvalActionDigest(request), issuedAt: performance.now() };
      this.tickets.set(ticket, record);
      result.push({ ticket, method: request.method, command: request.params.command ?? null,
        reason: request.params.reason ?? null, actionDigest: record.actionDigest,
        scope: 'single-request', decision: denialDecision(request) });
    }
    return result;
  }
  async decline(label, ticket) {
    return this.#decide(label, ticket, false);
  }
  async acceptOnce(label, ticket) {
    return this.#decide(label, ticket, true);
  }
  async #decide(label, ticket, accept) {
    const target = this.#target(label, accept ? 'approval-accept' : 'approval-decline');
    const record = this.tickets.get(ticket);
    // Tickets are bound to this local client connection, target, actual owner,
    // immutable runtime request and a short lifetime. Never accept raw IDs from a phone.
    if (!record || record.label !== label || performance.now()-record.issuedAt > 60000) throw new CapabilityError('invalid_approval_ticket');
    const decisionKey = digest([label, record.owner, record.request.id]);
    if (this.decisionsInFlight.has(decisionKey) || this.attemptedDecisions.has(decisionKey)) throw new CapabilityError('approval_already_attempted');
    if (this.attemptedDecisions.size >= 1024) throw new CapabilityError('approval_attempt_limit');
    // Claim before the first await, including other tickets for this request.
    // An unresolved decision cannot be submitted again on this connection.
    this.decisionsInFlight.add(decisionKey);
    try {
      const snapshot = await this.snapshot(label);
      if (this.owners.get(label) !== record.owner) throw new CapabilityError('approval_owner_changed');
      const current = snapshot.conversationState.requests?.find(r => r.id === record.request.id && r.method === record.request.method && r.completed !== true);
      if (!current || digest(current) !== record.fingerprint) throw new CapabilityError('approval_no_longer_current');
      let decision = denialDecision(current);
      if (accept) {
        if (!target.allowedActionDigests?.includes(record.actionDigest)) throw new CapabilityError('action_not_authorized_for_accept');
        if (!current.params.availableDecisions?.includes('accept')) throw new CapabilityError('single_accept_not_available');
        decision = 'accept';
      }
      this.attemptedDecisions.add(decisionKey);
      for (const [id, other] of this.tickets) {
        if (other.label === label && other.owner === record.owner && other.request.id === record.request.id) this.tickets.delete(id);
      }
      const reply = await this.#request('thread-follower-command-approval-decision', {
        conversationId: target.threadId, requestId: current.id, decision }, record.owner);
      if (reply.result?.ok !== true) throw new TransportFailure('approval_outcome_unknown', true);
      return { decisionSubmitted: true, decision }; // caller must observe the actual resolution
    } finally { this.decisionsInFlight.delete(decisionKey); }
  }
  close(reason = 'client_closed') {
    if (this.closed) return;
    this.closed = true;
    for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(new TransportFailure(reason, true)); }
    this.pending.clear(); this.snapshots.clear(); this.tickets.clear();
    this.decisionsInFlight.clear(); this.attemptedDecisions.clear();
    this.socket.destroy(); // router removes follower subscriptions on disconnect
  }
}
