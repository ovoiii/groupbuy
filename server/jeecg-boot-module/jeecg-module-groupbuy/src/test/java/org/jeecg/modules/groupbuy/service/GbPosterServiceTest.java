package org.jeecg.modules.groupbuy.service;

import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.GbPeriod;
import org.jeecg.modules.groupbuy.entity.GbPoint;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 分享海报：小程序码 scene 编解码、画图 */
class GbPosterServiceTest {

    @Test
    void sceneRoundTrip() {
        String pg = "2123232862147170305", pt = "2123232862000369665";
        String s = GbPosterService.goodsScene(pg, pt);
        assertTrue(s.length() <= 32, "小程序码 scene 最多 32 个字符：" + s);
        assertTrue(s.matches("[0-9a-z.]+"), "只能用小程序码允许的字符：" + s);
        Map<String, String> m = GbPosterService.parseScene(s);
        assertEquals("goods", m.get("type"));
        assertEquals(pg, m.get("id"));
        assertEquals(pt, m.get("pt"));

        Map<String, String> h = GbPosterService.parseScene(GbPosterService.homeScene(pt));
        assertEquals("home", h.get("type"));
        assertEquals(pt, h.get("pt"));
        assertEquals(Map.of("type", "home"), GbPosterService.parseScene(GbPosterService.homeScene(null)));
    }

    @Test
    void sceneEdgeCases() {
        // 最大的 long 也放得下
        String max = String.valueOf(Long.MAX_VALUE);
        assertTrue(GbPosterService.goodsScene(max, max).length() <= 32);
        // 没有提货点
        assertEquals("goods", GbPosterService.parseScene(GbPosterService.goodsScene("123", null)).get("type"));
        assertNull(GbPosterService.parseScene(GbPosterService.goodsScene("123", null)).get("pt"));
        // 非数字 id 不能编码
        assertThrows(IllegalArgumentException.class, () -> GbPosterService.goodsScene("abc", null));
        // 乱码 scene
        assertNull(GbPosterService.parseScene("x.1"));
        assertNull(GbPosterService.parseScene("g.!!"));
        assertNull(GbPosterService.parseScene(null));
    }

    @Test
    void money() {
        assertEquals("9.9", GbPosterService.plain(new BigDecimal("9.90")));
        assertEquals("12", GbPosterService.plain(new BigDecimal("12.00")));
        assertEquals("0.05", GbPosterService.plain(new BigDecimal("0.05")));
        assertEquals("100", GbPosterService.plain(new BigDecimal("100")));
    }

    @Test
    void qrCode() {
        BufferedImage q = GbPosterService.qr("https://shop.example.com/h5/#/pages/goods/goods?id=1&pt=2", 300);
        assertNotNull(q);
        assertEquals(300, q.getWidth());
        // 深色模块占比在正常范围（二维码大约一半是深色）
        int dark = 0;
        for (int x = 0; x < q.getWidth(); x++)
            for (int y = 0; y < q.getHeight(); y++) if ((q.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) dark++;
        double ratio = dark / (double) (q.getWidth() * q.getHeight());
        assertTrue(ratio > 0.25 && ratio < 0.7, "深色占比 " + ratio);
    }

    @Test
    void renderGoodsPoster() throws Exception {
        GbPosterService s = service();
        Map<String, Object> g = new HashMap<>();
        g.put("name", "烟台红富士苹果 新鲜脆甜 当季现摘 一箱约 5 斤装 果径 80mm 以上");
        g.put("spec", "约 2.5kg / 箱");
        g.put("price", new BigDecimal("29.90"));
        g.put("origPrice", new BigDecimal("45.00"));
        g.put("sold", 128);
        g.put("tag", "爆款");
        BufferedImage photo = solid(400, 300, new Color(0xCC3333));
        BufferedImage code = solid(280, 280, Color.BLACK);
        byte[] png = s.renderGoods(g, photo, period(), point(), code);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(750, out.getWidth());
        assertEquals(GbPosterService.GOODS_H, out.getHeight());
        // 商品图铺满上半部分
        assertEquals(0xCC3333, out.getRGB(375, 600) & 0xFFFFFF);
        // 右下角是码
        assertEquals(0x000000, out.getRGB(750 - 40 - 90, GbPosterService.GOODS_H - 250 + 35 + 90) & 0xFFFFFF);
        // 规格文字和底部虚线之间留了空：虚线上方 10 像素整行都是白的
        int footerTop = GbPosterService.GOODS_H - 250;
        for (int x = 40; x < 710; x++) assertEquals(0xFFFFFF, out.getRGB(x, footerTop - 10) & 0xFFFFFF, "x=" + x);
    }

    @Test
    void renderWithoutPhotoAndCode() throws Exception {
        GbPosterService s = service();
        Map<String, Object> g = new HashMap<>();
        g.put("name", "土鸡蛋");
        g.put("price", new BigDecimal("12.80"));
        g.put("sold", 0);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(s.renderGoods(g, null, null, null, null)));
        assertEquals(750, out.getWidth());
        // 没图画浅绿底，没码画占位
        assertEquals(GbPosterService.SOFT.getRGB() & 0xFFFFFF, out.getRGB(375, 375) & 0xFFFFFF);
    }

    @Test
    void renderHomePoster() throws Exception {
        GbPosterService s = service();
        List<Map<String, Object>> goods = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Map<String, Object> g = new HashMap<>();
            g.put("name", "商品" + i);
            g.put("price", new BigDecimal("9.9"));
            g.put("origPrice", new BigDecimal("15"));
            goods.add(g);
        }
        List<BufferedImage> photos = List.of(solid(10, 10, Color.ORANGE), solid(10, 10, Color.ORANGE), solid(10, 10, Color.ORANGE), solid(10, 10, Color.ORANGE));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(s.renderHome(goods, photos, period(), point(), solid(280, 280, Color.BLACK))));
        assertEquals(750, out.getWidth());
        assertEquals(GbPosterService.homeHeight(6), out.getHeight());
        // 第二行商品卡片整个在底部白条上面，不被盖住
        int secondRowBottom = 330 + 2 * 410 + 20;
        assertTrue(secondRowBottom <= out.getHeight() - 250, "第二行被底部盖住了");
        assertEquals(Color.ORANGE.getRGB() & 0xFFFFFF, out.getRGB(40 + 100, 330 + 430 + 140) & 0xFFFFFF);
        // 头部是绿色
        assertEquals(GbPosterService.GREEN.getRGB() & 0xFFFFFF, out.getRGB(700, 20) & 0xFFFFFF);
        // 第一个商品格子里是商品图
        assertEquals(Color.ORANGE.getRGB() & 0xFFFFFF, out.getRGB(40 + 100, 330 + 140) & 0xFFFFFF);
    }

    private static GbPosterService service() {
        GbPosterService s = new GbPosterService(new GbProperties(), null, null, null, null, null, null);
        s.pickFont();
        return s;
    }

    private static GbPeriod period() {
        GbPeriod p = new GbPeriod();
        p.setCutoffTime(new Date(System.currentTimeMillis() + 3600_000L));
        p.setPickupTime(new Date(System.currentTimeMillis() + 86400_000L));
        return p;
    }

    private static GbPoint point() {
        GbPoint p = new GbPoint();
        p.setId("2123232862000369665");
        p.setName("幸福里 3 号楼便利店");
        p.setLeaderName("王桂芳");
        return p;
    }

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return im;
    }
}
