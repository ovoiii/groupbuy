# 邻里鲜 · 社区团购

PC 运营后台 + 微信小程序（居民下单 / 团长核销）+ 后端接口。基于 JeecgBoot 3.9.6。

```
D:\GroupBuy
├── _design/   原型（确认过的界面设计，双击 index.html 看）
├── server/    后端：JeecgBoot + 团购模块 jeecg-boot-module/jeecg-module-groupbuy
├── admin/     PC 运营后台：Vue 3 + Ant Design Vue，团购页面在 src/views/groupbuy/
├── miniapp/   微信小程序：uni-app，页面在 src/pages/，接口和购物车在 src/gb/
└── data/      本地上传文件（商品图、售后照片），不进 git
```

## 本地启动（三个都要起）

需要：JDK 17+、MySQL 8（root/root）、Redis、Node 18+、pnpm。

**1. 数据库**（只做一次）：建库 `groupbuy`，导入 JeecgBoot 基础表（去掉文件开头两行的建库和 USE 语句）：

```bash
mysql -uroot -proot -e "CREATE DATABASE groupbuy DEFAULT CHARSET utf8mb4"
sed '1,2d' server/db/jeecgboot-mysql-5.7.sql | mysql -uroot -proot --default-character-set=utf8mb4 groupbuy
```

团购的表和后台菜单由 Flyway 在后端第一次启动时自动建（`V3.9.6_100__groupbuy_init.sql`、`V3.9.6_101__groupbuy_menu_fix.sql`）。
库里没有团购数据时，后端会自动写入演示数据（14 个商品、6 个提货点、前后 5 期团、约 700 单），时间按「今天」推算。

**2. 后端**（端口 8080）：先启动 Redis，然后

```bash
cd server
mvn install -DskipTests -pl jeecg-module-system/jeecg-system-start -am     # 第一次需要完整编译
java -jar jeecg-module-system/jeecg-system-start/target/jeecg-system-start-3.9.6.jar
```

**3. 后台**（端口 3200）：`cd admin && pnpm install && pnpm dev`，打开 http://localhost:3200 ，账号 admin / 123456。

**4. 小程序**：`cd miniapp && pnpm install`

- 浏览器预览：`pnpm dev:h5`，打开 http://localhost:9000
- 微信开发者工具：`pnpm dev`（或 `pnpm build` 出正式包），导入 `miniapp/dist/dev/mp-weixin`；
  本地调试要在开发者工具里勾选「不校验合法域名」。真机预览要把 `miniapp/env/.env.development` 里的
  `VITE_SERVER_BASEURL` 改成电脑的局域网 IP。
- 开发登录用手机号：居民 `13800001029`，团长 `13800002716`（王桂芳，幸福里 3 号楼便利店）。

## 业务规则

1. **每日一期**：前一晚 23:00 开团 → 当晚 23:00 截单 → 次日 16:00 起自提 → 再次日 21:00 提货截止。后台建团期时可以改。
   定时任务（每 30 秒，`GbScheduler`）自动开团、截单、开始自提、结束。
2. 截单时：未付款的关闭，已付款的转「配送中」，按提货点生成配送单；团长签收后这个点的订单变「待提货」。
3. **截单前**居民可以自己取消，原路退款；截单后只能申请售后。待付款 15 分钟不付自动关闭。
4. **库存**按团期单独设，下单时并发安全地扣（`occupy` 一条 SQL）；**限购**按每人每期算。
5. **冷藏 / 冷冻商品**只能选有冰柜的提货点。
6. **优惠**：满减券、折扣券、新人 1 元购；结算自动选最划算的一张，一单只能用一张。取消订单退回券。
7. **提货码** 6 位，同一期内不重复；团长核销时必须逐件点货。
8. **售后**：到货后可申请，提货后 24 小时内有效；可退金额 = 所选商品金额 × 实付 / 商品金额。
   平台 24 小时内不处理自动同意。审核时选责任方（供应商 / 团长 / 平台 / 物流）用于对账。
9. **团长佣金**：每个提货点单独设比例（默认 10%，后台「佣金结算」或「团长管理 → 编辑」里改，0–30%）。
   佣金 = 商品金额 × (实付 − 已退) ÷ 实付 × 比例：优惠券由平台出，不扣团长的；退过款的按没退的部分算。
   比例在居民付款时记到订单上，改比例只影响之后付款的订单。
   顾客提货满 24 小时（售后期）且没有进行中的售后，定时任务自动入账到团长的可提现余额；没提货的订单没有佣金。
10. **团长提现**：小程序「团长工作台 → 我的佣金 → 提现」，提到微信零钱或银行卡，单笔最少 10 元（`withdraw-min`），
    同时只能一笔在审核。后台「提现审核」：运营线下打完款后登记流水号；驳回时钱退回可提现余额。
11. **团长入驻**：居民在小程序「我的 → 申请当团长」填提货点名称、地图位置、地址、户数、营业时间、冰柜、姓名手机号。
    一个账号同时只能有一份待审核；同一手机号不能重复登记。后台「团长管理 → 待审核」自动检查 1.5 公里内有没有别的提货点、有没有冰柜。
    被拒后能看到原因，改了重新提交（覆盖原申请）；通过后用申请时的账号登录就能进团长工作台，下一期团起居民能选这个点。
12. **订阅消息**：6 个场景——到货通知、取货提醒、售后结果（居民），发车通知、提现结果（团长），入驻审核结果（申请人）。
    微信是一次性订阅：用户在付款、申请售后、提交入驻、提现、签收到货时会弹订阅框，点一次「允许」只能收一条。
    没配 AppID 或模板时只记录不发送，后台「订单履约 → 消息通知」能看到每条的内容和没发的原因。
13. **地图导航**：订单详情点提货点旁的定位图标、选提货点页点「导航」，打开微信地图，可以再跳高德 / 百度 / 腾讯地图；
    提货点没标位置时复制地址。

配置在 `server/.../application-dev.yml` 末尾的 `groupbuy:` 段。

## 上线前必须做的

| 项 | 现在 | 要做什么 |
|---|---|---|
| 微信支付 | 模拟支付（`pay-mock: true`，点了就成功） | 申请商户号，接统一下单 + 支付回调，回调里调 `GbCoreService.markPaid`；退款同理（代码里标了「模拟退款」的位置） |
| 定位权限 | `manifest.config.ts` 已声明 getLocation / chooseLocation | 在公众平台「用户隐私保护指引」里勾选「位置信息」，否则真机调不了定位和地图选点 |
| 微信登录 | 手机号开发登录（`dev-login: true`） | 填 `groupbuy.wx.appid / secret`，关掉 `dev-login`；手机号授权需要再接 `getPhoneNumber` |
| 订阅消息 | 只记录不发送 | 公众平台「订阅消息」里选用 6 个模板，把模板 ID 和字段对应填到 `groupbuy.notify.scenes`（yml 里有示例和说明）；只有用微信登录的用户才收得到 |
| 登录密钥 | 开发用的固定值 | `groupbuy.token-secret` 改成随机长字符串 |
| 演示数据 | 空库自动写入 | `groupbuy.demo-seed: false` |
| 团长提现打款 | 运营线下转账，后台登记流水号 | 想自动打款就开通微信支付「商家转账到零钱」，在 `GbCommissionService.approve` 里调转账接口 |
| 多实例部署 | 定时任务单机跑 | 加分布式锁，或改成 JeecgBoot 的 Quartz 集群任务 |
| 商品图 | 示意图 | 后台商品库里上传实拍图 |

## 这一版没做

团长责任售后从佣金里扣款；订阅消息发送失败后自动重发。
