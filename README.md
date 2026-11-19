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

配置在 `server/.../application-dev.yml` 末尾的 `groupbuy:` 段。

## 上线前必须做的

| 项 | 现在 | 要做什么 |
|---|---|---|
| 微信支付 | 模拟支付（`pay-mock: true`，点了就成功） | 申请商户号，接统一下单 + 支付回调，回调里调 `GbCoreService.markPaid`；退款同理（代码里标了「模拟退款」的位置） |
| 微信登录 | 手机号开发登录（`dev-login: true`） | 填 `groupbuy.wx.appid / secret`，关掉 `dev-login`；手机号授权需要再接 `getPhoneNumber` |
| 到货 / 取货通知 | 只打日志 | 接小程序订阅消息（`GbCoreService.sign` 和提醒取货处） |
| 登录密钥 | 开发用的固定值 | `groupbuy.token-secret` 改成随机长字符串 |
| 演示数据 | 空库自动写入 | `groupbuy.demo-seed: false` |
| 多实例部署 | 定时任务单机跑 | 加分布式锁，或改成 JeecgBoot 的 Quartz 集群任务 |
| 商品图 | 示意图 | 后台商品库里上传实拍图 |

## 这一版没做

团长佣金结算和提现（表里留了 `commission_rate` 字段）、小程序里的团长入驻申请表单（后台审核页已有）、订阅消息、地图导航。
