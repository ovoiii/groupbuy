"""团长责任售后从佣金里扣款：扣款、余额不够记负数、负数不能提现、撤销"""
from lib import eq, ok, err

LEADER = '13800002716'


def setup(ctx):
    ctx.cache['d_pt'] = ctx.sql(f"select id from gb_point where leader_phone='{LEADER}' limit 1")
    # 演示数据按当前时间生成，上午跑时这个点可能还没有「待提货」的单：挑 3 个已付款的单改成已到货
    rows = ctx.sql(f"select o.id, m.phone from gb_order o join gb_member m on m.id=o.member_id where o.point_id='{ctx.cache['d_pt']}' "
                   "and o.status in ('arrived','done','shipping','paid') and o.after_status='none' and o.refund_amount=0 and ifnull(o.pay_channel,'mock')='mock' "
                   "and o.pay_amount>5 order by o.status='arrived' desc, o.create_time desc limit 3").splitlines()
    assert len(rows) == 3, '找不到 3 个能申请售后的订单'
    ctx.cache['d_orders'] = [r.split('\t') for r in rows]
    ids = "','".join(r[0] for r in ctx.cache['d_orders'])
    ctx.sql(f"update gb_order set status='arrived', pickup_at=null where id in ('{ids}')")


def acc(ctx):
    r = ctx.row(f"select balance, total_deducted from gb_leader_account where point_id='{ctx.cache['d_pt']}'")
    return float(r[0]), float(r[1])


def apply(ctx, i, typ):
    oid, phone = ctx.cache['d_orders'][i]
    tok = ctx.dev_login(phone)
    det = ok(ctx.app(f'/order/detail?id={oid}', tok))
    ok(ctx.app('/aftersale/apply', tok, {'orderId': oid, 'itemIds': [det['items'][0]['id']], 'type': typ, 'reason': 'e2e 扣款'}), '申请售后')
    return ctx.row(f"select id, apply_amount from gb_aftersale where order_id='{oid}' and status='wait'")


def test_charge_validation(ctx):
    """团长承担的钱不能超过退款金额；责任方要合法"""
    aid, cap = apply(ctx, 0, '少件')
    ctx.cache['d_a1'] = (aid, cap)
    err(ctx.admin('/aftersale/approve', {'id': aid, 'refundAmount': cap, 'duty': '团长', 'leaderCharge': str(float(cap) + 1), 'reply': 'x'}), '不能超过退款金额')
    err(ctx.admin('/aftersale/approve', {'id': aid, 'duty': '路人', 'reply': 'x'}), '责任方')


def test_charge_full(ctx):
    """责任方选团长、不填金额 = 全额从可提现扣"""
    aid, cap = ctx.cache['d_a1']
    b0, d0 = acc(ctx)
    ok(ctx.admin('/aftersale/approve', {'id': aid, 'refundAmount': cap, 'duty': '团长', 'reply': '已退款', 'chargeNote': '货放门口被拿走了'}))
    b1, d1 = acc(ctx)
    eq(round(b0 - b1, 2), float(cap), '可提现少了')
    eq(round(d1 - d0, 2), float(cap), '累计扣款多了')
    err(ctx.admin('/aftersale/approve', {'id': aid, 'duty': '团长', 'reply': 'x'}), '处理过', '再同意一次')
    tok = ctx.dev_login(LEADER)
    rec = ok(ctx.app('/leader/income/list?type=ded', tok))['records'][0]
    eq(rec['reason'], '货放门口被拿走了', '团长看到的说明')


def test_platform_duty_no_charge(ctx):
    """责任方是平台不扣团长"""
    aid, cap = apply(ctx, 1, '质量问题')
    b0, _ = acc(ctx)
    ok(ctx.admin('/aftersale/approve', {'id': aid, 'refundAmount': cap, 'duty': '平台', 'reply': '已退款'}))
    eq(acc(ctx)[0], b0, '可提现不变')
    eq(ctx.sql(f"select count(*) from gb_leader_deduct where aftersale_id='{aid}'"), '0', '扣款记录')


def test_negative_balance_and_revoke(ctx):
    """余额不够扣记负数，负数时提不了现；撤销后钱退回"""
    pt = ctx.cache['d_pt']
    keep = ctx.sql(f"select balance from gb_leader_account where point_id='{pt}'")
    pending = ctx.sql(f"select id from gb_withdraw where point_id='{pt}' and status in ('wait','paying') limit 1")
    if pending:
        ok(ctx.admin('/withdraw/reject', {'id': pending, 'reply': 'e2e 让出位置'}))
        keep = ctx.sql(f"select balance from gb_leader_account where point_id='{pt}'")
    ctx.sql(f"update gb_leader_account set balance = 1.00 where point_id='{pt}'")
    try:
        aid, cap = apply(ctx, 2, '破损')
        ok(ctx.admin('/aftersale/approve', {'id': aid, 'refundAmount': cap, 'duty': '团长', 'leaderCharge': cap, 'reply': '已退款'}))
        bal = acc(ctx)[0]
        eq(round(bal, 2), round(1 - float(cap), 2), '可提现成了负数')
        tok = ctx.dev_login(LEADER)
        err(ctx.app('/leader/withdraw', tok, {'amount': '1', 'method': 'wechat', 'accountName': '王桂芳'}), '余额不够', '负数时提现')
        did = ctx.sql(f"select id from gb_leader_deduct where aftersale_id='{aid}'")
        err(ctx.admin('/commission/deduct/revoke', {'id': did}), '原因', '不写原因')
        ok(ctx.admin('/commission/deduct/revoke', {'id': did, 'reason': '复核是物流压坏的'}))
        err(ctx.admin('/commission/deduct/revoke', {'id': did, 'reason': 'x'}), '撤销过', '再撤销')
        eq(acc(ctx)[0], 1.0, '钱退回')
    finally:
        ctx.sql(f"update gb_leader_account set balance = {keep} where point_id='{pt}'")
