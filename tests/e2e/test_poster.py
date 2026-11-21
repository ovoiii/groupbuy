"""分享海报：商品海报、本期团海报，小程序码 scene 带商品和提货点"""
from lib import eq, ok, err, png_size, b36

LEADER = '13800002716'


def setup(ctx):
    ctx.wx('/__reset')
    tok = ctx.dev_login(LEADER)
    ctx.cache['p_tok'] = tok
    me = ok(ctx.app('/me', tok))
    ctx.cache['p_pt'] = me['leaderPoint']['id']
    ctx.cache['p_goods'] = ok(ctx.app('/home', tok))['goods']


def poster(ctx, **q):
    from urllib.parse import urlencode
    return ctx.raw('/gb/app/poster?' + urlencode(q), headers={'X-Gb-Token': ctx.cache['p_tok']}, method='GET')


def test_goods_poster(ctx):
    """商品海报是 750×1290 的 PNG，小程序码 scene = g.<团品>.<提货点>（36 进制）"""
    g, pt = ctx.cache['p_goods'][0], ctx.cache['p_pt']
    st, body, h = poster(ctx, type='goods', id=g['id'], pt=pt)
    eq(st, 200, 'HTTP')
    assert 'image/png' in h.get('Content-Type', ''), h
    eq(png_size(body), (750, 1290), '尺寸')
    c = ctx.wx_calls('code')[-1]
    eq(c['scene'], f"g.{b36(g['id'])}.{b36(pt)}", 'scene')
    assert len(c['scene']) <= 32
    eq(c['page'], 'pages/goods/goods', '页面')
    eq(c['env_version'], 'develop', '版本（notify.state=developer）')
    eq(c['check_path'], False, 'check_path')


def test_scene_decode(ctx):
    """后端也能把 scene 解回商品和提货点"""
    g, pt = ctx.cache['p_goods'][0], ctx.cache['p_pt']
    r = ok(ctx.app(f"/scene?s=g.{b36(g['id'])}.{b36(pt)}"))
    eq(r, {'type': 'goods', 'id': g['id'], 'pt': pt}, '解析')
    err(ctx.app('/scene?s=zz'), '不对', '乱写的 scene')


def test_code_cached(ctx):
    """同一个码只向微信要一次"""
    g, pt = ctx.cache['p_goods'][0], ctx.cache['p_pt']
    n = len(ctx.wx_calls('code'))
    eq(poster(ctx, type='goods', id=g['id'], pt=pt)[0], 200, 'HTTP')
    eq(len(ctx.wx_calls('code')), n, '小程序码调用次数')


def test_home_poster(ctx):
    """本期团海报 750×1450（两行商品），scene = i.<提货点>，扫码进首页"""
    pt = ctx.cache['p_pt']
    st, body, _ = poster(ctx, type='home', pt=pt)
    eq(st, 200, 'HTTP')
    eq(png_size(body), (750, 1450), '尺寸')
    c = ctx.wx_calls('code')[-1]
    eq(c['scene'], f'i.{b36(pt)}', 'scene')
    eq(c['page'], 'pages/index/index', '页面')


def test_bad_point_ignored(ctx):
    """乱填的提货点不进海报（scene 里没有提货点）"""
    g = ctx.cache['p_goods'][1]
    st, body, _ = poster(ctx, type='goods', id=g['id'], pt='123')
    eq(st, 200, 'HTTP')
    eq(ctx.wx_calls('code')[-1]['scene'], f"g.{b36(g['id'])}", 'scene')


def test_code_fail_still_poster(ctx):
    """微信生成小程序码失败时，海报照样出（没有码）"""
    ctx.wx_mode(code='fail')
    try:
        g = ctx.cache['p_goods'][2]
        st, body, _ = poster(ctx, type='goods', id=g['id'], pt=ctx.cache['p_pt'])
        eq(st, 200, 'HTTP')
        eq(png_size(body), (750, 1290), '尺寸')
    finally:
        ctx.wx_mode(code='ok')


def test_poster_errors(ctx):
    """商品不存在返回 400；没登录不给图"""
    st, body, _ = poster(ctx, type='goods', id='1')
    eq(st, 400, '商品不存在')
    assert '商品不存在' in body.decode('utf8')
    st, body, _ = ctx.raw('/gb/app/poster?type=home', method='GET')
    assert not body.startswith(b'\x89PNG'), '没登录不能拿到海报'
