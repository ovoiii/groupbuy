"""团长提现自动打款（微信支付商家转账，假微信支付）：等团长确认收款、到账、失败重打、撤销、结果未知时查询"""
import threading
from lib import eq, ok, err

PAY_NOTIFY = '/gb/app/pay/transfer-notify'


def setup(ctx):
    ctx.pay('/__reset')
    # 找一个没有在途提现的团长（不用演示团长 13800002716，那个在扣款测试里用）
    row = ctx.row("select p.id, p.leader_phone, p.leader_name from gb_point p where p.status='ok' and p.leader_phone <> '13800002716' "
                  "and not exists (select 1 from gb_withdraw w where w.point_id=p.id and w.status in ('wait','paying')) order by p.create_time limit 1")
    assert row, '找不到合适的团长'
    pt, phone, name = row
    ctx.sql(f"insert ignore into gb_leader_account (point_id, balance, frozen, total_income, total_withdrawn, total_deducted, update_time) values ('{pt}', 0, 0, 0, 0, 0, now())")
    ctx.sql(f"update gb_leader_account set balance = balance + 100 where point_id='{pt}'")
    # 团长用微信登录一次，绑上 openid（打到零钱要用）
    r = ok(ctx.app('/login', data={'code': f'c_oLeader{phone}', 'phoneCode': f'p_{phone}'}), '团长微信登录')
    ctx.cache.update(t_pt=pt, t_name=name, t_tok=r['token'], t_openid=f'oLeader{phone}')


def acc(ctx):
    r = ctx.row(f"select balance, frozen, total_withdrawn from gb_leader_account where point_id='{ctx.cache['t_pt']}'")
    return tuple(round(float(x), 2) for x in r)


def r2(*v):
    return tuple(round(x, 2) for x in v)


def wd(ctx, wid):
    r = ctx.row(f"select status, transfer_no, transfer_state, ifnull(transfer_fail,''), ifnull(voucher,'') from gb_withdraw where id='{wid}'")
    return dict(zip(['status', 'no', 'state', 'fail', 'voucher'], r))


def clean(ctx):
    """上一个用例失败时可能留下在途的提现：直接作废（钱退回余额），免得后面的用例跟着失败"""
    pt = ctx.cache['t_pt']
    for wid, amt in [r.split('\t') for r in ctx.sql(f"select id, amount from gb_withdraw where point_id='{pt}' and status in ('wait','paying')").splitlines()]:
        ctx.sql(f"update gb_withdraw set status='rej', reply='e2e 清理' where id='{wid}'")
        ctx.sql(f"update gb_leader_account set frozen = frozen - {amt}, balance = balance + {amt} where point_id='{pt}'")


def apply(ctx, amount='2.00', method='wechat'):
    clean(ctx)
    body = {'amount': amount, 'method': method, 'accountName': ctx.cache['t_name']}
    if method == 'bank':
        body.update(accountNo='6222020200112233445', bankName='工商银行')
    return ok(ctx.app('/leader/withdraw', ctx.cache['t_tok'], body), '申请提现')['id']


def finished(plain_state, ctx, no, reason=None):
    b = ctx.bill(no)
    p = {'mch_id': '1900000001', 'out_bill_no': no, 'transfer_bill_no': b['transfer_bill_no'], 'state': plain_state, 'transfer_amount': b['transfer_amount'],
         'openid': b['openid'], 'create_time': b['create_time'], 'update_time': b['update_time']}
    if reason:
        p['fail_reason'] = reason
    return ctx.signed_notify(PAY_NOTIFY, 'mch_payment', 'MCHTRANSFER.BILL.FINISHED', p)


