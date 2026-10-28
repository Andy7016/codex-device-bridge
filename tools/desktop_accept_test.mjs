// One-shot acceptance of a previously reviewed synthetic print command only.
import { readFileSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { CoordinationClient } from '../desktop/coordination.mjs';
import { DesktopAdapter } from '../desktop/adapter.mjs';
import { ActivationBarrier } from '../desktop/activation.mjs';
import { NativePipeTransport } from '../desktop/transport.mjs';

const policy = JSON.parse(readFileSync('.local/desktop-target-policy.json', 'utf8'));
const context = JSON.parse(readFileSync('.local/desktop-caller-context.json', 'utf8'));
const reviewed = JSON.parse(readFileSync('.local/reviewed-print-action.json', 'utf8'));
const prompt = '这是单次允许审批联调。请只尝试一次执行以下命令：python -c "print(\'DEVICE_BRIDGE_APPROVAL_V1\')"。不要改写文件，不要联网，不要运行其他命令。需要审批时正常等待；被拒绝时不要重试；成功时报告打印的标记。';
if (policy.targets.codex.testFixture !== true || !policy.targets.codex.allowedActionDigests?.includes(reviewed.actionDigest)) throw new Error('reviewed_action_not_provisioned');
const client = await CoordinationClient.open({ policy });
const report = { fixtureOnly: true, decision: 'accept', persistentRuleGranted: false, claimedOwnership: false };
try {
  const before = await client.snapshot('codex');
  if (before.conversationState.latestThreadSettings?.approvalPolicy !== 'untrusted' ||
      before.conversationState.latestThreadSettings?.approvalsReviewer !== 'user' ||
      before.conversationState.threadRuntimeStatus?.type !== 'idle') throw new Error('test_preconditions_changed');
  const adapter = new DesktopAdapter({ transport: new NativePipeTransport(), context, policy,
    activation: new ActivationBarrier('.local/desktop-attempts') });
  await adapter.send('codex', prompt, 'approval-accept-v1');
  report.testPromptAccepted = true;
  let observed;
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    const approvals = await client.commandApprovals('codex');
    observed = approvals.find(a => a.command?.includes('DEVICE_BRIDGE_APPROVAL_V1'));
    if (observed) break;
    await delay(1000);
  }
  if (!observed) throw new Error('no_matching_command_approval_observed');
  report.genuineCommandApprovalObserved = true;
  if (observed.actionDigest !== reviewed.actionDigest) {
    await client.decline('codex', observed.ticket);
    report.unreviewedActionCancelled = true;
    throw new Error('action_digest_does_not_match_reviewed_print');
  }
  const decision = await client.acceptOnce('codex', observed.ticket);
  report.decisionSubmitted = decision.decisionSubmitted;
  const after = await client.snapshot('codex');
  report.pendingCommandApprovalsAfter = after.conversationState.requests?.filter(r => r.method === 'item/commandExecution/requestApproval' && r.completed !== true).length ?? 0;
} catch (error) {
  report.error = error.code || error.message;
  if (error.nativeDiagnostic) writeFileSync('.local/desktop-last-error.json', JSON.stringify(error.nativeDiagnostic, null, 2));
  process.exitCode = 1;
} finally {
  client.close();
  writeFileSync('.local/live-accept-test-report.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}
