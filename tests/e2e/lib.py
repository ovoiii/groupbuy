"""端到端测试的公共工具：调接口、查库、控制假服务。只用 Python 标准库。"""
import json
import os
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request as u


class Ctx:
    def __init__(self, base, db, mysql_args, wx_base, pay_base, java, keys_dir, v3_key):
        self.base = base            # 后端 http://127.0.0.1:8180/jeecg-boot
        self.db = db
        self.mysql_args = mysql_args
        self.wx_base = wx_base      # 假微信 + 假短信
        self.pay_base = pay_base    # 假微信支付
        self.java = java
        self.keys_dir = keys_dir
        self.v3_key = v3_key
        self._admin = None
        self.cache = {}

    # ------------------------------------------------------------ HTTP

    def raw(self, path, data=None, headers=None, body=None, method=None, base=None):
        url = (base or self.base) + path
        h = {'Content-Type': 'application/json; charset=utf-8', **(headers or {})}
        payload = body.encode() if isinstance(body, str) else body if body is not None else (
            json.dumps(data, ensure_ascii=False).encode() if data is not None else None)
        req = u.Request(url, payload, h, method=method)
        try:
            with u.urlopen(req, timeout=60) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers)

    def req(self, path, data=None, headers=None):
        st, b, _ = self.raw(path, data, headers)
        try:
            return json.loads(b.decode('utf8'))
        except Exception:
            raise AssertionError(f'{path} 返回的不是 JSON（HTTP {st}）：{b[:200]!r}')

    def app(self, path, tok=None, data=None):
        """小程序接口；data 为 None 时发 GET"""
        h = {'X-Gb-Token': tok} if tok else {}
        if data is None:
            return self.req('/gb/app' + path, None, h)
        return self.req('/gb/app' + path, data, h)

    def admin(self, path, data=None):
        h = {'X-Access-Token': self.admin_token()}
        return self.req('/gb/admin' + path, data, h)

    def admin_token(self):
        if not self._admin:
            r = self.req('/sys/mLogin', {'username': 'admin', 'password': '123456'})
            assert r.get('success'), f'后台登录失败：{r}'
            self._admin = r['result']['token']
        return self._admin

    def dev_login(self, phone):
        r = self.app('/dev-login', data={'phone': phone})
        assert r['success'], r
        return r['result']['token']

    # ------------------------------------------------------------ 假服务

    def wx(self, path):
        st, b, _ = self.raw(path, base=self.wx_base, method='GET')
        return json.loads(b)

    def wx_mode(self, **kw):
        return self.wx('/__mode?' + urllib.parse.urlencode(kw))

    def wx_calls(self, kind):
        return self.wx('/__calls?kind=' + kind)

    def pay(self, path, body=None):
        st, b, _ = self.raw(path, base=self.pay_base, body=body, method='POST' if body is not None else 'GET')
        return json.loads(b)

    def pay_mode(self, transfer):
        return self.pay('/__mode?transfer=' + transfer)

    def bills(self):
        return self.pay('/__bills')

    def bill(self, no):
        return next((b for b in self.bills() if b['out_bill_no'] == no), None)

    def signed_notify(self, path, typ, event, plain, bad=False):
        """让假微信支付加密、签名一条通知，再 POST 给后端，返回 HTTP 状态码"""
        q = urllib.parse.urlencode({'type': typ, 'event': event, 'bad': '1' if bad else '0'})
        j = self.pay('/__notify?' + q, json.dumps(plain, ensure_ascii=False))
        st, b, _ = self.raw(path, headers=j['headers'], body=j['body'])
        return st

    # ------------------------------------------------------------ 数据库

    def sql(self, q):
        out = subprocess.run(self.mysql_args + ['-N', '--default-character-set=utf8mb4', self.db, '-e', q], capture_output=True)
        if out.returncode != 0:
            raise AssertionError(f'SQL 出错：{out.stderr.decode("utf8", "replace")}\n{q}')
        return out.stdout.decode('utf8').strip()

    def row(self, q):
        s = self.sql(q)
        return s.split('\t') if s else None

    # ------------------------------------------------------------ 等待

    @staticmethod
    def wait(fn, timeout=80, step=1.0, what='条件'):
        end = time.time() + timeout
        last = None
        while time.time() < end:
            last = fn()
            if last:
                return last
            time.sleep(step)
        raise AssertionError(f'等了 {timeout} 秒，{what}还没满足（最后一次：{last!r}）')


def png_size(b):
    """PNG 的宽高（读 IHDR）"""
    assert b[:8] == b'\x89PNG\r\n\x1a\n', f'不是 PNG：{b[:40]!r}'
    return struct.unpack('>II', b[16:24])


def b36(n):
    n = int(n)
    s = ''
    while n:
        n, r = divmod(n, 36)
        s = '0123456789abcdefghijklmnopqrstuvwxyz'[r] + s
    return s or '0'


def eq(actual, expected, what=''):
    assert actual == expected, f'{what}：期望 {expected!r}，实际 {actual!r}'


def ok(r, what=''):
    assert r.get('success'), f'{what}应该成功：{r.get("message")}'
    return r.get('result')


def err(r, contains, what=''):
    assert not r.get('success'), f'{what}应该失败，结果成功了：{r}'
    assert contains in (r.get('message') or ''), f'{what}提示应包含「{contains}」，实际：{r.get("message")}'
