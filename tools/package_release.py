"""Collect only allowlisted program artifacts, never private host/test state."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    output = ROOT/'.local/distribution'
    output.mkdir(parents=True, exist_ok=True)
    apk = output/'device-bridge-0.2.0-debug.apk'
    shutil.copyfile(ROOT/'android/app/build/outputs/apk/debug/app-debug.apk', apk)
    with zipfile.ZipFile(apk) as package:
        if any(name.startswith('assets/') for name in package.namelist()):
            raise ValueError('main APK unexpectedly contains assets; inspect before distribution')
    windows = output/'device-bridge-companion-0.2.0-windows.zip'
    program = output/'DeviceBridge-Companion'
    with zipfile.ZipFile(windows, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(program.rglob('*')):
            if path.is_file():
                if path.is_symlink() or path.suffix in {'.dpapi', '.pem', '.key', '.sqlite', '.json'}:
                    raise ValueError('unexpected private/config file in program package')
                archive.write(path, 'DeviceBridge-Companion/'+path.relative_to(program).as_posix())
        archive.write(ROOT/'companion/README.md', 'DeviceBridge-Companion/README.md')
    relay = output/'device-bridge-relay-0.2.0.zip'
    sources = ['companion/__init__.py', 'companion/relay.py', 'companion/wire.py', 'companion/Dockerfile.relay',
               'prototype/__init__.py', 'prototype/loopback.py', 'prototype/journal.py', 'prototype/lease.py', '.dockerignore']
    with zipfile.ZipFile(relay, 'w', zipfile.ZIP_DEFLATED) as archive:
        for relative in sources:
            archive.write(ROOT/relative, relative)
        archive.write(ROOT/'companion/RELAY.md', 'README.md')
    artifacts = [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': digest(p)} for p in (apk, windows, relay)]
    (output/'build-info-v02.json').write_text(json.dumps({'version': '0.2.0', 'artifacts': artifacts}, indent=2), encoding='utf8')
    print(json.dumps(artifacts, indent=2))


if __name__ == '__main__':
    main()
