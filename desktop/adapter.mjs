import { randomUUID } from 'node:crypto';

export class CapabilityError extends Error {
  constructor(code) { super(code); this.code = code; this.deliveryUnknown = false; }
}

export function decodeToolResult(result) {
  if (result?.success === false || result?.isError === true) {
    const error = new CapabilityError('desktop_tool_failed');
    Object.defineProperty(error, 'nativeDiagnostic', { value: result });
    throw error;
  }
  const blocks = result?.contentItems ?? result?.content;
  const text = blocks?.find(b => ['inputText', 'text'].includes(b.type) && typeof b.text === 'string')?.text;
  if (typeof text !== 'string') throw new CapabilityError('unsupported_tool_result');
  try { return JSON.parse(text); }
  catch { throw new CapabilityError('invalid_tool_result'); }
}

export class DesktopAdapter {
  constructor({ transport, context, policy, activation,
                environmentThread = process.env.CODEX_THREAD_ID, now = Date.now }) {
    this.transport = transport;
    this.context = Object.freeze({ ...context });
    this.policy = structuredClone(policy);
    this.activation = activation;
    this.environmentThread = environmentThread;
    this.now = now;
    this.catalog = null;
  }
  assertContext() {
    const c = this.context;
    if (!c.threadId || !c.turnId || c.hostId !== 'local' || c.callerSource !== 'codex' ||
        c.threadId !== this.environmentThread || !Number.isFinite(c.expiresAt) || c.expiresAt <= this.now()) {
      throw new CapabilityError('invalid_or_expired_desktop_context');
    }
  }
  target(label, right) {
    if (!Object.hasOwn(this.policy.targets ?? {}, label)) throw new CapabilityError('target_not_authorized');
    const target = this.policy.targets[label];
    if (target.hostId !== 'local' || !['codex', 'chatgpt'].includes(target.kind) || !target.threadId || !target.rights?.includes(right)) throw new CapabilityError('operation_not_authorized');
    return target;
  }
  async discover() {
    this.assertContext();
    const result = await this.transport.request('tools/list', { threadStartKind: 'all' });
    if (!Array.isArray(result?.tools)) throw new CapabilityError('invalid_tool_catalog');
    this.catalog = new Set(result.tools.filter(t => t.namespace === 'codex_app').map(t => t.name));
    return { readHistory: this.catalog.has('read_thread'), sendMessage: this.catalog.has('send_message_to_thread'),
             listProjects: this.catalog.has('list_projects'), readApprovals: false, decideApproval: false,
             requiresActiveDesktopTask: true };
  }
  async #invoke(tool, args) {
    this.assertContext();
    if (!['read_thread', 'send_message_to_thread', 'list_projects'].includes(tool)) throw new CapabilityError('unsupported_operation');
    if (!this.catalog) await this.discover();
    if (!this.catalog.has(tool)) throw new CapabilityError('desktop_capability_unavailable');
    return decodeToolResult(await this.transport.request('tools/call', {
      ...this.#contextFields(), namespace: 'codex_app', tool, arguments: args, callId: randomUUID(),
    }));
  }
  #contextFields() {
    const { callerSource, threadId, turnId, hostId } = this.context;
    return { callerSource, threadId, turnId, hostId };
  }
  async read(label) {
    const target = this.target(label, 'read');
    const result = await this.#invoke('read_thread', { threadId: target.threadId,
      ...(target.kind === 'codex' ? { hostId: target.hostId } : {}),
      turnLimit: 3, includeOutputs: false, maxOutputCharsPerItem: 1000 });
    if (result.thread?.id !== target.threadId || result.thread?.kind !== target.kind) throw new CapabilityError('unexpected_target_identity');
    return result;
  }
  async projects() {
    if (this.policy.allowProjectCatalog !== true) throw new CapabilityError('operation_not_authorized');
    return this.#invoke('list_projects', {});
  }
  async send(label, prompt, operationId) {
    const target = this.target(label, 'send');
    if (typeof prompt !== 'string' || !prompt || Buffer.byteLength(prompt) > 8192 ||
        !target.allowedMessages?.includes(prompt)) throw new CapabilityError('message_not_authorized');
    if (!this.activation) throw new CapabilityError('durable_activation_guard_required');
    // Bind to a genuine active originating task, not a fabricated background ID.
    let caller;
    try { caller = await this.#invoke('read_thread', { threadId: this.context.threadId,
      turnLimit: 1, includeOutputs: false, maxOutputCharsPerItem: 0 }); }
    catch (error) { error.deliveryUnknown = false; throw error; }
    if (caller.thread?.id !== this.context.threadId ||
        !caller.turns?.some(t => t.id === this.context.turnId && t.status === 'inProgress')) {
      throw new CapabilityError('originating_turn_not_active');
    }
    const accepted = this.activation.begin(operationId, target.threadId, prompt);
    try {
      const reply = await this.#invoke('send_message_to_thread', { threadId: target.threadId,
        ...(target.kind === 'codex' ? { hostId: target.hostId } : {}), prompt });
      if (reply?.threadId !== target.threadId) throw new CapabilityError('unexpected_target_identity');
      accepted();
      return reply;
    } catch (error) { error.deliveryUnknown = true; throw error; }
  }
  approve() { throw new CapabilityError('approval_control_not_exposed'); }
}
