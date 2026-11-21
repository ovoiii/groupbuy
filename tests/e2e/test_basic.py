"""基础：配置、首页、下单、取消"""
from lib import eq, ok, err

RESIDENT = '13800001029'


def setup(ctx):
    ctx.cache['resident'] = ctx.dev_login(RESIDENT)


def test_config(ctx):
    """/config 告诉小程序哪些登录方式能用"""
    c = ok(ctx.app('/config'))
    eq(c['wxLogin'], True, '微信登录')
    eq(c['smsLogin'], True, '短信登录')
    eq(c['payMock'], False, '真实支付')
    eq(c['tmpl'].get('pickup'), 'TPL_PK', '取货提醒模板')


def test_need_login(ctx):
    """没登录调接口返回 401"""
    r = ctx.app('/home')
    eq(r.get('code'), 401, '没登录')


def test_home(ctx):
    """首页有进行中的团和商品"""
    h = ok(ctx.app('/home', ctx.cache['resident']))
    assert h['period'] and h['period']['status'] == 'live', h['period']
    assert len(h['goods']) >= 4, '商品太少'
    ctx.cache['goods'] = h['goods']


def test_order_create_cancel(ctx):
    """下单（待付款）→ 取消 → 已关闭"""
    tok = ctx.cache['resident']
    me = ok(ctx.app('/me', tok))
    pt = me['defaultPoint']['id']
    g = next(x for x in ctx.cache['goods'] if not x['limitQty'] and x['left'] > 5)
    o = ok(ctx.app('/order/create', tok, {'pointId': pt, 'items': [{'periodGoodsId': g['id'], 'qty': 2}], 'userCouponId': 'none', 'remark': 'e2e'}), '下单')
    eq(ctx.sql(f"select status from gb_order where id='{o['id']}'"), 'unpaid', '新订单')
    ok(ctx.app('/order/cancel', tok, {'id': o['id']}), '取消')
    eq(ctx.sql(f"select status from gb_order where id='{o['id']}'"), 'closed', '取消后')
    err(ctx.app('/order/cancel', tok, {'id': o['id']}), '', '再取消一次')
