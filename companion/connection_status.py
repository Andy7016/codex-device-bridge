"""Safe connection phases; never display remote error text or credentials."""
import ssl
import threading


class RelayWaitEnded(ConnectionError):
    """An admitted host's unpaired, bounded waiting channel ended."""


def failure_detail(error):
    seen = set()
    while error is not None and id(error) not in seen and len(seen) < 8:
        seen.add(id(error))
        status = getattr(getattr(error, 'response', None), 'status_code', None)
        if status in (401, 403):
            return '登记未获接受（HTTP '+str(status)+'），请核对当前电脑的中继登记'
        if status == 429:
            return '通道或连接频率已达上限（HTTP 429）'
        if status == 503:
            return '中继服务暂不可用（HTTP 503），请核对部署配置'
        if isinstance(error, ssl.SSLCertVerificationError):
            return '中继证书验证失败'
        if isinstance(error, ImportError):
            return '当前代理类型缺少支持组件'
        # Keep walking: a timeout wrapper may hide a more specific cause.
        following = error.__cause__ or error.__context__
        if following is None and isinstance(error, TimeoutError):
            return '连接超时，请检查网络或代理'
        error = following
    return ''


class RelayStatus:
    """Aggregate both host slots so an idle rotation cannot hide a live slot."""
    def __init__(self):
        self.lock = threading.Lock()
        self.channels = {}

    def update(self, channel, phase, detail=''):
        with self.lock:
            self.channels[channel] = (phase, detail)

    def text(self):
        with self.lock:
            states = list(self.channels.values())
        if any(phase == 'processing' for phase, _ in states):
            return '手机通道已就绪 · 正在处理加密连接'
        if any(phase == 'waiting' for phase, _ in states):
            return '中继已接受连接 · 等待手机接入'
        if any(phase == 'connecting' for phase, _ in states):
            return '正在连接中继 · 后台处理'
        failures = [detail for phase, detail in states if phase == 'failed']
        if failures:
            detail = next((value for value in failures if value), '')
            return '中继连接待恢复' + (' · '+detail if detail else '')
        if states:
            return '等待通道已轮换 · 正在重新接入中继'
        return '中继连接准备中'
