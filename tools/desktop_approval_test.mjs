// User-authorized dedicated fixture only. Tightens that fixture, then declines
// one genuine pending print-command approval. Never grants execution authority.
import { readFileSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { CoordinationClient } from '../desktop/coordination.mjs';
import { DesktopAdapter } from '../desktop/adapter.mjs';
import { ActivationBarrier } from '../desktop/activation.mjs';
import { NativePipeTransport } from '../desktop/transport.mjs';

const label = 'codex';
const prompt = '这是命令审批联调。请只尝试一次执行以下命令：python -c "print(\'DEVICE_BRIDGE_APPROVAL_V1\')"。不要改写文件，不要联网，不要运行其他命令。需要审批时正常等待；如果被拒绝，不要重试或采用其他工具绕过，直接报告未执行。';
const policy = JSON.parse(readFileSync('.local/desktop-target-policy.json', 'utf8'));
const context = JSON.parse(readFileSync('.local/desktop-caller-context.json', 'utf8'));
const client = await CoordinationClient.open({ policy });
const report = { fixtureOnly: true, decision: 'decline', globalConfigurationChanged: false };
try {
  const before = await client.snapshot(label);
  writeFileSync('.local/approval-test-original-settings.json', JSON.stringify(before.conversationState.latestThreadSettings, null, 2), { flag: 'wx' });
  await client.tightenTestApprovals(label);
  report.testPolicyTightened = true;
  const adapter = new DesktopAdapter({ transport: new NativePipeTransport(), context, policy,
    activation: new ActivationBarrier('.local/desktop-attempts') });
  await adapter.send(label, prompt, 'approval-decline-v1');
  report.testPromptAccepted = true;
  const deadline = Date.now() + 45000;
  let observed;
  while (Date.now() < deadline) {
    const approvals = await client.commandApprovals(label);
    const candidate = approvals.find(a => a.command?.includes('DEVICE_BRIDGE_APPROVAL_V1'));
    if (candidate) { observed = candidate; break; }
    await delay(1000);
  }
  if (!observed) throw new Error('no_matching_command_approval_observed');
  writeFileSync('.local/observed-test-approval.json', JSON.stringify(observed, null, 2), { flag: 'wx' });
  report.genuineCommandApprovalObserved = true;
  const decision = await client.decline(label, observed.ticket);
  report.decision = decision.decision;
  report.decisionSubmitted = true;
  const after = await client.snapshot(label);
  report.pendingCommandApprovalsAfter = after.conversationState.requests?.filter(r => r.method === 'item/commandExecution/requestApproval' && r.completed !== true).length ?? 0;
  const commandStates = [];
  function collect(value) {
    if (!value || typeof value !== 'object') return;
    if (value.type === 'commandExecution') commandStates.push(value.status);
    for (const child of Object.values(value)) if (typeof child === 'object') collect(child);
  }
  collect(after.conversationState);
  report.commandStates = [...new Set(commandStates)];
} catch (error) {
  report.error = error.code || error.message;
  if (error.nativeDiagnostic) writeFileSync('.local/desktop-last-error.json', JSON.stringify(error.nativeDiagnostic, null, 2));
  process.exitCode = 1;
} finally {
  client.close();
  writeFileSync('.local/live-approval-test-report.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}
