"""
邻里鲜自动化测试：一条命令跑完前端小工具单测 + 后端端到端测试。说明见 tests/README.md。

    python -X utf8 tests/run.py              # 全部
    python -X utf8 tests/run.py -k transfer  # 只跑名字里带 transfer 的端到端测试
    python -X utf8 tests/run.py --keep       # 跑完不关后端和假服务（方便看日志、手动点）

端到端测试会：新建一个独立的库 groupbuy_e2e（每次重建，不碰开发库），启动假微信 / 假微信支付 / 假短信，
用测试配置启动打好的后端 jar（端口 8180，Redis 用 7 号库），然后逐个跑 tests/e2e/test_*.py 里的 test_ 函数。
"""
import argparse
import importlib
import os
import shutil
import socket
import subprocess
import sys
import time
import traceback
import urllib.request as u

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HERE = os.path.join(ROOT, 'tests')
WORK = os.path.join(HERE, '.work')
sys.path.insert(0, os.path.join(HERE, 'e2e'))

MODULES = ['test_basic', 'test_login', 'test_poster', 'test_pay', 'test_deduct', 'test_transfer', 'test_notify']
V3_KEY = '0123456789abcdef0123456789abcdef'
PORT, WX_PORT, PAY_PORT = 8180, 18080, 18090
DB = os.environ.get('GB_E2E_DB', 'groupbuy_e2e')


def find_java():
    j = os.environ.get('GB_JAVA')
    if j:
        return j
    home = os.environ.get('JAVA_HOME')
    if home and os.path.exists(os.path.join(home, 'bin', 'java.exe' if os.name == 'nt' else 'java')):
        return os.path.join(home, 'bin', 'java')
    j = shutil.which('java')
    if not j:
        sys.exit('找不到 java：装 JDK 17+，或者用环境变量 GB_JAVA 指定 java 程序的路径')
    return j


def mysql_args():
    return [os.environ.get('GB_MYSQL', 'mysql'), '-h', os.environ.get('GB_MYSQL_HOST', '127.0.0.1'), '-u' + os.environ.get('GB_MYSQL_USER', 'root'),
            '-p' + os.environ.get('GB_MYSQL_PASSWORD', 'root')]


def port_open(p):
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(('127.0.0.1', p)) == 0


