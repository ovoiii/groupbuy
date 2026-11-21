package org.jeecg.modules.groupbuy.service;

import com.google.gson.Gson;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.GbOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 不依赖数据库的业务规则：佣金计算、订阅消息字段整理、登录凭证、金额换算、验证码摘要、转账单解析 */
class GbRulesTest {

    // ---------------------------------------------------------------- 佣金

    private static GbOrder order(String goods, String pay, String refund) {
        GbOrder o = new GbOrder();
        o.setGoodsAmount(new BigDecimal(goods));
        o.setPayAmount(new BigDecimal(pay));
        o.setRefundAmount(new BigDecimal(refund));
        return o;
    }

    @Test
    void commissionBase() {
        // 没退款：按商品金额算（券是平台出的，不扣团长）
        assertEquals(new BigDecimal("100.00"), GbCommissionService.baseOf(order("100", "90", "0")));
        // 退了一半实付：商品金额也折一半
        assertEquals(new BigDecimal("50.00"), GbCommissionService.baseOf(order("100", "90", "45")));
        // 全退：没有佣金
        assertEquals(0, GbCommissionService.baseOf(order("100", "90", "90")).signum());
        // 0 元单
        assertEquals(0, GbCommissionService.baseOf(order("10", "0", "0")).signum());
        // 佣金向下取到分
        assertEquals(new BigDecimal("3.33"), GbCommissionService.commissionOf(new BigDecimal("33.39"), new BigDecimal("0.1")));
    }

    // ---------------------------------------------------------------- 订阅消息字段

    @Test
    void notifyFieldFormat() {
        // thing 最多 20 个字，超了加省略号
        String longText = "幸福里三号楼便利店门口左手边的冷柜第二层靠右边";
        String t = GbNotifyService.format("thing1", longText);
        assertEquals(20, t.codePointCount(0, t.length()));
        assertTrue(t.endsWith("…"));
        // phrase 最多 5 个字，不能有省略号
        assertEquals("平台取消，", GbNotifyService.format("phrase2", "平台取消，已退款"));
        // 金额
        assertEquals("¥12.50", GbNotifyService.format("amount3", new BigDecimal("12.5")));
        assertEquals("12.50元", GbNotifyService.format("thing9", new BigDecimal("12.5")));
        // 编号只留 ASCII
        assertEquals("261117512057", GbNotifyService.format("character_string1", "订单261117512057"));
        // 时间
        assertTrue(GbNotifyService.format("time4", new Date()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
        assertTrue(GbNotifyService.format("date4", new Date()).matches("\\d{4}-\\d{2}-\\d{2}"));
        assertEquals("", GbNotifyService.format("thing1", null));
    }

    @Test
    void notifyData() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("thing1", "point");
        fields.put("thing2", "固定文字");
        Map<String, Object> d = GbNotifyService.data(fields, Map.of("point", "幸福里"));
        assertEquals(Map.of("value", "幸福里"), d.get("thing1"));
        // 配的不是内容名就原样发
        assertEquals(Map.of("value", "固定文字"), d.get("thing2"));
    }

    @Test
    void notifyRetrySchedule() {
        assertArrayEquals(new int[]{1, 5, 30}, GbNotifyService.RETRY_MINUTES);
        assertTrue(GbNotifyService.TRANSIENT.contains(-1));
        assertFalse(GbNotifyService.TRANSIENT.contains(47003));
    }

    // ---------------------------------------------------------------- 登录凭证

    @Test
    void tokenAndTicketAreSeparate() {
        GbProperties p = new GbProperties();
        GbTokenService t = new GbTokenService(p);
        String token = t.issue("m1");
        assertEquals("m1", t.verify(token));
        assertNull(t.verify(token + "x"));
        assertNull(t.verify(token.replace("m1.", "m2.")));
        String ticket = t.issueTicket("oOpenid");
        assertEquals("oOpenid", t.verifyTicket(ticket));
        // 票据不能当登录凭证用，凭证也不能当票据用
        assertNull(t.verify(ticket));
        assertNull(t.verifyTicket(token));
        // 换了密钥，旧凭证失效
        p.setTokenSecret("another-secret");
        assertNull(t.verify(token));
    }

    // ---------------------------------------------------------------- 金额

    @Test
    void yuanToFen() {
        assertEquals(1990, GbPayService.fen(new BigDecimal("19.90")));
        assertEquals(1, GbPayService.fen(new BigDecimal("0.01")));
        assertEquals(10000, GbPayService.fen(new BigDecimal("100")));
        assertTrue(GbPayService.rfc3339(new Date()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+08:00"));
    }

    // ---------------------------------------------------------------- 短信验证码

    @Test
    void smsHash() {
        GbSmsService s = new GbSmsService(new GbProperties(), null);
        String h = s.hash("13800001029", "123456");
        assertEquals(64, h.length());
        assertEquals(h, s.hash("13800001029", "123456"));
        assertNotEquals(h, s.hash("13800001028", "123456"), "同一个验证码换个手机号摘要要不一样");
        assertNotEquals(h, s.hash("13800001029", "123457"));
        assertFalse(h.contains("123456"));
    }

    @Test
    void smsProvider() {
        GbProperties p = new GbProperties();
        GbSmsService s = new GbSmsService(p, null);
        assertTrue(s.enabled());
        assertEquals("mock", s.provider());
        p.getSms().setProvider("off");
        assertFalse(s.enabled());
        p.getSms().setProvider("aliyun");
        assertNotNull(s.missing());
        p.getSms().setAccessKeyId("k");
        p.getSms().setAccessKeySecret("s");
        p.getSms().setSignName("邻里鲜");
        p.getSms().setTemplateCode("SMS_1");
        assertNull(s.missing());
    }

    // ---------------------------------------------------------------- 商家转账

    @Test
    void transferBillJson() {
        String json = "{\"out_bill_no\":\"TX261121000001\",\"transfer_bill_no\":\"1330000071100999991182020050700019480001\",\"state\":\"WAIT_USER_CONFIRM\","
                + "\"package_info\":\"affffddafdfafddffda==\",\"transfer_amount\":2000,\"create_time\":\"2026-11-21T10:00:00+08:00\"}";
        GbTransferService.Bill b = new Gson().fromJson(json, GbTransferService.Bill.class);
        assertEquals("TX261121000001", b.getOutBillNo());
        assertEquals("WAIT_USER_CONFIRM", b.getState());
        assertEquals("affffddafdfafddffda==", b.getPackageInfo());
        assertEquals(2000, b.getTransferAmount());
    }

    @Test
    void transferFailReason() {
        assertEquals("收款人姓名和微信实名不一致", GbTransferService.reasonText("NAME_NOT_CORRECT"));
        assertEquals("SOMETHING_NEW", GbTransferService.reasonText("SOMETHING_NEW"));
        assertEquals("未知原因", GbTransferService.reasonText(null));
        assertTrue(GbTransferService.FINAL.containsAll(java.util.List.of("SUCCESS", "FAIL", "CANCELLED")));
    }
}
