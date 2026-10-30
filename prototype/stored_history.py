"""Bounded stored-history reads. Never resumes, generates, or owns a thread."""
from collections import OrderedDict
import hashlib
import hmac
import json
import os
from pathlib import Path
import queue
import secrets
import shutil
import subprocess
import threading
import time

from .child_job import KillOnCloseJob
from .desktop import DesktopUnavailable, NodeDesktopRuntime


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':')).encode()).hexdigest()


def codex_executable():
    found = shutil.which('codex.exe')
    if found:
        return str(Path(found).resolve())
    local = os.environ.get('LOCALAPPDATA')
    candidates = list((Path(local)/'OpenAI/Codex/bin').glob('*/codex.exe')) if local else []
    if not candidates or len(candidates) > 16:
        raise DesktopUnavailable('stored history executable unavailable')
    return str(max(candidates, key=lambda path: path.stat().st_mtime_ns).resolve())


class ReadOnlyHistoryProcess:
    METHODS = {'initialize', 'thread/turns/list'}
    FRAME_LIMIT = 2 * 1024 * 1024

    def __init__(self, executable=None, timeout=10):
        self.executable, self.timeout = executable, timeout
        self.process, self.job = None, None
        self.lock = threading.RLock()
        self.counter = 0
        self.cancelled = False

    def _start(self):
        if self.cancelled:
            raise DesktopUnavailable('stored history stopping')
        if self.process is not None and self.process.poll() is None:
            return
        self.close()
        # Identity is local to this read-only service. No agent caller context.
        allowed = {'systemroot', 'windir', 'path', 'temp', 'tmp', 'pathext',
                   'localappdata', 'appdata', 'userprofile', 'homedrive', 'homepath'}
        environment = {k: v for k, v in os.environ.items() if k.lower() in allowed}
        try:
            self.process = subprocess.Popen([self.executable or codex_executable(), 'app-server', '--listen', 'stdio://'],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                env=environment, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            self.job = KillOnCloseJob(self.process)
            self.replies = queue.Queue(maxsize=4)
            process, replies = self.process, self.replies

            def read():
                try:
                    while True:
                        raw = process.stdout.readline(self.FRAME_LIMIT+1)
                        if not raw:
                            break
                        if len(raw) > self.FRAME_LIMIT or not raw.endswith(b'\n'):
                            process.kill()
                            break
                        value = json.loads(raw)
                        # Ignore notifications and never answer server requests.
                        if isinstance(value, dict) and 'id' in value and 'method' not in value:
                            replies.put_nowait(value)
                except (OSError, ValueError, queue.Full):
                    if process.poll() is None:
                        process.kill()
                finally:
                    try:
                        replies.put_nowait(None)
                    except queue.Full:
                        pass

            self.reader = threading.Thread(target=read, daemon=True)
            self.reader.start()
            self._request('initialize', {'clientInfo': {'name': 'device_bridge_stored_history', 'version': '0.3.8'}})
            self.process.stdin.write(b'{"method":"initialized","params":{}}\n')
            self.process.stdin.flush()
        except Exception:
            self.close()
            raise DesktopUnavailable('stored history initialization unavailable') from None

    def _request(self, method, params):
        if method not in self.METHODS:
            raise ValueError('read_only_method_required')
        self.counter += 1
        request_id = self.counter
        raw = json.dumps({'id': request_id, 'method': method, 'params': params}, separators=(',', ':')).encode()+b'\n'
        if len(raw) > 8192:
            raise DesktopUnavailable('stored request too large')
        self.process.stdin.write(raw)
        self.process.stdin.flush()
        deadline = time.monotonic()+self.timeout
        while True:
            reply = self.replies.get(timeout=max(.001, deadline-time.monotonic()))
            if reply is None:
                raise DesktopUnavailable('stored history child unavailable')
            if reply.get('id') == request_id:
                if not isinstance(reply.get('result'), dict):
                    raise DesktopUnavailable('stored history request unavailable')
                return reply['result']
            if time.monotonic() >= deadline:
                raise DesktopUnavailable('stored history timed out')

    def request(self, method, params):
        if method != 'thread/turns/list':
            raise ValueError('read_only_method_required')
        with self.lock:
            try:
                self._start()
                return self._request(method, params)
            except (OSError, queue.Empty, DesktopUnavailable):
                self.close()
                raise DesktopUnavailable('stored history temporarily unavailable') from None

    def close(self):
        with self.lock:
            process, job = self.process, self.job
            self.process = self.job = None
            if job is not None:
                job.close()
            if process is not None:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=3)
                reader = getattr(self, 'reader', None)
                if reader is not None and reader.ident is not None and reader is not threading.current_thread():
                    reader.join(3)
                for stream in (process.stdin, process.stdout):
                    if stream is not None:
                        stream.close()

    def cancel(self):
        self.cancelled = True
        process = self.process
        if process is not None and process.poll() is None:
            try:
                process.kill()
            except OSError:
                pass