def test_wait_confirm_then_paid(ctx):
    """打款 → 等团长确认收款 → 确认后查询到账：冻结金额扣掉、累计提现增加、发提现结果通知"""
    clean(ctx)
    a0 = acc(ctx)
    wid = apply(ctx)
    ctx.cache['t_w1'] = wid
    eq(acc(ctx)[1], r2(a0[1] + 2)[0], '申请后冻结')
    row = next(x for x in ok(ctx.admin('/withdraw/list?status=wait'))['records'] if x['w']['id'] == wid)
    eq(row['autoPay'], True, '后台显示可以自动打款')
    r = ctx.admin('/withdraw/transfer', {'id': wid})
    assert r['success'] and '确认收款' in r['message'], r
    w = wd(ctx, wid)
    eq((w['status'], w['state']), ('paying', 'WAIT_USER_CONFIRM'), '提现状态')
    wd_no = ctx.sql(f"select wd_no from gb_withdraw where id='{wid}'")
    eq(w['no'], wd_no, '商户单号 = 提现单号')
    b = ctx.bill(wd_no)
    eq(b['transfer_amount'], 200, '金额（分）')
    eq(b['user_name'], ctx.cache['t_name'], '收款人姓名（加密传给微信，解密后一致）')
    eq(b['openid'], ctx.cache['t_openid'], '收款 openid')
    eq(b['transfer_scene_id'], '1005', '转账场景')
    eq(b['user_recv_perception'], '劳务报酬', '收款感知')
    eq(b['report_infos'], 2, '场景报备信息条数')
    eq(b['serial_header'], 'PUB_KEY_ID_TEST', '加密字段要带 Wechatpay-Serial')
    assert b['notify_url'].endswith('/gb/app/pay/transfer-notify'), b['notify_url']
    # 团长端
    inc = ok(ctx.app('/leader/income', ctx.cache['t_tok']))
    eq(inc['pending']['needConfirm'], True, '团长看到「确认收款」')
    p = ok(ctx.app(f'/leader/withdraw/confirm?id={wid}', ctx.cache['t_tok']))
    eq(p, {'mchId': '1900000001', 'appId': 'wxe2e0000000000001', 'package': b['package_info']}, '确认收款参数')
    other = ctx.dev_login('13800002716')
    err(ctx.app(f'/leader/withdraw/confirm?id={wid}', other), '不存在', '别的团长拿确认参数')
    err(ctx.app('/leader/withdraw', ctx.cache['t_tok'], {'amount': '1', 'method': 'wechat', 'accountName': ctx.cache['t_name']}), '还有一笔', '打款中再申请')
    err(ctx.admin('/withdraw/reject', {'id': wid, 'reply': 'x'}), '处理过', '打款中驳回')
    # 团长确认收款（假微信支付把单子改成成功），小程序回来查一下
    ctx.pay(f'/__state?no={wd_no}&state=SUCCESS')
    v = ok(ctx.app('/leader/withdraw/check', ctx.cache['t_tok'], {'id': wid}))
    eq(v['status'], 'paid', '查询后')
    w = wd(ctx, wid)
    assert w['voucher'].startswith('微信转账 '), w
    eq(acc(ctx), r2(a0[0] - 2, a0[1], a0[2] + 2), '余额 / 冻结 / 累计提现')
    ctx.wait(lambda: ctx.sql(f"select count(*) from gb_notify_log where scene='withdraw' and biz_id='{wid}'") != '0', 20, what='提现结果通知')


def test_success_by_notify(ctx):
    """转账结果通知到账；重复通知只结算一次；签名不对返回 401"""
    clean(ctx)
    a0 = acc(ctx)
    wid = apply(ctx, '3.00')
    ok(ctx.admin('/withdraw/transfer', {'id': wid}))
    no = wd(ctx, wid)['no']
    ctx.pay(f'/__state?no={no}&state=SUCCESS')
    b = ctx.bill(no)
    bad = ctx.signed_notify(PAY_NOTIFY, 'mch_payment', 'MCHTRANSFER.BILL.FINISHED',
                            {'out_bill_no': no, 'state': 'SUCCESS', 'transfer_amount': b['transfer_amount']}, bad=True)
    eq(bad, 401, '坏签名')
    eq(wd(ctx, wid)['status'], 'paying', '坏签名不处理')
    eq(finished('SUCCESS', ctx, no), 200, '通知')
    eq(wd(ctx, wid)['status'], 'paid', '通知后')
    eq(finished('SUCCESS', ctx, no), 200, '重复通知')
    eq(acc(ctx), r2(a0[0] - 3, a0[1], a0[2] + 3), '只扣一次')


