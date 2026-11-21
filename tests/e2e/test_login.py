"""登录：微信手机号快捷登录、短信验证码登录（假微信 + 假阿里云短信）"""
import json
from lib import eq, ok, err


def last_code(ctx, phone):
    """假阿里云短信收到的最后一条验证码"""
    sms = [c for c in ctx.wx_calls('sms') if c['phone'] == phone]
    assert sms, f'{phone} 没收到短信'
    return json.loads(sms[-1]['param'])['code']


def setup(ctx):
    ctx.wx('/__reset')


def test_wechat_phone_login(ctx):
    """微信手机号快捷登录：新用户一次注册，老用户直接登录（不再换手机号）"""
    r = ok(ctx.app('/login', data={'code': 'c_oE2eA', 'phoneCode': 'p_13900000001'}), '快捷登录')
    assert r['token'] and r['member']['rawPhone'] == '13900000001'
    n = len(ctx.wx_calls('phone'))
    r2 = ok(ctx.app('/login', data={'code': 'c_oE2eA', 'phoneCode': 'p_13900000001'}), '再登录')
    eq(r2['member']['id'], r['member']['id'], '同一个账号')
    eq(len(ctx.wx_calls('phone')), n, '老用户不再调换手机号接口（按次收费）')


def test_wechat_two_step(ctx):
    """没绑手机号的微信先拿票据，再用手机号 code 完成；伪造票据不行"""
    r = ok(ctx.app('/login', data={'code': 'c_oE2eB'}))
    assert r.get('needPhone') and r.get('ticket'), r
    r2 = ok(ctx.app('/login/phone', data={'ticket': r['ticket'], 'code': 'p_13900000002'}))
    eq(r2['member']['rawPhone'], '13900000002', '手机号')
    bad = ctx.app('/login/phone', data={'ticket': r['ticket'][:-2] + 'xx', 'code': 'p_13900000002'})
    eq(bad.get('code'), 410, '伪造票据')
    eq(ctx.app('/me', r['ticket']).get('code'), 401, '票据不能当登录凭证')


def test_sms_send(ctx):
    """发验证码：真的调了阿里云接口（签名、模板、参数对），接口不返回验证码"""
    r = ok(ctx.app('/sms/send', data={'phone': '13900000011'}), '发验证码')
    assert 'devCode' not in (r or {}), '用阿里云时不能把验证码返回给前端'
    eq(r['interval'], 60, '间隔')
    c = ctx.wx_calls('sms')[-1]
    eq(c['phone'], '13900000011', '手机号')
    eq(c['template'], 'SMS_E2E_001', '模板')
    eq(c['sign'], 'LinLiXianTest', '签名名称')
    eq(c['key'], 'e2eAccessKey', 'AccessKey')
    assert c['signed'], '请求要带阿里云签名'
    code = json.loads(c['param'])['code']
    assert len(code) == 6 and code.isdigit(), code
    row = ctx.row("select status, code_hash from gb_sms_code where phone='13900000011' order by create_time desc limit 1")
    eq(row[0], 'sent', '记录状态')
    assert code not in row[1], '库里不能存验证码明文'


def test_sms_interval(ctx):
    """60 秒内不能再发"""
    err(ctx.app('/sms/send', data={'phone': '13900000011'}), '秒后再试', '马上再发')


def test_sms_login(ctx):
    """输错提示还剩几次；输对登录并新建账号；同一个验证码不能再用"""
    phone = '13900000011'
    code = last_code(ctx, phone)
    wrong = '000000' if code != '000000' else '111111'
    err(ctx.app('/login/sms', data={'phone': phone, 'code': wrong}), '还能再试 4 次', '输错')
    r = ok(ctx.app('/login/sms', data={'phone': phone, 'code': code}), '输对')
    eq(r['member']['rawPhone'], phone, '登录的手机号')
    assert ok(ctx.app('/me', r['token']))['id'] == r['member']['id']
    err(ctx.app('/login/sms', data={'phone': phone, 'code': code}), '重新获取', '同一个验证码再用')


def test_sms_existing_account(ctx):
    """已有账号（演示团长）用短信登录进的是原账号"""
    phone = '13800002716'
    ok(ctx.app('/sms/send', data={'phone': phone}))
    r = ok(ctx.app('/login/sms', data={'phone': phone, 'code': last_code(ctx, phone)}))
    eq(r['member']['id'], ctx.sql(f"select id from gb_member where phone='{phone}'"), '账号')
    assert r['member']['leaderPoint'], '团长身份还在'


def test_sms_five_wrong(ctx):
    """输错 5 次作废，再输对也不行"""
    phone = '13900000012'
    ok(ctx.app('/sms/send', data={'phone': phone}))
    code = last_code(ctx, phone)
    wrong = '000000' if code != '000000' else '111111'
    for i in range(4):
        err(ctx.app('/login/sms', data={'phone': phone, 'code': wrong}), f'还能再试 {4 - i} 次', f'第 {i + 1} 次输错')
    err(ctx.app('/login/sms', data={'phone': phone, 'code': wrong}), '输错次数太多', '第 5 次')
    err(ctx.app('/login/sms', data={'phone': phone, 'code': code}), '输错次数太多', '作废后输对')


def test_sms_with_wechat(ctx):
    """小程序里短信登录同时绑上微信（付款要 openid）"""
    phone = '13900000013'
    ok(ctx.app('/sms/send', data={'phone': phone}))
    ok(ctx.app('/login/sms', data={'phone': phone, 'code': last_code(ctx, phone), 'wxCode': 'c_oE2eSms'}))
    eq(ctx.sql(f"select openid from gb_member where phone='{phone}'"), 'oE2eSms', 'openid')


def test_sms_provider_fail(ctx):
    """短信服务商出错：提示没发出去，记录为失败"""
    ctx.wx_mode(sms='fail')
    try:
        err(ctx.app('/sms/send', data={'phone': '13900000014'}), '没发出去', '服务商限流')
        row = ctx.row("select status, note from gb_sms_code where phone='13900000014'")
        eq(row[0], 'failed', '状态')
        assert 'BUSINESS_LIMIT_CONTROL' in row[1], row[1]
    finally:
        ctx.wx_mode(sms='ok')


def test_sms_daily_limit(ctx):
    """同一个号一天最多 10 条"""
    phone = '13900000015'
    for i in range(10):
        ctx.sql(f"insert into gb_sms_code (id, phone, code_hash, ip, status, attempts, expire_time, create_time) values "
                f"('e2e{phone}{i:02d}', '{phone}', 'x', '10.0.0.{i}', 'sent', 0, now(), now() - interval 2 minute)")
    err(ctx.app('/sms/send', data={'phone': phone}), '太多了', '第 11 条')


def test_sms_bad_input(ctx):
    """手机号、验证码格式不对"""
    err(ctx.app('/sms/send', data={'phone': '12345'}), '11 位', '手机号')
    err(ctx.app('/login/sms', data={'phone': '13900000016', 'code': '12'}), '6 位', '验证码')
    err(ctx.app('/login/sms', data={'phone': '13900000016', 'code': '123456'}), '过期', '没发过验证码')
