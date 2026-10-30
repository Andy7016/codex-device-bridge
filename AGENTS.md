# Project working agreements

## Scope and evidence

- Build the Android session client, Windows host bridge and connection path described in README.md.
- Keep desktop ChatGPT and Codex capability results separate. A second runtime or copied history does not demonstrate control of the original desktop session.
- Consult current official OpenAI documentation through openaiDeveloperDocs before OpenAI integration work. Record desktop/CLI versions and schema provenance in validation results.
- Mark proposed, documented, observed and tested capabilities distinctly. Never mark a test passed without execution evidence.
- Preserve the Kotlin/Compose Android, Python/Tk Windows host and shared WSS relay boundaries. Revalidate desktop integration before extending real write capabilities.

## Data and credentials

- Do not commit credentials, pairing secrets, real conversation contents, personal file listings, runtime databases or raw captures.
- Use synthetic fixtures and isolated test identities. Keep sensitive local results under ignored test-results/ or .local/ and review staged files before each commit.
- Do not edit desktop app databases, authentication files, global configuration or original user conversations to make a capability probe pass. Prefer disposable conversations and supported interfaces.
- Do not expose app-server or unrestricted RPC to public/shared networks. The bridge must enforce authorization; hiding controls in Android is insufficient.

## Security validation

- The user authorized attack testing when needed for connection and recovery validation. Tests must target project-owned isolated test clients, bridge and relay with synthetic data.
- This authorization does not cover third-party infrastructure, production OpenAI/ToDesk endpoints, other users' devices, real account takeover or unrestricted traffic flooding.
- Bound test traffic, execution duration and resources. Record exact targets, limits, cleanup and expected outcomes before execution.
- New external targets, production credentials, public exposure, broad permission changes or destructive actions require separate scope review and any applicable approval.
- Preserve existing sandbox and approval constraints. No automatic privilege expansion on reconnect or fallback.

## Delivery

- Keep each milestone reviewable; run checks appropriate to actual changes.
- Recovery tests must account for duplicate delivery, lost acknowledgements, stale approvals and concurrent desktop/mobile actions.
- The user authorized public repository visibility and separate Android/Windows 0.3.8 pre-releases on 2026-10-30. Public source and artifacts must exclude private runtime state; future visibility changes remain user decisions.
- Read `agent/README.MD` for source maps, verification and publication constraints; it supplements these instructions.
