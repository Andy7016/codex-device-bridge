// Read-only owner availability probe for the already-authorized ChatGPT fixture.
import { readFileSync, writeFileSync } from 'node:fs';
import { CoordinationClient } from '../desktop/coordination.mjs';
const policy = JSON.parse(readFileSync('.local/desktop-target-policy.json', 'utf8'));
if (policy.targets?.chatgpt?.kind !== 'chatgpt') throw new Error('chatgpt_fixture_required');
const client = await CoordinationClient.open({ policy });
try {
  const result = { readOnly: true, callerContextUsed: false, ...await client.probeOwner('chatgpt') };
  writeFileSync('.local/chatgpt-owner-probe.json', JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
} finally { client.close(); }
