# Security scope — experimental 0.3.8

This bridge can read sensitive conversation data and, when explicitly authorized, submit text that can trigger tools in the existing desktop environment. Restrict pairing and session grants to devices and sessions you trust.

## Trust boundaries

- WSS relay admission routes connections; inner TLS authenticates endpoints; host policy AND per-device grants authorize session operations.
- Relay operators can observe IPs, routing identity, timing and sizes and can deny service. They should not hold inner TLS private keys or conversation plaintext.
- The Windows user account is a local trust boundary. DPAPI does not protect against a process already controlling that account. A rooted/compromised phone is outside the tested boundary.
- Cache cannot prove authorization, readiness or delivery. Revocation cannot erase already downloaded data or undo tasks already handed off.
- Unknown delivery must be reconciled, never automatically replayed. No broad RPC, shell, file path or global setting endpoint is available to phones.

## Known gaps

Experimental debug-signed APK; unsigned Windows executable; no signed updater, desktop-version gate, backup rollback protection, comprehensive mobile background execution or broad network compatibility evidence. Mobile approval decisions and live question answers remain unimplemented. Desktop adapters include internal interfaces that may change.

Public artifacts contain programs and synthetic examples only. Keep keys, pairing QR codes, route tickets, private registration state, real policies, journals, logs and screenshots outside Git and Releases. Repository visibility does not expose or authorize access to a running host; deployment and local grants are separate.

## Reporting

Use GitHub's private **Report a vulnerability** entry when enabled. If unavailable, open a minimal issue describing the affected component and request a private channel; do not include credentials, real conversations, exploit traffic against production services or personal data in a public issue.

Security tests should use owned loopback hosts, synthetic identities and bounded traffic. This repository does not authorize attacks against OpenAI, ToDesk, hosting providers, unrelated devices or third-party infrastructure. See [AGENTS.md](AGENTS.md) for contributor constraints and [review docs](docs/review/BACKEND.md) for source entry points.
