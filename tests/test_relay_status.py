"""User-visible phases and credential-safe failure classification."""
import ssl
from types import SimpleNamespace
import unittest

from companion.connection_status import PhoneStatus, RelayStatus, failure_detail


class RelayStatusTests(unittest.TestCase):
    def test_phone_diagnostics_are_bounded_and_accept_only_fixed_phases(self):
        state = PhoneStatus()
        self.assertIn('尚无手机通道到达', state.text())
        for _ in range(10):
            state.record('channel')
            state.record('tls')
            state.record('reply')
        self.assertEqual(len(state.events), 3)
        self.assertIn('本次启动10次', state.text())
        self.assertIn('会话请求已回复', state.text())
        with self.assertRaises(KeyError):
            state.record('synthetic-secret-do-not-display')
        self.assertNotIn('synthetic-secret', state.text())

    def test_live_waiting_slot_is_not_hidden_by_other_slots_failure_or_rotation(self):
        state = RelayStatus()
        state.update(1, 'waiting')
        state.update(2, 'failed', 'HTTP 429')
        self.assertIn('等待手机', state.text())
        state.update(2, 'renewing')
        self.assertIn('等待手机', state.text())
        state.update(1, 'renewing')
        self.assertIn('通道已轮换', state.text())
        self.assertNotIn('待恢复', state.text())

    def test_ready_channel_and_both_failed_slots_have_distinct_states(self):
        state = RelayStatus()
        state.update(1, 'processing')
        state.update(2, 'waiting')
        self.assertIn('手机通道已就绪', state.text())
        state.update(1, 'failed')
        state.update(2, 'failed', '登记未获接受（HTTP 403）')
        self.assertIn('待恢复', state.text())
        self.assertIn('HTTP 403', state.text())

    def test_nested_http_rejection_reports_status_without_remote_error_text(self):
        for status in (401, 403, 429, 503):
            source = RuntimeError('Authorization: Bearer synthetic-do-not-display')
            source.response = SimpleNamespace(status_code=status)
            wrapper = OSError('untrusted-private-message')
            wrapper.__cause__ = source
            detail = failure_detail(wrapper)
            self.assertIn(str(status), detail)
            self.assertNotIn('synthetic-do-not-display', detail)
            self.assertNotIn('untrusted-private-message', detail)

    def test_certificate_timeout_and_optional_proxy_dependency_fail_closed(self):
        self.assertIn('证书验证失败', failure_detail(ssl.SSLCertVerificationError()))
        self.assertIn('连接超时', failure_detail(TimeoutError()))
        self.assertIn('代理类型', failure_detail(ImportError('sensitive proxy address')))

    def test_cycles_and_unknown_messages_never_escape_to_the_ui(self):
        error = RuntimeError('synthetic secret')
        error.__cause__ = error
        self.assertEqual(failure_detail(error), '')


if __name__ == '__main__':
    unittest.main()
