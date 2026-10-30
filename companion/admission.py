"""Host-signed ES256 route tickets; never authorize desktop operations."""
import base64
import json
import re
import secrets
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

from .security import certificate, public_id, sha256

MAX_TICKET_AGE = 30 * 86400


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def sign_claims(key_pem, payload):
    key = serialization.load_pem_private_key(key_pem.encode() if isinstance(key_pem, str) else key_pem, password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError('invalid_relay_key')
    signing_input = 'eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.' + b64(json.dumps(payload, separators=(',', ':')).encode())
    r, s = decode_dss_signature(key.sign(signing_input.encode(), ec.ECDSA(hashes.SHA256())))
    return signing_input + '.' + b64(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))


def issue_ticket(key_pem, host_id, subject, *, expires=None, now=None):
    now = int(time.time()) if now is None else int(now)
    expires = now + MAX_TICKET_AGE if expires is None else int(expires)
    if not re.fullmatch(r'[0-9a-f]{64}', host_id) or not re.fullmatch(r'(invite:[0-9a-f]{32}|phone:[0-9a-f]{64})', subject):
        raise ValueError('invalid_relay_subject')
    if not now < expires <= now + MAX_TICKET_AGE:
        raise ValueError('invalid_relay_expiry')
    payload = {'iss': host_id, 'sub': subject, 'aud': 'device-bridge-relay-v1', 'iat': now,
               'exp': expires, 'jti': secrets.token_hex(16)}
    return sign_claims(key_pem, payload)


def registration(cert_pem, token):
    cert = certificate(cert_pem)
    key = cert.public_key()
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError('invalid_relay_key')
    numbers = key.public_numbers()
    return {public_id(cert): {'tokenSha256': sha256(token.encode()), 'publicKeyJwk': {
        'kty': 'EC', 'crv': 'P-256', 'x': b64(numbers.x.to_bytes(32, 'big')), 'y': b64(numbers.y.to_bytes(32, 'big'))}}}
