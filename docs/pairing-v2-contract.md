# Pairing / relay v0.2 implementation contract

Status: implementation specification, not a passed capability claim. No public deployment is authorized by this packet. Existing desktop runtime calls and approval policy remain unchanged.

## Identity and QR

Host has an EC P-256 TLS certificate/key, generated once and retained. `hostId` is lowercase SHA-256 of DER SubjectPublicKeyInfo. `serverName` is `device.<first32-hostId>.<last32-hostId>.bridge.invalid`, present in its DNS SAN (each label <=63 chars). Endpoints are routing hints, never identities. Private host keys are DPAPI protected on Windows. Phone generates its own EC P-256 key in AndroidKeystore, including a valid self-signed certificate. Host registers the exact certificate after challenge proof and local approval; it does not transfer a phone private key.

QR text is `dbpair:` followed by unpadded base64url UTF-8 JSON, max 8192 bytes decoded. Exact fields:

```
schemaVersion: 1
displayName: string (1..80, no controls)
hostId: 64 lowercase hex
serverName: device.<first32-hostId>.<last32-hostId>.bridge.invalid
serverCertificatePem: single PEM X509
serverCertificateSha256: SHA256 DER cert, 64 lowercase hex
relay: {host: hostname/IP, port: integer, certificatePem: PEM or null}
directEndpoints: [{host: private/loopback IP literal, port: integer}] (0..4)
inviteId: 32 lowercase hex
inviteSecret: 64 lowercase hex (256 random bits)
expiresAt: Unix seconds (integer)
```

Relay `certificatePem=null` uses system trust and hostname verification; supplied certificate is a scoped trust anchor AND exact leaf pin, with hostname verification. Inner TLS always uses scoped host cert trust, hostname verification using `serverName`, and exact leaf pin, before any application data. TLS >=1.2; no early data, insecure trust fallback, proxy bypass or VpnService.

## Outbound relay

Outer TLS connects to relay.host:port. JSONL preface <=2048 bytes, read without buffering beyond newline:

Host: `{version:1,role:"host",hostId:<id>,token:<private relay credential>}`.
Phone: `{version:1,role:"client",hostId:<id>}`.

Relay configuration binds hostId to SHA256(token). Only authorized host waiters register. When paired, relay sends `{ready:true}\n` to BOTH sides, then copies raw bytes bidirectionally. Inner TLS runs over this tunnel. Relay has neither host nor phone inner private keys. Idle waiter <=45 seconds, paired tunnel <=45 seconds, bounded connection/thread/buffer counts. A failed/unknown route returns `{ready:false}\n` and closes. No relay-specified arbitrary forwarding destination.

Host keeps two outbound waiters with bounded reconnect backoff. Each inner connection carries one JSONL RPC (existing 8192-byte frame limit) and closes. Direct endpoints use the same inner TLS and RPC, without relay preface. Try bounded direct route first, then relay; retrying transport is permitted only BEFORE any application request has been written. Once a submit starts writing, preserve unknown and reconcile, never replay.

## Pairing RPC (inside server-authenticated inner TLS)

Common envelope: `{method:<name>,params:{...}}`, existing response `{ok:true,result:{...}}` or `{ok:false,error:<safe_code>}`. Only pairing methods are allowed without an authorized client certificate.

`pair.begin` exact params `{inviteId,inviteSecret,requestId,phoneName,clientCertificatePem}`. requestId is UUID string, phoneName 1..80 no controls. Certificate is self-signed ECDSA P-256, SHA256, currently valid, max 8192 bytes; host verifies its signature. One invitation atomically binds one requestId + exact certificate. Identical begin is idempotent, any different binding is denied. Response `{state:"challenge",challenge:<64hex>,confirmationCode:<6 digits>,expiresAt:<seconds>}`.

Proof bytes are UTF-8 (no trailing newline):

```
device-bridge-pair-v1\n<inviteId>\n<requestId>\n<hostId>\n<challenge>\n<clientCertificateSha256>
```

`pair.prove` and `pair.status` exact params `{inviteId,inviteSecret,requestId,signature}`. signature is standard base64 DER ECDSA SHA256 over proof bytes. prove verifies key possession and makes the request visible for local confirmation. status also verifies proof. Return `{state:"pending"|"approved"|"denied",confirmationCode:<6digits>,expiresAt:<seconds>}`. Device is `phone-<SHA256 DER phone cert>`. Code is zero-padded `(int(SHA256(proof bytes).hex,16) mod 1000000)`.

Approval requires local host UI confirmation of exact request, code and selected policy-scoped rights; read/send only, defaults read. Invitation consumed once; approval+device journal provisioning must be recoverable and fail closed on restart. Unconfirmed invites expire at 120 sec and invalidate on process restart. Approved result may be recovered for 10 min beyond invite expiry with same proof; cannot provision again or restore revoked identity. Phone durably saves pending QR + alias + requestId + challenge before prove; resumes same request after lost acknowledgements. No new key/ID on retry. No secrets in logs or ordinary config exports.

## Android profile v2

Exact keys: `schemaVersion:2, displayName, hostId, serverName, serverCertificatePem, serverCertificateSha256, clientKeyAlias, clientCertificateSha256, relay, directEndpoints`.

Encrypted profile stores only key alias, never exported phone key. Keystore identity must match cert fingerprint and have a private key. Profile id is lowercase SHA256 of UTF-8 `hostId + "\n" + phoneCertificateSha256`, excluding endpoint. v1 imported profiles stay supported. Existing operation store/reconciliation semantics stay unchanged. Import/scanning must not clear another profile's unresolved operations. QR import from image is an acceptable camera fallback; raw QR manual paste is a recovery path.

## Test boundary

Only project-owned loopback relay, host and emulator, synthetic policy/runtime/phones. No production OpenAI/ToDesk calls, real messages, public listener, firewall changes, global VPN changes or existing identity reactivation. Bound tests <=60s each, <=32 connections, <=1 MiB synthetic tunnel payload. Cover replay, expired invites, races, altered cert/proof/code, relay wrong identity/cert, lost pairing ack, restart/revoke, direct-to-relay switch before write, and no replay after unknown write.