def wait_port(p, name, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        if port_open(p):
            return
        time.sleep(0.3)
    sys.exit(f'{name}（端口 {p}）没起来')


def step(msg):
    print(f'\n== {msg}', flush=True)


# ---------------------------------------------------------------- 单元测试（不用起后端）

def unit_tests():
    step('小程序小工具单测（node）')
    node = shutil.which('node')
    if not node:
        print('  跳过：没装 node')
        return True
    files = sorted(os.path.join(HERE, 'unit', f) for f in os.listdir(os.path.join(HERE, 'unit')) if f.endswith('.test.mjs'))
    r = subprocess.run([node, '--no-warnings', '--test', *files], cwd=ROOT)
    return r.returncode == 0


# ---------------------------------------------------------------- 准备环境

def reset_db():
    step(f'重建测试库 {DB}（导入 JeecgBoot 基础表，团购的表和演示数据由后端启动时建）')
    args = mysql_args()
    r = subprocess.run(args + ['-e', f'DROP DATABASE IF EXISTS {DB}; CREATE DATABASE {DB} DEFAULT CHARSET utf8mb4'], capture_output=True)
    if r.returncode:
        sys.exit('连不上 MySQL：' + r.stderr.decode('utf8', 'replace'))
    with open(os.path.join(ROOT, 'server', 'db', 'jeecgboot-mysql-5.7.sql'), 'rb') as f:
        lines = f.read().split(b'\n', 2)
    r = subprocess.run(args + ['--default-character-set=utf8mb4', DB], input=lines[2], capture_output=True)
    if r.returncode:
        sys.exit('导入基础表失败：' + r.stderr.decode('utf8', 'replace')[-500:])


def start_fakes(java, procs):
    step('启动假微信（小程序接口 + 阿里云短信）、假微信支付')
    keys = os.path.join(WORK, 'keys')
    if not os.path.exists(os.path.join(keys, 'wx_private.pem')):
        subprocess.run([java, os.path.join(HERE, 'fakes', 'WxPayFake.java'), 'keys', keys], check=True)
    for p in (WX_PORT, PAY_PORT):
        if port_open(p):
            sys.exit(f'端口 {p} 被占用了，先关掉占用的程序')
    logf = open(os.path.join(WORK, 'fakes.log'), 'w', encoding='utf8')
    procs.append(subprocess.Popen([sys.executable, '-X', 'utf8', os.path.join(HERE, 'fakes', 'fake_wx.py'), str(WX_PORT)], stdout=logf, stderr=logf))
    procs.append(subprocess.Popen([java, os.path.join(HERE, 'fakes', 'WxPayFake.java'), 'serve', keys, str(PAY_PORT), V3_KEY], stdout=logf, stderr=logf))
    wait_port(WX_PORT, '假微信')
    wait_port(PAY_PORT, '假微信支付', 90)
    return keys


def start_backend(java, keys, procs):
    jar = os.path.join(ROOT, 'server', 'jeecg-module-system', 'jeecg-system-start', 'target', 'jeecg-system-start-3.9.6.jar')
    if not os.path.exists(jar):
        sys.exit('没找到后端 jar，先打包：cd server && mvn install -DskipTests -pl jeecg-module-system/jeecg-system-start -am')
    if port_open(PORT):
        sys.exit(f'端口 {PORT} 被占用了')
    if not port_open(6379):
        sys.exit('Redis 没启动（127.0.0.1:6379），先启动 Redis')
    step(f'启动后端（测试配置，端口 {PORT}），日志在 tests/.work/backend.log')
    url = (f'jdbc:mysql://127.0.0.1:3306/{DB}?characterEncoding=UTF-8&useUnicode=true&useSSL=false&tinyInt1isBit=false'
           '&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai')
    wx, pay = f'http://127.0.0.1:{WX_PORT}', f'http://127.0.0.1:{PAY_PORT}'
    props = {
        'server.port': PORT,
        'spring.datasource.dynamic.datasource.master.url': url,
        'spring.datasource.dynamic.datasource.master.username': os.environ.get('GB_MYSQL_USER', 'root'),
        'spring.datasource.dynamic.datasource.master.password': os.environ.get('GB_MYSQL_PASSWORD', 'root'),
        'spring.data.redis.database': 7,
        'jeecg.path.upload': os.path.join(ROOT, 'data', 'upload').replace('\\', '/'),
        'groupbuy.token-secret': 'e2e-token-secret',
        'groupbuy.dev-login': 'true',
        'groupbuy.pay-mock': 'false',
        'groupbuy.withdraw-min': 1,
        'groupbuy.wx.appid': 'wxe2e0000000000001',
        'groupbuy.wx.secret': 'e2e-secret',
        'groupbuy.wx.api-base': wx,
        'groupbuy.pay.mch-id': '1900000001',
        'groupbuy.pay.merchant-serial-number': 'E2E00000000000000000000000000001',
        'groupbuy.pay.private-key-path': os.path.join(keys, 'apiclient_key.pem').replace('\\', '/'),
        'groupbuy.pay.api-v3-key': V3_KEY,
        'groupbuy.pay.public-key-id': 'PUB_KEY_ID_TEST',
        'groupbuy.pay.public-key-path': os.path.join(keys, 'pub_key.pem').replace('\\', '/'),
        'groupbuy.pay.notify-base': 'https://e2e.example.com/jeecg-boot',
        'groupbuy.pay.api-base': pay,
        'groupbuy.sms.provider': 'aliyun',
        'groupbuy.sms.access-key-id': 'e2eAccessKey',
        'groupbuy.sms.access-key-secret': 'e2eAccessSecret',
        'groupbuy.sms.sign-name': 'LinLiXianTest',
        'groupbuy.sms.template-code': 'SMS_E2E_001',
        'groupbuy.sms.endpoint': wx,
        'groupbuy.notify.state': 'developer',
        'groupbuy.notify.scenes.pickup.template-id': 'TPL_PK',
        'groupbuy.notify.scenes.aftersale.template-id': 'TPL_AS',
        'groupbuy.notify.scenes.withdraw.template-id': 'TPL_WD',
    }
    log = open(os.path.join(WORK, 'backend.log'), 'w', encoding='utf8')
    jvm = os.environ.get('GB_JVM_ARGS', '-Xmx1g').split()
    procs.append(subprocess.Popen([java, *jvm, '-Dfile.encoding=UTF-8', '-jar', jar] + [f'--{k}={v}' for k, v in props.items()],
                                  cwd=os.path.join(ROOT, 'server'), stdout=log, stderr=subprocess.STDOUT))
    base = f'http://127.0.0.1:{PORT}/jeecg-boot'
    end = time.time() + 300
    while time.time() < end:
        if procs[-1].poll() is not None:
            sys.exit('后端启动失败，看 tests/.work/backend.log')
        try:
            u.urlopen(base + '/gb/app/config', timeout=3).read()
            break
        except Exception:
            time.sleep(2)
    else:
        sys.exit('后端 5 分钟没起来，看 tests/.work/backend.log')
    return base


def wait_seed(ctx):
    step('等演示数据写完')
    last, same = -1, 0
    end = time.time() + 180
    while time.time() < end:
        n = int(ctx.sql('select count(*) from gb_order') or 0)
        live = ctx.sql("select count(*) from gb_period where status='live'")
        same = same + 1 if n == last and n > 0 else 0
        if same >= 3 and live != '0':
            print(f'  {n} 单，进行中的团 {live} 个')
            return
        last = n
        time.sleep(2)
    sys.exit('演示数据没写好（gb_order 一直在变或为空）')


# ---------------------------------------------------------------- 跑测试

def run_e2e(ctx, keyword):
    results = []
    for name in MODULES:
        mod = importlib.import_module(name)
        tests = [(n, f) for n, f in vars(mod).items() if n.startswith('test_') and callable(f)]
        tests.sort(key=lambda t: t[1].__code__.co_firstlineno)
        if keyword:
            tests = [t for t in tests if keyword in f'{name}.{t[0]}']
        if not tests:
            continue
        step(f'{name}  {(mod.__doc__ or "").strip().splitlines()[0] if mod.__doc__ else ""}')
        if hasattr(mod, 'setup'):
            try:
                mod.setup(ctx)
            except Exception as e:
                traceback.print_exc()
                for n, _ in tests:
                    results.append((f'{name}.{n}', False, f'setup 出错：{e}'))
                continue
        for n, f in tests:
            t0 = time.time()
            try:
                f(ctx)
                results.append((f'{name}.{n}', True, ''))
                print(f'  ✓ {n}  ({time.time() - t0:.1f}s)  {(f.__doc__ or "").strip()}', flush=True)
            except AssertionError as e:
                results.append((f'{name}.{n}', False, str(e)))
                print(f'  ✗ {n}  {(f.__doc__ or "").strip()}\n      {e}', flush=True)
            except Exception as e:
                results.append((f'{name}.{n}', False, repr(e)))
                print(f'  ✗ {n}  出错了', flush=True)
                traceback.print_exc()
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-k', dest='keyword', help='只跑名字里带这个词的端到端测试')
    ap.add_argument('--keep', action='store_true', help='跑完不关后端和假服务')
    ap.add_argument('--unit-only', action='store_true', help='只跑单测')
    ap.add_argument('--no-reset', action='store_true', help='不重建测试库（接着上次的数据跑，快一点）')
    a = ap.parse_args()
    os.makedirs(WORK, exist_ok=True)
    unit_ok = unit_tests()
    if a.unit_only:
        sys.exit(0 if unit_ok else 1)

    java = find_java()
    procs = []
    try:
        if not a.no_reset:
            reset_db()
        keys = start_fakes(java, procs)
        base = start_backend(java, keys, procs)
        from lib import Ctx
        ctx = Ctx(base, DB, mysql_args(), f'http://127.0.0.1:{WX_PORT}', f'http://127.0.0.1:{PAY_PORT}', java, keys, V3_KEY)
        wait_seed(ctx)
        t0 = time.time()
        results = run_e2e(ctx, a.keyword)
        bad = [r for r in results if not r[1]]
        step(f'结果：单测{"通过" if unit_ok else "有失败"}；端到端 {len(results) - len(bad)} / {len(results)} 通过，用时 {time.time() - t0:.0f} 秒')
        for n, _, msg in bad:
            print(f'  ✗ {n}: {msg}')
        code = 0 if unit_ok and not bad else 1
        if a.keep:
            print(f'\n--keep：后端 {base}，假微信 :{WX_PORT}，假微信支付 :{PAY_PORT} 还开着，按回车结束')
            input()
        sys.exit(code)
    finally:
        for p in reversed(procs):
            if p.poll() is None:
                p.terminate()
        for p in procs:
            try:
                p.wait(10)
            except Exception:
                p.kill()


if __name__ == '__main__':
    main()
