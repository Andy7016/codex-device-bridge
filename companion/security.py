"""Standard cryptography; private host material is user-bound on Windows."""

import base64
import ctypes
from ctypes import wintypes
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import os
import secrets
from pathlib import Path
import ssl
import tempfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def private_address(value):
    address = ipaddress.ip_address(value)
    ranges = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '127.0.0.0/8') if address.version == 4 else ('fc00::/7', '::1/128')
    if not any(address in ipaddress.ip_network(network) for network in ranges):
        raise ValueError('private_direct_address_required')
    return address


def certificate(pem):
    if not isinstance(pem, str) or len(pem.encode()) > 8192 or pem.count('-----BEGIN CERTIFICATE-----') != 1:
        raise ValueError('invalid_certificate')
    cert = x509.load_pem_x509_certificate(pem.encode('ascii'))
    if cert.public_bytes(serialization.Encoding.PEM).decode().strip() != pem.strip():
        raise ValueError('invalid_certificate')
    now = datetime.now(timezone.utc)
    if not cert.not_valid_before_utc <= now < cert.not_valid_after_utc:
        raise ValueError('certificate_expired')
    return cert


def fingerprint(cert):
    return sha256(cert.public_bytes(serialization.Encoding.DER))


def public_id(cert):
    return sha256(cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))


def verify_phone(pem):
    cert = certificate(pem)
    key = cert.public_key()
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError('phone_key_algorithm')
    if cert.subject != cert.issuer or not isinstance(cert.signature_hash_algorithm, hashes.SHA256):
        raise ValueError('phone_self_signature')
    try:
        if cert.extensions.get_extension_for_class(x509.BasicConstraints).value.ca:
            raise ValueError('phone_ca_not_allowed')
    except x509.ExtensionNotFound:
        pass
    try:
        if cert.extensions.get_extension_for_class(x509.KeyUsage).value.key_cert_sign:
            raise ValueError('phone_ca_not_allowed')
    except x509.ExtensionNotFound:
        pass
    key.verify(cert.signature, cert.tbs_certificate_bytes, ec.ECDSA(hashes.SHA256()))
    return cert


def proof_bytes(invite, request, host, challenge, phone):
    return '\n'.join(('device-bridge-pair-v1', invite, request, host, challenge, phone)).encode('utf8')


def confirmation(proof):
    return str(int(sha256(proof), 16) % 1000000).zfill(6)


def verify_proof(cert, proof, signature):
    if not isinstance(signature, str) or len(signature) > 192:
        raise ValueError('invalid_proof')
    cert.public_key().verify(base64.b64decode(signature, validate=True), proof, ec.ECDSA(hashes.SHA256()))


def generate_identity(name=None):
    key = ec.generate_private_key(ec.SECP256R1())
    pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    identity = sha256(pub)
    dns = 'device.'+identity[:32]+'.'+identity[32:]+'.bridge.invalid'
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name or 'Device Bridge')])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=5))
            .not_valid_after(now+timedelta(days=365)).add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName(dns)]), False).sign(key, hashes.SHA256()))
    pem = cert.public_bytes(serialization.Encoding.PEM).decode('ascii')
    private = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return identity, dns, pem, private


class _Blob(ctypes.Structure):
    _fields_ = [('length', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]


def _dpapi(data, decrypt=False):
    if os.name != 'nt':
        raise RuntimeError('Windows user-bound DPAPI is required for host storage')
    buffer = ctypes.create_string_buffer(data)
    source = _Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    target = _Blob()
    function = getattr(ctypes.WinDLL('crypt32', use_last_error=True), 'CryptUnprotectData' if decrypt else 'CryptProtectData')
    function.argtypes = [ctypes.POINTER(_Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(_Blob)]
    function.restype = wintypes.BOOL
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise OSError('private_identity_unavailable')
    try:
        return ctypes.string_at(target.data, target.length)
    finally:
        ctypes.memset(target.data, 0, target.length)
        free = ctypes.WinDLL('kernel32').LocalFree
        free.argtypes = [ctypes.c_void_p]
        free.restype = ctypes.c_void_p
        free(target.data)


def protect(data):
    return b'DBDP1'+_dpapi(data)


def unprotect(data):
    if not data.startswith(b'DBDP1'):
        raise ValueError('invalid_private_identity')
    return _dpapi(data[5:], decrypt=True)


def server_context(cert_path, key_pem, directory, phone_pems=()):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    fd, name = tempfile.mkstemp(prefix='.tls-key-', dir=Path(directory))
    try:
        password = secrets.token_urlsafe(32).encode('ascii')
        temporary_key = serialization.load_pem_private_key(key_pem, password=None).private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.BestAvailableEncryption(password))
        with os.fdopen(fd, 'wb') as output:
            output.write(temporary_key)
        context.load_cert_chain(str(cert_path), name, password=password)
    finally:
        Path(name).unlink(missing_ok=True)
    context.verify_mode = ssl.CERT_OPTIONAL
    if phone_pems:
        context.load_verify_locations(cadata='\n'.join(phone_pems))
    return context


def client_context(endpoint):
    pem = endpoint.get('certificatePem')
    context = ssl.create_default_context(cadata=pem) if pem else ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context


def check_pin(socket, pem):
    if pem and sha256(socket.getpeercert(binary_form=True)) != fingerprint(certificate(pem)):
        raise ssl.SSLError('certificate_pin_mismatch')
