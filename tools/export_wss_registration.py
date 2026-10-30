"""Export relay admission metadata only; host private key remains local."""
import argparse
import json
from pathlib import Path

from companion.admission import registration
from companion.configuration import load_configuration, write_json
from companion.security import unprotect


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    config, _ = load_configuration(args.config)
    private = json.loads(unprotect((Path(args.config).resolve().parent / 'private.dpapi').read_bytes()))
    target = Path(args.output).resolve()
    if target.exists():
        raise FileExistsError('output_already_exists')
    write_json(target, registration(config['identity']['serverCertificatePem'], private['relayToken']))
    print('Relay registration exported; no private key or plaintext host token included.')


if __name__ == '__main__':
    main()