def project_turn(turn):
    messages = []
    turn_id = str(turn.get('id', ''))[:128]
    for index, item in enumerate(turn.get('items', [])):
        kind = item.get('type')
        if kind == 'userMessage':
            role = 'user'
            text = '\n'.join(str(part.get('text', '')) for part in item.get('content', []) if part.get('type') == 'text')
        elif kind == 'agentMessage':
            role, text = 'assistant', item.get('text', '')
        else:
            # Summary mode intentionally omits full command/tool output.
            continue
        text = str(text or '')
        questions = item.get('questions', []) if kind == 'agentMessage' else []
        projected_questions = []
        if isinstance(questions, list):
            for question_index, question in enumerate(questions):
                if not isinstance(question, dict) or not isinstance(question.get('title'), str):
                    continue
                options = question.get('options')
                choices = [v for v in options if isinstance(v, str)] if isinstance(options, list) else []
                text = str(text)+('\n\n' if text else '')+'提问：'+question['title']+('\n可选回答：\n'+'\n'.join('• '+v for v in choices) if choices else '')
                if len(projected_questions) < 3:
                    projected_questions.append({'id': json.dumps(['request_user_input_async', str(item.get('id', ''))[:128], question_index], separators=(',', ':')),
                        'title': question['title'][:256], 'options': [v[:128] for v in choices[:12]]})
        if text or projected_questions:
            messages.append({'id': turn_id+':'+str(item.get('id', index))[:128], 'role': role,
                             'text': str(text), 'status': str(turn.get('status', ''))[:32],
                             **({'questions': projected_questions} if projected_questions else {})})
    return messages


