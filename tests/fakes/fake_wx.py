# 测试用「假微信小程序服务端接口 + 假阿里云短信」，只用 Python 标准库。
#   python fake_wx.py 18080
# 小程序接口（后端配 groupbuy.wx.api-base=http://127.0.0.1:18080）：
#   GET  /sns/jscode2session        js_code=c_<openid> → openid；其他 code 报 40029
#   POST /cgi-bin/stable_token      固定 token
#   POST /wxa/business/getuserphonenumber   code=p_<手机号> → 手机号
#   POST /cgi-bin/message/subscribe/send    按 mode.notify：ok / busy(-1) / down(断开连接) / bad(47003)
#   POST /wxa/getwxacodeunlimit     返回一张 PNG；mode.code=fail 时返回 41030
# 阿里云短信（后端配 groupbuy.sms.endpoint=http://127.0.0.1:18080）：
#   GET/POST /?Action=SendSms       记下手机号和验证码；mode.sms=fail 时返回业务限流
# 控制（测试用）：
#   GET /__mode?notify=busy&sms=ok&code=ok    改模式
#   GET /__calls?kind=notify|sms|code         取调用记录（JSON 数组）
#   GET /__reset                              清空记录、模式恢复 ok
import json, struct, sys, threading, uuid, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

TOKEN = 'FAKE_TOKEN_1'
LOCK = threading.Lock()
MODE = {'notify': 'ok', 'sms': 'ok', 'code': 'ok'}
CALLS = {'notify': [], 'sms': [], 'code': [], 'phone': []}


def png(size=280):
    """黑色方块 + 白边的 PNG（标准库 zlib 手写）"""
    rows = []
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            edge = x < 20 or y < 20 or x >= size - 20 or y >= size - 20
            row += b'\xff\xff\xff' if edge else b'\x00\x00\x00'
        rows.append(bytes(row))
    raw = zlib.compress(b''.join(rows), 6)

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)) + chunk(b'IDAT', raw) + chunk(b'IEND', b'')


PNG = png()


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def out(self, obj, status=200, ctype='application/json; charset=utf-8'):
        b = obj if isinstance(obj, bytes) else json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def body(self):
        n = int(self.headers.get('Content-Length', 0) or 0)
        return self.rfile.read(n) if n else b''

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == '/__mode':
            with LOCK:
                MODE.update(q)
            return self.out(MODE)
        if u.path == '/__calls':
            with LOCK:
                return self.out(CALLS.get(q.get('kind', ''), []))
        if u.path == '/__reset':
            with LOCK:
                for v in CALLS.values():
                    v.clear()
                MODE.update({'notify': 'ok', 'sms': 'ok', 'code': 'ok'})
            return self.out({'ok': True})
        if u.path == '/sns/jscode2session':
            code = q.get('js_code', '')
            if code.startswith('c_'):
                return self.out({'openid': code[2:], 'session_key': 'sk'})
            return self.out({'errcode': 40029, 'errmsg': 'invalid code'})
        if q.get('Action') == 'SendSms':
            return self.sms(q)
        self.out({'errcode': 404, 'errmsg': 'not found'})

    def do_POST(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        raw = self.body()
        if q.get('Action') == 'SendSms' or (u.path == '/' and b'Action=SendSms' in raw):
            q.update({k: v[0] for k, v in parse_qs(raw.decode('utf8')).items()})
            return self.sms(q)
        body = json.loads(raw or b'{}')
        if u.path == '/cgi-bin/stable_token':
            return self.out({'access_token': TOKEN, 'expires_in': 7200})
        if q.get('access_token') != TOKEN:
            return self.out({'errcode': 40001, 'errmsg': 'invalid credential'})
        if u.path == '/wxa/business/getuserphonenumber':
            code = body.get('code', '')
            with LOCK:
                CALLS['phone'].append(code)
            if code.startswith('p_'):
                p = code[2:]
                return self.out({'errcode': 0, 'errmsg': 'ok', 'phone_info': {'phoneNumber': p, 'purePhoneNumber': p, 'countryCode': '86'}})
            return self.out({'errcode': 40029, 'errmsg': 'invalid code'})
        if u.path == '/cgi-bin/message/subscribe/send':
            with LOCK:
                mode = MODE['notify']
                CALLS['notify'].append({'mode': mode, **body})
            if mode == 'busy':
                return self.out({'errcode': -1, 'errmsg': 'system error'})
            if mode == 'bad':
                return self.out({'errcode': 47003, 'errmsg': 'argument invalid! data.thing4.value invalid'})
            if mode == 'down':
                self.close_connection = True
                self.connection.close()
                return
            return self.out({'errcode': 0, 'errmsg': 'ok'})
        if u.path == '/wxa/getwxacodeunlimit':
            with LOCK:
                mode = MODE['code']
                CALLS['code'].append(body)
            if mode == 'fail':
                return self.out({'errcode': 41030, 'errmsg': 'invalid page'})
            return self.out(PNG, ctype='image/png')
        self.out({'errcode': 404, 'errmsg': 'not found'})

    def sms(self, q):
        with LOCK:
            mode = MODE['sms']
            CALLS['sms'].append({'mode': mode, 'phone': q.get('PhoneNumbers'), 'sign': q.get('SignName'), 'template': q.get('TemplateCode'),
                                 'param': q.get('TemplateParam'), 'key': q.get('AccessKeyId'), 'signed': bool(q.get('Signature'))})
        rid = str(uuid.uuid4()).upper()
        if mode == 'fail':
            return self.out({'Code': 'isv.BUSINESS_LIMIT_CONTROL', 'Message': '触发分钟级流控Permits:1', 'RequestId': rid})
        return self.out({'Code': 'OK', 'Message': 'OK', 'BizId': '9006' + rid[:8], 'RequestId': rid})

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