def test_rejected_then_retry_then_fail(ctx):
    """微信拒绝（余额不足）→ 回到待审核；重打换新单号；转账失败通知 → 回到待审核并写明原因；最后线下打款登记"""
    clean(ctx)
    wid = apply(ctx, '2.00')
    ctx.pay_mode('reject')
    try:
        r = ctx.admin('/withdraw/transfer', {'id': wid})
        assert not r['success'] and '余额不足' in r['message'], r
    finally:
        ctx.pay_mode('ok')
    w = wd(ctx, wid)
    eq(w['status'], 'wait', '拒绝后')
    assert '余额不足' in w['fail'], w
    ok(ctx.admin('/withdraw/transfer', {'id': wid}), '重打')
    w = wd(ctx, wid)
    wd_no = ctx.sql(f"select wd_no from gb_withdraw where id='{wid}'")
    eq(w['no'], wd_no + 'R2', '重打的商户单号')
    eq(ok(ctx.app('/leader/income', ctx.cache['t_tok']))['pending']['transferFail'], None, '打款中不显示上次失败')
    ctx.pay(f"/__state?no={w['no']}&state=FAIL&reason=NAME_NOT_CORRECT")
    eq(finished('FAIL', ctx, w['no'], 'NAME_NOT_CORRECT'), 200, '失败通知')
    w = wd(ctx, wid)
    eq(w['status'], 'wait', '失败后')
    assert '姓名' in w['fail'], w
    eq(ok(ctx.app('/leader/income', ctx.cache['t_tok']))['pending']['transferFail'], w['fail'], '团长看到失败原因')
    a0 = acc(ctx)
    ok(ctx.admin('/withdraw/approve', {'id': wid, 'voucher': 'BANK20261121001'}), '线下打款登记')
    eq(acc(ctx), r2(a0[0], a0[1] - 2, a0[2] + 2), '登记后')


def test_cancel(ctx):
    """团长一直不确认：撤销转账 → 撤销完成 → 回到待审核 → 驳回，钱退回可提现"""
    clean(ctx)
    a0 = acc(ctx)
    wid = apply(ctx, '2.00')
    ok(ctx.admin('/withdraw/transfer', {'id': wid}))
    no = wd(ctx, wid)['no']
    r = ok(ctx.admin('/withdraw/transfer/cancel', {'id': wid}), '撤销')
    eq((r['status'], r['transferState']), ('paying', 'CANCELING'), '撤销中')
    err(ctx.admin('/withdraw/transfer/cancel', {'id': wid}), '撤销不了', '撤销中再撤销')
    ctx.pay(f'/__state?no={no}&state=CANCELLED')
    r = ok(ctx.admin('/withdraw/transfer/sync', {'id': wid}), '刷新结果')
    eq(r['status'], 'wait', '撤销完成后')
    assert '撤销' in wd(ctx, wid)['fail']
    ok(ctx.admin('/withdraw/reject', {'id': wid, 'reply': '团长说先不提了'}))
    eq(acc(ctx), a0, '驳回后余额复原')