class StoredHistoryReader:
    def __init__(self, client=None):
        self.client = client or ReadOnlyHistoryProcess()
        self.lock = threading.RLock()
        self.secret = secrets.token_bytes(32)
        self.cursors = OrderedDict()

    def _cursor(self, target, state):
        token = 's1.'+hmac.new(self.secret, json.dumps([target, state], sort_keys=True).encode(), 'sha256').hexdigest()
        self.cursors[token] = (time.monotonic(), target, state)
        self.cursors.move_to_end(token)
        while len(self.cursors) > 512:
            self.cursors.popitem(last=False)
        return token

    def _saved(self, target, cursor, kind):
        saved = self.cursors.get(cursor)
        if not saved or saved[1] != target or time.monotonic()-saved[0] > 1800 or saved[2].get('kind', 'page') != kind:
            raise HistoryChanged()
        return dict(saved[2])

    @staticmethod
    def _slice(text, offset, budget):
        raw = text.encode('utf8')
        end = min(len(raw), offset+budget)
        while end < len(raw) and end > offset and raw[end] & 0xc0 == 0x80:
            end -= 1
        return raw[offset:end].decode('utf8'), end, len(raw)

    def _preview(self, target, state, message):
        text, end, total = self._slice(message['text'], 0, 2400)
        next_part = self._cursor(target, {'kind': 'text', 'cursor': state['cursor'], 'message': message['id'],
            'generation': digest(message), 'offset': end}) if end < total else None
        return {**message, 'text': text, 'nextPart': next_part, 'totalBytes': total, 'contentHash': digest(message)}

    def message(self, target, cursor):
        with self.lock:
            state = self._saved(target, cursor, 'text')
            reply = self.client.request('thread/turns/list', {'threadId': target, 'cursor': state['cursor'],
                'limit': 1, 'itemsView': 'summary', 'sortDirection': 'desc'})
            turns = reply.get('data', [])
            messages = project_turn(turns[0]) if len(turns) == 1 else []
            message = next((item for item in messages if item['id'] == state['message']), None)
            if message is None or digest(message) != state['generation']:
                raise HistoryChanged()
            budget = 24000
            while True:
                text, end, total = self._slice(message['text'], state['offset'], budget)
                if len(json.dumps(text, ensure_ascii=False).encode()) <= 32000:
                    break
                budget //= 2
            next_part = self._cursor(target, {**state, 'offset': end}) if end < total else None
            return {'id': message['id'], 'text': text, 'nextPart': next_part, 'totalBytes': total, 'contentHash': digest(message)}

    def history(self, target, before, limit):
        if type(limit) is not int or not 1 <= limit <= 8:
            raise ValueError('invalid_history_page')
        with self.lock:
            state = {'cursor': None, 'skip': 0, 'generation': None}
            if before is not None:
                state = self._saved(target, before, 'page')
            deadline = time.monotonic()+12
            for _ in range(8):
                if time.monotonic() >= deadline:
                    break
                reply = self.client.request('thread/turns/list', {'threadId': target, 'cursor': state['cursor'],
                    'limit': 1, 'itemsView': 'summary', 'sortDirection': 'desc'})
                turns = reply.get('data', [])
                if not isinstance(turns, list) or len(turns) > 1:
                    raise DesktopUnavailable('unexpected stored history page')
                messages = project_turn(turns[0]) if turns else []
                generation = digest(messages)
                if state['generation'] is not None and state['generation'] != generation:
                    raise HistoryChanged()
                next_cursor = reply.get('nextCursor')
                if next_cursor is not None and (not isinstance(next_cursor, str) or len(next_cursor) > 2048):
                    raise DesktopUnavailable('unexpected stored cursor')
                end = len(messages)-state['skip']
                if end < 0:
                    raise HistoryChanged()
                if end or next_cursor is None:
                    start = max(0, end-limit)
                    page = [self._preview(target, state, message) for message in messages[start:end]]
                    while len(page) > 1 and len(json.dumps(page, ensure_ascii=False).encode()) > 6200:
                        start += 1
                        page = [self._preview(target, state, message) for message in messages[start:end]]
                    next_before = None
                    if start:
                        next_before = self._cursor(target, {'cursor': state['cursor'], 'skip': len(messages)-start,
                                                            'generation': generation})
                    elif next_cursor is not None:
                        next_before = self._cursor(target, {'cursor': next_cursor, 'skip': 0, 'generation': None})
                    return {'messages': page, 'nextBefore': next_before, 'hasMore': next_before is not None,
                            'runtimeStatus': 'unknown', 'historyComplete': next_before is None,
                            'source': 'stored-history-preview-v2'}
                state = {'cursor': next_cursor, 'skip': 0, 'generation': None}
            # An empty bounded page still advances over tool-only turns.
            next_before = self._cursor(target, state)
            return {'messages': [], 'nextBefore': next_before, 'hasMore': True,
                    'runtimeStatus': 'unknown', 'historyComplete': False, 'source': 'stored-history-preview-v2'}

    def close(self):
        cancel = getattr(self.client, 'cancel', None)
        if cancel:
            cancel()
        self.client.close()


class HistoryChanged(ValueError):
    pass


class BridgeDesktopRuntime(NodeDesktopRuntime):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.stored = StoredHistoryReader()
        policy = json.loads(self.policy_path.read_text(encoding='utf-8-sig'))
        self.live_labels = {label for label, target in policy.get('targets', {}).items() if 'send' in target.get('rights', [])}

    def history(self, label, target, before, limit):
        # Preserve original-owner readiness for explicitly send-enabled
        # targets. Stored/cached state never grants readiness for a mutation.
        page = self.stored.history(target, before, limit)
        if label in self.live_labels:
            try:
                live = super().history(label, target, None, 1)
                page['runtimeStatus'] = live.get('runtimeStatus', 'unknown')
            except (DesktopUnavailable, OSError):
                pass
        return page

    def message(self, label, target, cursor):
        return self.stored.message(target, cursor)

    def cancel(self):
        self.stored.client.cancel()

    def close(self):
        self.stored.close()
