"""微信支付结果通知：验签、到账、重复通知只处理一次（通知由假微信支付按 API v3 规则加密签名）"""
from lib import eq, ok

RESIDENT = '13800001029'


def setup(ctx):
    tok = ctx.dev_login(RESIDENT)
    ctx.cache['pay_tok'] = tok
    me = ok(ctx.app('/me', tok))
    goods = ok(ctx.app('/home', tok))['goods']
    g = next(x for x in goods if not x['limitQty'] and x['left'] > 5)
    o = ok(ctx.app('/order/create', tok, {'pointId': me['defaultPoint']['id'], 'items': [{'periodGoodsId': g['id'], 'qty': 1}], 'userCouponId': 'none', 'remark': 'e2e 支付'}))
    ctx.cache['pay_order'] = (o['id'], ctx.sql(f"select order_no from gb_order where id='{o['id']}'"), round(float(o['payAmount']) * 100))


def plain(no, fen, tx):
    return {'mchid': '1900000001', 'appid': 'wxe2e0000000000001', 'out_trade_no': no, 'transaction_id': tx, 'trade_type': 'JSAPI', 'trade_state': 'SUCCESS',
            'trade_state_desc': '支付成功', 'bank_type': 'OTHERS', 'success_time': '2026-11-21T10:00:00+08:00', 'payer': {'openid': 'oE2ePayer'},
            'amount': {'total': fen, 'payer_total': fen, 'currency': 'CNY', 'payer_currency': 'CNY'}}


def test_bad_signature(ctx):
    """签名不对的通知返回 401，订单不动"""
    oid, no, fen = ctx.cache['pay_order']
    st = ctx.signed_notify('/gb/app/pay/notify', 'transaction', 'TRANSACTION.SUCCESS', plain(no, fen, '4200000000202611210001'), bad=True)
    eq(st, 401, 'HTTP')
    eq(ctx.sql(f"select status from gb_order where id='{oid}'"), 'unpaid', '订单')


def test_paid_by_notify(ctx):
    """支付成功通知 → 订单已付款，记下微信支付单号"""
    oid, no, fen = ctx.cache['pay_order']
    eq(ctx.signed_notify('/gb/app/pay/notify', 'transaction', 'TRANSACTION.SUCCESS', plain(no, fen, '4200000000202611210001')), 200, 'HTTP')
    row = ctx.row(f"select status, pay_channel, transaction_id from gb_order where id='{oid}'")
    eq(row, ['paid', 'wx', '4200000000202611210001'], '订单')
    eq(ok(ctx.app('/order/pay/check', ctx.cache['pay_tok'], {'id': oid}))['paid'], True, '小程序查单')


def test_duplicate_notify(ctx):
    """同一条通知再来一次：返回成功，不重复处理"""
    oid, no, fen = ctx.cache['pay_order']
    before = ctx.row(f"select pay_time, pickup_code from gb_order where id='{oid}'")
    eq(ctx.signed_notify('/gb/app/pay/notify', 'transaction', 'TRANSACTION.SUCCESS', plain(no, fen, '4200000000202611210001')), 200, 'HTTP')
    eq(ctx.row(f"select pay_time, pickup_code from gb_order where id='{oid}'"), before, '付款时间和提货码不变')
