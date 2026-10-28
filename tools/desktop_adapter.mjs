// Explicit local test harness; no public listener, caller spoofing or credentials.
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { DesktopAdapter } from '../desktop/adapter.mjs';
import { ActivationBarrier } from '../desktop/activation.mjs';
import { NativePipeTransport } from '../desktop/transport.mjs';

const [action, label, operationId] = process.argv.slice(2);
if (!['capabilities', 'read', 'send', 'projects'].includes(action)) throw new Error('unsupported_action');
const context = JSON.parse(readFileSync('.local/desktop-caller-context.json', 'utf8'));
const policy = JSON.parse(readFileSync('.local/desktop-target-policy.json', 'utf8'));
const adapter = new DesktopAdapter({ transport: new NativePipeTransport(), context, policy,
  activation: action === 'send' ? new ActivationBarrier('.local/desktop-attempts') : undefined });
let result;
try {
  if (action === 'capabilities') result = await adapter.discover();
  else if (action === 'read') result = await adapter.read(label);
  else if (action === 'projects') result = await adapter.projects();
  else result = await adapter.send(label, policy.targets[label]?.allowedMessages?.[0], operationId);
  mkdirSync('.local', { recursive: true });
  writeFileSync('.local/desktop-last-result.json', JSON.stringify(result, null, 2));
  // Reports deliberately omit real IDs, titles, credentials and conversation text.
  console.log(JSON.stringify({ ok: true, action, capability: action === 'capabilities' ? result : undefined,
    threadKind: result.thread?.kind, threadStatus: result.thread?.status,
    turnCount: result.turns?.length, projectCount: result.projects?.length,
    resultKeys: Object.keys(result), syntheticAckSeen: result.turns?.some(turn => turn.items?.some(
      item => item.type === 'agentMessage' && item.text?.trim() === 'DEVICE_BRIDGE_ACK_V1')) ?? false }, null, 2));
} catch (error) {
  if (error.nativeDiagnostic) writeFileSync('.local/desktop-last-error.json', JSON.stringify(error.nativeDiagnostic, null, 2));
  console.log(JSON.stringify({ ok: false, action, code: error.code || 'local_adapter_error',
    deliveryUnknown: action === 'send' && error.deliveryUnknown === true }, null, 2));
  process.exitCode = 1;
}
