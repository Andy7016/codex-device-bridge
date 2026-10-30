# Agent development guide — 0.3.8

The human entry point is [../README.md](../README.md). Follow [../AGENTS.md](../AGENTS.md) as repository instructions. This guide does not expand user authorization. The READMEs live in different directories for Windows case-insensitive filesystem compatibility.

## Implementation and source map

| Responsibility | Start here |
| --- | --- |
| Android presentation/recovery | `android/app/src/main/java/io/github/vvcchh0/devicebridge/BridgeViewModel.kt`, `BridgeRepository.kt`, `ui/BridgeApp.kt` |
| Android encrypted state | `data/EncryptedVaultFile.kt`, `ProfileStore.kt`, `HistoryCache.kt` in the same Java package |
| Android transport/pairing | `data/BridgeTransport.kt`, `WebSocketCarrier.kt`, `EngineTlsSocket.kt`, `PairingRepository.kt` |
| Local UI/configuration | `companion/app.py`, `startup.py`, `configuration.py` |
| Authenticated bounded dispatch | `companion/host.py`, `pairing.py`, `wire.py`, `websocket_transport.py` |
| Durable operations/authorization | `prototype/desktop.py`, `journal.py`, `lease.py`, `child_job.py` |
| Stored history/full text | `prototype/stored_history.py` |
| Original-runtime follower | `desktop/runtime_worker.mjs`, `coordination.mjs`, `activation.mjs`, `observation.mjs` |
| Relay | `relay/protocol.mjs`, `cloudflare.mjs`, `render.mjs`, `wrangler.jsonc` |
| Packaging | `tools/build_companion.ps1`, `package_companion.py`, Android Gradle wrapper |

Android is Kotlin/Compose, min API 26 / target 35 / compile 37.0, versionName 0.3.8 / versionCode 8. Companion is Python/Tk with DPAPI and private stdio workers. Relay package/health version remains 0.3.0.

The public business API has seven methods: `catalog`, `history`, `message`, `submit`, `status`, `reconcile`, `sync`. Network approvals, settings, files and ChatGPT are disabled. Original-runtime Codex control is a version-specific follower integration; read-only app-server history is a separate adapter. See [API](../docs/review/API.md).

## Invariants

1. Certificate identity selects the device. JSON cannot supply identity, arbitrary runtime IDs, RPC, paths, shell commands or caller context.
2. Check host policy AND device/session grants, including before result release. Never revive revoked identities when importing settings, granting rights or reconnecting.
3. Persist operation and immutable target/message/execution binding before dispatch. Possibly delivered operations remain `unknown`; never replay or replace their IDs during recovery.
4. Cache/stored history cannot establish send readiness, delivery or pending approval freshness. Bind fragments/cache to identity, target and content revision.
5. Preserve frame/deadline/queue limits, Job Object cancellation and hidden workers on Windows.
6. Do not modify desktop databases, global settings, authentication, installed bundles or original conversations to make tests pass.
7. Use synthetic fixtures. Runtime configs, pairings, keys, captured history, reports and artifacts stay in ignored private directories.

## Verification

Reuse a project virtual environment; from repository root:

```powershell
python -m unittest discover -s tests -q
node --test desktop/*.test.mjs
```

Relay (owned loopback fixtures):

```powershell
cd relay
npm ci
npm test
npm run check:cloudflare
```

The dry run is local; do not replace it with a deployment. Python Cloudflare integration is opt-in with `WSS_TEST_BACKEND=cloudflare` and needs local workerd; default WSS fixtures use Node. Run heavy builds and device jobs sequentially.

Android:

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

Device tests require an explicitly selected owned device and generated synthetic assets. Never distribute AndroidTest APKs. `tools/integrated_desktop_test.py`, `desktop_approval_test.mjs`, `desktop_accept_test.mjs` and live-send probes affect real sessions; they are not routine tests.

## Reviewing and publishing

Read [workflows](../docs/review/WORKFLOWS.md), [backend](../docs/review/BACKEND.md), [API](../docs/review/API.md) and [security](../SECURITY.md). Trace input → certificate identity → policy/grant → durable operation → restricted adapter → result guard. Review relay routing separately from execution authorization. Include duplicate/lost acknowledgements, revocation races, stale cursors, identity mismatch and VPN interruption.

Keep implemented, observed and proposed capabilities separate. Preserve dated evidence. Consult current official OpenAI documentation for new integration claims. Stored history success is not proof of controlling the existing desktop runtime.

0.3.8 binaries were built from runtime source commit `cc9c65e`; publication documentation/tooling may be newer. Debug APK signing preserves existing installs; production signing, signed updates and desktop version gating remain future work.

Before publishing inspect reachable Git history, tracked binaries, archive members, versions/signatures and SHA-256. Upload an explicit asset allowlist. Never package a portable directory containing `devicebridge-data`; use a verified archive or a new clean build. A public repository does not authorize uploading local policy, credentials, logs, history or screenshots.

Track helpers by PID/start time/path. Keep the user-requested Companion running; clean only task-owned helpers. Record actual checks and remaining evidence gaps.