def test_unknown_then_query(ctx):
    """发起时断网：先记「结果未知」，查询后知道微信建了单；微信 500：查不到就回到待审核"""
    clean(ctx)
    wid = apply(ctx, '2.00')
    ctx.pay_mode('drop')
    try:
        ok(ctx.admin('/withdraw/transfer', {'id': wid}))
    finally:
        ctx.pay_mode('ok')
    eq(wd(ctx, wid)['state'], 'UNKNOWN', '断网后')
    r = ok(ctx.admin('/withdraw/transfer/sync', {'id': wid}))
    eq((r['status'], r['transferState']), ('paying', 'WAIT_USER_CONFIRM'), '查询后')
    ok(ctx.admin('/withdraw/transfer/cancel', {'id': wid}))
    ctx.pay(f"/__state?no={wd(ctx, wid)['no']}&state=CANCELLED")
    ok(ctx.admin('/withdraw/transfer/sync', {'id': wid}))
    eq(wd(ctx, wid)['status'], 'wait', '撤销完成')

    ctx.pay_mode('error')
    try:
        ok(ctx.admin('/withdraw/transfer', {'id': wid}))
    finally:
        ctx.pay_mode('ok')
    eq(wd(ctx, wid)['state'], 'UNKNOWN', '微信 500 后')
    ok(ctx.admin('/withdraw/transfer/sync', {'id': wid}))
    w = wd(ctx, wid)
    eq(w['status'], 'wait', '微信没有这笔转账')
    assert '没有这笔转账' in w['fail'], w
    ok(ctx.admin('/withdraw/reject', {'id': wid, 'reply': 'e2e'}))


def test_concurrent_click(ctx):
    """两个人同时点「打款」：只发起一笔"""
    clean(ctx)
    wid = apply(ctx, '2.00')
    n0 = len(ctx.bills())
    res = []
    ts = [threading.Thread(target=lambda: res.append(ctx.admin('/withdraw/transfer', {'id': wid}))) for _ in range(3)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    eq(sum(1 for r in res if r['success']), 1, '成功的请求数')
    eq(len(ctx.bills()) - n0, 1, '微信收到的转账单数')
    ok(ctx.admin('/withdraw/transfer/cancel', {'id': wid}))
    ctx.pay(f"/__state?no={wd(ctx, wid)['no']}&state=CANCELLED")
    ok(ctx.admin('/withdraw/transfer/sync', {'id': wid}))
    ok(ctx.admin('/withdraw/reject', {'id': wid, 'reply': 'e2e'}))


def test_bank_not_auto(ctx):
    """提到银行卡的不能自动打款"""
    clean(ctx)
    wid = apply(ctx, '2.00', 'bank')
    row = next(x for x in ok(ctx.admin('/withdraw/list?status=wait'))['records'] if x['w']['id'] == wid)
    eq(row['autoPay'], False, '银行卡')
    assert '银行卡' in row['autoPayWhy']
    err(ctx.admin('/withdraw/transfer', {'id': wid}), '银行卡', '强行打款')
    ok(ctx.admin('/withdraw/reject', {'id': wid, 'reply': 'e2e'}))


def test_no_openid_not_auto(ctx):
    """团长没用微信登录过（没有 openid）不能自动打款"""
    clean(ctx)
    pt, phone = ctx.row("select p.id, p.leader_phone from gb_point p where p.status='ok' and p.id <> '%s' and p.leader_phone <> '13800002716' "
                        "and not exists (select 1 from gb_withdraw w where w.point_id=p.id and w.status in ('wait','paying')) limit 1" % ctx.cache['t_pt'])
    ctx.sql(f"insert ignore into gb_leader_account (point_id, balance, frozen, total_income, total_withdrawn, total_deducted, update_time) values ('{pt}', 0, 0, 0, 0, 0, now())")
    ctx.sql(f"update gb_leader_account set balance = balance + 10 where point_id='{pt}'")
    tok = ctx.dev_login(phone)
    ctx.sql(f"update gb_member set openid = null where phone='{phone}'")
    wid = ok(ctx.app('/leader/withdraw', tok, {'amount': '2', 'method': 'wechat', 'accountName': '某团长'}))['id']
    err(ctx.admin('/withdraw/transfer', {'id': wid}), '没用微信登录', '没有 openid')
    ok(ctx.admin('/withdraw/reject', {'id': wid, 'reply': 'e2e'}))
