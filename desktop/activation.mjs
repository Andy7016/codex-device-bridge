import { createHash } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

export class AlreadyAttempted extends Error {
  constructor(code) { super(code); this.code = code; this.deliveryUnknown = true; }
}

function durableNewFile(file, record) {
  const fd = openSync(file, 'wx', 0o600);
  try { writeFileSync(fd, JSON.stringify(record)); fsyncSync(fd); }
  finally { closeSync(fd); }
}

export class ActivationBarrier {
  constructor(directory) {
    this.directory = path.resolve(directory);
    mkdirSync(this.directory, { recursive: true });
  }
  begin(operationId, targetId, prompt) {
    if (typeof operationId !== 'string' || !/^[a-zA-Z0-9_-]{1,96}$/.test(operationId)) throw new Error('invalid_operation_id');
    const digest = createHash('sha256').update(JSON.stringify([targetId, prompt])).digest('hex');
    const file = path.join(this.directory, operationId + '.attempt.json');
    try { durableNewFile(file, { digest, attemptedAt: Date.now(), state: 'attempted' }); }
    catch (error) {
      if (error.code !== 'EEXIST') throw error;
      let record;
      try { record = JSON.parse(readFileSync(file, 'utf8')); }
      catch { throw new AlreadyAttempted('damaged_activation_record'); }
      throw new AlreadyAttempted(record.digest === digest ? 'already_attempted_query_history' : 'operation_id_conflict');
    }
    return () => {
      const receipt = path.join(this.directory, operationId + '.accepted.json');
      if (!existsSync(receipt)) durableNewFile(receipt, { digest, acceptedAt: Date.now(), state: 'accepted' });
    };
  }
}
