"""Package a versioned desktop build; private runtime state is never included."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='0.3.7')
    args = parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version):
        raise ValueError('invalid_version')
    root = Path(__file__).resolve().parents[1]
    distribution = root/'.local/distribution'
    source = distribution/('companion-'+args.version)/'DeviceBridge-Companion'
    if not (source/'DeviceBridge-Companion.exe').is_file():
        raise FileNotFoundError('build_companion_first')
    files = sorted(source.rglob('*'))
    for path in files:
        if path.is_symlink() or path.name == 'devicebridge-data' or path.suffix in {'.dpapi', '.pem', '.key', '.p12', '.sqlite', '.json'}:
            raise ValueError('runtime_data_in_build_output')
    target = distribution/('device-bridge-companion-'+args.version+'-windows.zip')
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files:
            if path.is_file():
                archive.write(path, 'DeviceBridge-Companion/'+path.relative_to(source).as_posix())
        archive.write(root/'companion/README.md', 'DeviceBridge-Companion/README.md')
    manifest = {'version': args.version, 'name': target.name, 'bytes': target.stat().st_size,
                'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
    (distribution/('build-info-companion-'+args.version+'.json')).write_text(json.dumps(manifest, indent=2), encoding='utf8')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
