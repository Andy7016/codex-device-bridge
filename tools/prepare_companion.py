"""Prepare a Windows companion identity, including offline initialization."""
import argparse
import json
from companion.configuration import prepare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--policy', help='optional scoped policy; defaults to no access')
    parser.add_argument('--relay-host', help='optional; omit for offline initialization')
    parser.add_argument('--relay-port', type=int, default=443)
    parser.add_argument('--relay-certificate')
    parser.add_argument('--direct-host')
    parser.add_argument('--direct-port', type=int, default=8765)
    args = parser.parse_args()
    print(json.dumps(prepare(args.output, args.policy, args.relay_host, args.relay_port, args.relay_certificate, args.direct_host, args.direct_port)))


if __name__ == '__main__':
    main()
