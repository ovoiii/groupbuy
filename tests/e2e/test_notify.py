"""订阅消息发送失败自动重发（假微信切换 繁忙 / 断网 / 参数错）。要等定时任务（30 秒一轮），这组最慢"""
from lib import eq, ok, err

RESIDENT = '13800001029'
TPL = 'TPL_PK'


def setup(ctx):
    ctx.wx('/__reset')
    mid = ctx.sql(f"select id from gb_member where phone='{RESIDENT}'")
    ctx.sql(f"update gb_member set openid='oE2eNotify' where id='{mid}'")
    tok = ctx.dev_login(RESIDENT)
    ok(ctx.app('/notify/subscribe', tok, {'ids': [TPL]}))
    ctx.sql(f"update gb_notify_sub set quota=5 where member_id='{mid}' and template_id='{TPL}'")
    # 演示数据按当前时间生成，上午可能还没有「待提货」的单：挑一个改成已到货（后台「提醒取货」只对待提货的单）
    oid = ctx.sql(f"select id from gb_order where member_id='{mid}' and status in ('arrived','done','shipping','paid') order by status='arrived' desc limit 1")
    assert oid, '居民没有订单'
    ctx.sql(f"update gb_order set status='arrived', pickup_at=null where id='{oid}'")
    ctx.cache.update(n_mid=mid, n_oid=oid)


def quota(ctx):
    return int(ctx.sql(f"select quota from gb_notify_sub where member_id='{ctx.cache['n_mid']}' and template_id='{TPL}'"))


def row(ctx, i):
    r = ctx.row(f"select status, attempts, ifnull(note,'') from gb_notify_log where id='{i}'")
    return r[0], int(r[1]), r[2]


def remind(ctx):
    """后台点「提醒取货」，返回这次的发送记录 id"""
    before = ctx.sql(f"select count(*) from gb_notify_log where biz_id='{ctx.cache['n_oid']}' and scene='pickup'")
    ok(ctx.admin('/order/remind', {'id': ctx.cache['n_oid']}))
    ctx.wait(lambda: ctx.sql(f"select count(*) from gb_notify_log where biz_id='{ctx.cache['n_oid']}' and scene='pickup'") != before, 15, step=0.3, what='发送记录')
    return ctx.sql(f"select id from gb_notify_log where biz_id='{ctx.cache['n_oid']}' and scene='pickup' order by create_time desc, id desc limit 1")


def due(ctx, i):
    """把下次重发时间提前到现在，等定时任务处理"""
    before = row(ctx, i)
    ctx.sql(f"update gb_notify_log set next_retry_time = now() - interval 1 second where id='{i}'")
    # 重发了（次数变了）或者不发了（状态变了）
    ctx.wait(lambda: row(ctx, i) != before and row(ctx, i)[0] != 'retry' or row(ctx, i)[1] != before[1], 75, what='定时任务处理')


def test_busy_then_ok(ctx):
    """微信繁忙 → 等待重发（订阅次数先占着）→ 第 2 次发成功"""
    q0 = quota(ctx)
    ctx.wx_mode(notify='busy')
    i = remind(ctx)
    st, n, note = row(ctx, i)
    eq((st, n), ('retry', 1), '首发')
    assert '1 分钟后' in note, note
    eq(quota(ctx), q0 - 1, '次数占着')
    ctx.wx_mode(notify='ok')
    due(ctx, i)
    eq(row(ctx, i)[:2], ('sent', 2), '重发')
    eq(quota(ctx), q0 - 1, '只用了一次')


def test_down_gives_up_after_4(ctx):
    """一直连不上：1、5、30 分钟后各重发一次，第 4 次失败放弃并退回次数"""
    q0 = quota(ctx)
    ctx.wx_mode(notify='down')
    try:
        i = remind(ctx)
        eq(row(ctx, i)[:2], ('retry', 1), '第 1 次')
        for n, wait in ((2, '5 分钟后'), (3, '30 分钟后')):
            due(ctx, i)
            st, a, note = row(ctx, i)
            eq((st, a), ('retry', n), f'第 {n} 次')
            assert wait in note, note
        due(ctx, i)
        st, a, note = row(ctx, i)
        eq((st, a), ('failed', 4), '第 4 次')
        assert '发了 4 次' in note and note.count('调用微信接口出错') == 1, note
        eq(quota(ctx), q0, '次数退回')
    finally:
        ctx.wx_mode(notify='ok')
    ctx.cache['n_failed'] = i


def test_manual_resend(ctx):
    """后台手动重发失败的（再用一次次数）；已发送的不能重发"""
    i = ctx.cache['n_failed']
    q0 = quota(ctx)
    r = ok(ctx.admin('/notify/list?scene=pickup&status=failed'))
    assert any(x['l']['id'] == i and x['canResend'] for x in r['records']), '列表里能重发'
    r = ctx.admin('/notify/resend', {'id': i})
    assert r['success'] and r['message'] == '已发送', r
    eq(row(ctx, i)[0], 'sent', '状态')
    eq(quota(ctx), q0 - 1, '次数')
    err(ctx.admin('/notify/resend', {'id': i}), '不能重发', '已发送的')


def test_bad_request_no_retry(ctx):
    """参数不对（47003）重发也没用：直接失败、退回次数"""
    q0 = quota(ctx)
    ctx.wx_mode(notify='bad')
    try:
        i = remind(ctx)
    finally:
        ctx.wx_mode(notify='ok')
    st, n, note = row(ctx, i)
    eq((st, n), ('failed', 1), '状态')
    assert '47003' in note, note
    eq(quota(ctx), q0, '次数退回')
    ctx.sql(f"update gb_notify_sub set quota=0 where member_id='{ctx.cache['n_mid']}' and template_id='{TPL}'")
    err(ctx.admin('/notify/resend', {'id': i}), '没有订阅次数', '没次数手动重发')
    ctx.sql(f"update gb_notify_sub set quota=5 where member_id='{ctx.cache['n_mid']}' and template_id='{TPL}'")


def test_template_changed(ctx):
    """等待重发期间换了模板：不再发"""
    ctx.wx_mode(notify='busy')
    try:
        i = remind(ctx)
    finally:
        ctx.wx_mode(notify='ok')
    ctx.sql(f"update gb_notify_log set template_id='TPL_OLD' where id='{i}'")
    n = len(ctx.wx_calls('notify'))
    due(ctx, i)
    st, _, note = row(ctx, i)
    eq(st, 'failed', '状态')
    assert '模板已经换了' in note, note
    eq(len(ctx.wx_calls('notify')), n, '没有再调微信')
