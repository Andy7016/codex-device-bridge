"""Versioned allowlist packaging for WSS relay and Android, excluding local state."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    output = ROOT / '.local/distribution'
    output.mkdir(parents=True, exist_ok=True)
    apk = output / 'device-bridge-0.3.0-debug.apk'
    shutil.copyfile(ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk', apk)
    with zipfile.ZipFile(apk) as archive:
        if any(name.startswith('assets/') for name in archive.namelist()):
            raise ValueError('private_assets_in_main_apk')
    relay = output / 'device-bridge-wss-relay-0.3.0.zip'
    paths = ['relay/package.json', 'relay/package-lock.json', 'relay/protocol.mjs', 'relay/cloudflare.mjs',
             'relay/render.mjs', 'relay/wrangler.jsonc', 'relay/README.md', 'render.yaml']
    with zipfile.ZipFile(relay, 'w', zipfile.ZIP_DEFLATED) as archive:
        for relative in paths:
            path = ROOT / relative
            if path.is_symlink():
                raise ValueError('linked_package_input')
            archive.write(path, relative)
    companion = output / 'device-bridge-companion-0.3.0-windows.zip'
    artifacts = [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                 for p in (apk, relay, companion)]
    (output / 'build-info-v03.json').write_text(json.dumps({'version': '0.3.0', 'artifacts': artifacts}, indent=2), encoding='utf8')
    print(json.dumps(artifacts, indent=2))


if __name__ == '__main__': main()
