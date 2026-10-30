"""One-use, proof-bound enrollment; grants share the recovery DB transaction."""
import base64
import hmac
import json
import re
import secrets
import time
import uuid

from prototype.journal import Conflict, Denied, canonical
from .security import certificate, confirmation, fingerprint, proof_bytes, sha256, verify_phone, verify_proof


class Pairing:
    def __init__(self, journal, service, identity, *, clock=time.time, ticket_issuer=None):
        self.journal, self.service, self.identity, self.clock = journal, service, identity, clock
        self.ticket_issuer = ticket_issuer
        with journal.lock, journal.db:
            journal.db.executescript('''
                CREATE TABLE IF NOT EXISTS pairing_invites(
                    id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, expires INTEGER NOT NULL,
                    state TEXT NOT NULL, request_id TEXT, certificate TEXT, phone_name TEXT,
                    challenge TEXT, code TEXT);
                CREATE TABLE IF NOT EXISTS paired_certificates(
                    device TEXT PRIMARY KEY, certificate TEXT NOT NULL,
                    phone_name TEXT NOT NULL, created INTEGER NOT NULL);
            ''')
            journal.db.execute("UPDATE pairing_invites SET state='denied' WHERE state IN ('issued','challenge','pending')")
            for row in journal.db.execute('SELECT device FROM paired_certificates'):
                service.register_device(row['device'])

    def invite(self):
        if not self.identity.get('relay'):
            raise ValueError('relay_not_configured')
        with self.journal.lock, self.journal.db:
            self.journal.db.execute("UPDATE pairing_invites SET state='denied' WHERE state IN ('issued','challenge','pending')")
            self.journal.db.execute('DELETE FROM pairing_invites WHERE expires<?', (int(self.clock())-86400,))
            identity, secret = secrets.token_hex(16), secrets.token_hex(32)
            expires = int(self.clock())+120
            self.journal.db.execute('INSERT INTO pairing_invites(id,secret_hash,expires,state) VALUES(?,?,?,?)',
                                    (identity, sha256(secret.encode()), expires, 'issued'))
            packet = {**self.identity, 'schemaVersion': 1, 'inviteId': identity, 'inviteSecret': secret, 'expiresAt': expires}
            if self.identity['relay'].get('transport') == 'wss':
                if self.ticket_issuer is None:
                    raise ValueError('relay_ticket_issuer_required')
                packet['schemaVersion'] = 2
                packet['relay'] = {**self.identity['relay'], 'ticket': self.ticket_issuer('invite:' + identity, expires=expires + 600)}
            payload = json.dumps(packet, ensure_ascii=False, separators=(',', ':')).encode()
            if len(payload) > 8192:
                raise ValueError('invitation_too_large')
            return {'text': 'dbpair:'+base64.urlsafe_b64encode(payload).decode().rstrip('='), 'packet': packet}

    def _row(self, params):
        for field, size in (('inviteId', 32), ('inviteSecret', 64)):
            if not isinstance(params.get(field), str) or not re.fullmatch('[0-9a-f]{'+str(size)+'}', params[field]):
                raise Denied('pairing_denied')
        request = params.get('requestId')
        if not isinstance(request, str) or str(uuid.UUID(request)) != request:
            raise ValueError('invalid_request_id')
        row = self.journal.db.execute('SELECT * FROM pairing_invites WHERE id=?', (params['inviteId'],)).fetchone()
        if not row or not hmac.compare_digest(row['secret_hash'], sha256(params['inviteSecret'].encode())) or row['state'] == 'denied':
            raise Denied('pairing_denied')
        if self.clock() >= row['expires']+(600 if row['state'] == 'approved' else 0):
            raise Denied('pairing_expired')
        return row

    def _proof(self, row):
        return proof_bytes(row['id'], row['request_id'], self.identity['hostId'], row['challenge'], fingerprint(certificate(row['certificate'])))

    def _result(self, row):
        if row['state'] == 'approved':
            self.journal.authorize_device('phone-'+fingerprint(certificate(row['certificate'])))
        result = {'state': 'approved' if row['state'] == 'approved' else 'pending',
                  'confirmationCode': row['code'], 'expiresAt': row['expires']}
        if row['state'] == 'approved' and self.identity.get('relay', {}).get('transport') == 'wss':
            result['relayTicket'] = self.ticket_issuer('phone:' + fingerprint(certificate(row['certificate'])))
        return result

    def call(self, method, params):
        expected = {'inviteId', 'inviteSecret', 'requestId', 'phoneName', 'clientCertificatePem'} if method == 'pair.begin' else {'inviteId', 'inviteSecret', 'requestId', 'signature'}
        if method not in {'pair.begin', 'pair.prove', 'pair.status'} or not isinstance(params, dict) or set(params) != expected:
            raise ValueError('invalid_pairing_request')
        with self.journal.lock, self.journal.db:
            row = self._row(params)
            if method == 'pair.begin':
                phone = verify_phone(params['clientCertificatePem'])
                name = params['phoneName']
                if not isinstance(name, str) or not 1 <= len(name) <= 80 or name != name.strip() or any(ord(c) < 32 or ord(c) == 127 for c in name):
                    raise ValueError('invalid_phone_name')
                if row['request_id']:
                    if row['request_id'] != params['requestId'] or fingerprint(phone) != fingerprint(certificate(row['certificate'])) or row['phone_name'] != name:
                        raise Conflict('invitation_bound')
                    if row['state'] == 'approved':
                        self._result(row)
                else:
                    challenge = secrets.token_hex(32)
                    code = confirmation(proof_bytes(row['id'], params['requestId'], self.identity['hostId'], challenge, fingerprint(phone)))
                    self.journal.db.execute("UPDATE pairing_invites SET state='challenge',request_id=?,certificate=?,phone_name=?,challenge=?,code=? WHERE id=?",
                                            (params['requestId'], params['clientCertificatePem'], name, challenge, code, row['id']))
                    row = self.journal.db.execute('SELECT * FROM pairing_invites WHERE id=?', (row['id'],)).fetchone()
                return {'state': 'challenge', 'challenge': row['challenge'], 'confirmationCode': row['code'], 'expiresAt': row['expires']}
            if row['request_id'] != params['requestId'] or not row['challenge']:
                raise Denied('pairing_denied')
            verify_proof(certificate(row['certificate']), self._proof(row), params['signature'])
            if method == 'pair.prove' and row['state'] == 'challenge':
                self.journal.db.execute("UPDATE pairing_invites SET state='pending' WHERE id=?", (row['id'],))
                row = self.journal.db.execute('SELECT * FROM pairing_invites WHERE id=?', (row['id'],)).fetchone()
            if row['state'] == 'challenge':
                raise Denied('pairing_not_proven')
            return self._result(row)

    def pending(self):
        with self.journal.lock:
            return [dict(row) for row in self.journal.db.execute(
                "SELECT id,request_id,phone_name,code,expires FROM pairing_invites WHERE state='pending' AND expires>?", (self.clock(),))]

    def approve(self, invite_id, code, grants):
        """Local UI only; never exposed as an RPC."""
        if not isinstance(grants, dict) or not grants:
            raise ValueError('explicit_grants_required')
        for label, rights in grants.items():
            target = self.service._target(label)
            if not isinstance(rights, (list, tuple)) or 'read' not in rights or not set(rights) <= {'read', 'send'} or not set(rights) <= set(target.get('rights', [])):
                raise Denied('grant_exceeds_host_policy')
        with self.journal.lock, self.journal.db:
            row = self.journal.db.execute('SELECT * FROM pairing_invites WHERE id=?', (invite_id,)).fetchone()
            if not row or row['state'] != 'pending' or self.clock() >= row['expires'] or not hmac.compare_digest(str(code), row['code']):
                raise Denied('pairing_not_pending')
            device = 'phone-'+fingerprint(certificate(row['certificate']))
            if self.journal.db.execute('SELECT 1 FROM devices WHERE id=?', (device,)).fetchone():
                raise Conflict('identity_already_registered')
            self.service.register_device(device)
            self.journal.db.execute('INSERT INTO devices VALUES(?,1)', (device,))
            for label, rights in grants.items():
                self.journal.db.execute('INSERT INTO grants VALUES(?,?,?)', (device, label, canonical(sorted(set(rights)))))
            self.journal.db.execute('INSERT INTO paired_certificates VALUES(?,?,?,?)', (device, row['certificate'], row['phone_name'], int(self.clock())))
            self.journal.db.execute("UPDATE pairing_invites SET state='approved' WHERE id=?", (invite_id,))
            return device

    def deny(self, invite_id):
        with self.journal.lock, self.journal.db:
            self.journal.db.execute("UPDATE pairing_invites SET state='denied' WHERE id=? AND state IN ('issued','challenge','pending')", (invite_id,))

    def devices(self):
        with self.journal.lock:
            return [dict(row) for row in self.journal.db.execute(
                'SELECT device,phone_name,created,enabled FROM paired_certificates JOIN devices ON devices.id=paired_certificates.device')]

    def trusted(self):
        with self.journal.lock:
            result = {}
            for row in self.journal.db.execute('SELECT device,certificate FROM paired_certificates JOIN devices ON devices.id=paired_certificates.device WHERE enabled=1'):
                try:
                    result[fingerprint(certificate(row['certificate']))] = (row['device'], row['certificate'])
                except ValueError:
                    continue  # expired phone cannot disable other paired phones
            return result
