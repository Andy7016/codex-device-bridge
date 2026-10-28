"""Explicit local mTLS desktop host. No public listener or network administration."""

import hashlib
import json
from pathlib import Path
import ssl
import sys
import threading

from .desktop import DesktopRecoveryService, NodeDesktopRuntime
from .journal import Journal
from .loopback import LoopbackServer


def build_host(config, *, runtime_factory=NodeDesktopRuntime):
    policy = json.loads(Path(config["policy"]).read_text(encoding="utf-8-sig"))
    path = Path(config["journal"]).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    fresh = not path.exists()
    journal = Journal(path, retention=config.get("retention", 8))
    try:
        # Bootstrap never reenables or rewrites a persisted device on restart.
        if fresh:
            for grant in config.get("bootstrapGrants", []):
                journal.provision(grant["device"], grant["thread"], rights=tuple(grant["rights"]))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(config["serverCert"], config["serverKey"])
        context.verify_mode = ssl.CERT_REQUIRED
        identities, certificates = {}, []
        for peer in config["peers"]:
            certificate = Path(peer["certificate"]).read_text(encoding="ascii")
            fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate)).hexdigest()
            if fingerprint in identities:
                raise ValueError("duplicate peer certificate")
            identities[fingerprint] = peer["device"]
            certificates.append(certificate)
        context.load_verify_locations(cadata="\n".join(certificates))
        runtime = runtime_factory(config["policy"], config["attempts"])
        service = DesktopRecoveryService(journal, runtime, policy, devices=set(identities.values()))
        server = LoopbackServer(("127.0.0.1", config.get("port", 0)), context, identities, journal, service=service)
        return server, journal
    except BaseException:
        journal.close()
        raise


def serve(config, *, runtime_factory=NodeDesktopRuntime):
    server, journal = build_host(config, runtime_factory=runtime_factory)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02})
    thread.start()
    print(json.dumps({"ready": True, "port": server.server_address[1], "loopbackOnly": True}), flush=True)
    try:
        for line in sys.stdin:
            try:
                if len(line.encode()) > 1024:
                    raise ValueError("control frame too large")
                command = json.loads(line)
                if command == {"action": "shutdown"}:
                    print('{"stopping":true}', flush=True)
                    break
                if set(command) != {"action", "device"} or command["action"] != "revoke":
                    raise ValueError("unsupported local control")
                server.service.revoke(command["device"])
                print('{"revoked":true}', flush=True)
            except Exception:
                print('{"ok":false,"error":"local_control_failed"}', flush=True)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)
        journal.close()


if __name__ == "__main__":
    config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    serve(config)
