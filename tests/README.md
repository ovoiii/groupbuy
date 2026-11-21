# 自动化测试

三层，改完代码跑一遍：

| 层 | 测什么 | 怎么跑 | 用时 |
|---|---|---|---|
| 后端单测（JUnit） | 不碰数据库的规则：佣金计算、订阅消息字段截断、登录凭证、金额换算、验证码摘要、海报 scene 编解码、海报画图 | `cd server && mvn test -DskipTests=false -pl jeecg-boot-module/jeecg-module-groupbuy` | 几秒 |
| 小程序单测（node） | `miniapp/src/gb/scene.ts`：扫海报小程序码进来时解析商品和提货点 | `node --test tests/unit/scene.test.mjs`（Node 22.6+） | 1 秒 |
| 端到端（Python） | 起真后端，按接口把业务流程走一遍：登录 / 短信 / 海报 / 支付通知 / 扣佣金 / 提现自动打款 / 订阅消息重发 | `python -X utf8 tests/run.py` | 约 5 分钟 |

`tests/run.py` 会先跑小程序单测，再跑端到端测试。

## 端到端测试

需要：后端已经打好 jar（`server/jeecg-module-system/jeecg-system-start/target/*.jar`）、MySQL（root/root）、Redis 在 6379、JDK 17+、Python 3.10+（只用标准库）。

```bash
python -X utf8 tests/run.py              # 全部
python -X utf8 tests/run.py -k transfer  # 只跑名字里带 transfer 的
python -X utf8 tests/run.py --keep       # 跑完不关后端和假服务，可以接着手动点
```

它会：

1. **新建独立的库 `groupbuy_e2e`**（每次删了重建，导入 JeecgBoot 基础表；团购表和演示数据由后端启动时建），不碰开发用的 `groupbuy` 库。Redis 用 7 号库。
2. **启动假服务**（都在本机，不访问任何外部服务）：
   - `fakes/fake_wx.py`（18080）：微信小程序服务端接口（登录、手机号、订阅消息、小程序码）+ 阿里云短信接口。
   - `fakes/WxPayFake.java`（18090）：微信支付商家转账接口，按 API v3 规则给响应签名；也负责生成加密、签名好的支付 / 转账结果通知。测试密钥运行时生成在 `tests/.work/keys`（不进 git）。
3. **用测试配置启动后端**（8180 端口，真实支付模式、阿里云短信、配了订阅消息模板），日志在 `tests/.work/backend.log`。
4. 逐个跑 `e2e/test_*.py` 里的 `test_` 函数，最后打印通过 / 失败汇总；有失败时退出码是 1。

可以用环境变量改：`GB_JAVA`（java 程序路径）、`GB_MYSQL` / `GB_MYSQL_HOST` / `GB_MYSQL_USER` / `GB_MYSQL_PASSWORD`、`GB_E2E_DB`、`GB_JVM_ARGS`。

### 写新用例

在 `e2e/` 下的 `test_*.py` 里加 `def test_xxx(ctx):`，文档字符串写一句这条测什么（会打印出来）。`ctx` 提供：

- `ctx.app(path, token, data)` 调小程序接口（data 为 None 时发 GET），`ctx.admin(path, data)` 调后台接口（自动登录 admin）
- `ctx.dev_login(手机号)` 拿小程序登录凭证；`ctx.sql(...)` / `ctx.row(...)` 查测试库
- `ctx.wx_mode(notify='busy')`、`ctx.wx_calls('sms')` 控制 / 查看假微信；`ctx.pay_mode('reject')`、`ctx.bills()` 控制 / 查看假微信支付
- `ctx.signed_notify(路径, 类型, 事件, 明文)` 发一条签好名的微信支付通知
- `ctx.wait(fn, 秒数)` 等条件满足（等定时任务用）

`lib.py` 里的 `ok / err / eq` 用来断言接口成功、失败（提示包含某段文字）、相等。

### 没覆盖到的

- 小程序页面本身（按钮、跳转）：要微信开发者工具，没有自动化；H5 版可以用浏览器手动看。
- 向微信下单（prepay）、退款提交、关单、查单：官方 SDK 的域名写死成 api.mch.weixin.qq.com，测试里不调，避免访问外网。支付结果和退款结果走的是通知，通知这一段测了。
- 真实微信、真实短信：要商户号、AppID、阿里云账号，上线前用真机走一遍。
