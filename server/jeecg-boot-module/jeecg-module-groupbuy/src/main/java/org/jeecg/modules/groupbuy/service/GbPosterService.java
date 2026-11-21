package org.jeecg.modules.groupbuy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.jeecg.modules.groupbuy.service.GbCoreService.fail;

/**
 * 分享海报：商品海报（居民 / 团长分享单个商品）和本期团海报（团长发小区群、朋友圈）。服务器上用 Java2D 画成 PNG。
 * <ul>
 *   <li>配了小程序 AppID：画小程序码，扫码进商品页 / 首页，scene 里带商品和提货点（见 {@link #goodsScene} 的格式）。</li>
 *   <li>没配 AppID 但配了 groupbuy.h5-base：画 H5 链接的普通二维码；都没配就不画码，只写提示。</li>
 *   <li>中文字体按顺序找 Noto Sans CJK SC / 思源黑体 / 微软雅黑 / 苹方 / 文泉驿 / 黑体。Linux 服务器要装中文字体（如 fonts-noto-cjk），否则字是方框。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbPosterService {
    static final int W = 750;
    static final Color GREEN = new Color(0x078149), RED = new Color(0xE4393C), INK = new Color(0x1F2421), GRAY = new Color(0x6B716D),
            LIGHT = new Color(0xA3AAA5), BG = new Color(0xF4F6F4), SOFT = new Color(0xE7F3EC);
    /** 要有粗体字重的排前面（Windows 上的 Noto Sans SC 是可变字体，Java 画不出粗体，放到后面） */
    private static final String[] FONTS = {"Noto Sans CJK SC", "Source Han Sans SC", "Microsoft YaHei", "微软雅黑", "PingFang SC",
            "WenQuanYi Micro Hei", "WenQuanYi Zen Hei", "Noto Sans SC", "SimHei", "黑体"};
    /** 商品海报高度；本期团海报按商品行数算 */
    static final int GOODS_H = 1290;
    static final int CARD_H = 410, CARD_GAP = 20, GRID_TOP = 330, FOOTER_H = 250;

    private final GbProperties props;
    private final GbPeriodGoodsMapper pgMapper;
    private final GbPeriodMapper periodMapper;
    private final GbPointMapper pointMapper;
    private final GbViewService view;
    private final GbCoreService core;
    private final GbWxService wx;

    @Value("${jeecg.path.upload:}")
    private String uploadPath;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, byte[]> codeCache = new ConcurrentHashMap<>();
    private String family = Font.SANS_SERIF;

    @PostConstruct
    void pickFont() {
        Set<String> have = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : FONTS) {
            if (have.contains(f) && new Font(f, Font.PLAIN, 12).canDisplayUpTo("邻里鲜团购") == -1) {
                family = f;
                return;
            }
        }
        if (new Font(Font.SANS_SERIF, Font.PLAIN, 12).canDisplayUpTo("邻里鲜团购") != -1)
            log.warn("[团购] 服务器上没找到中文字体，分享海报上的中文会显示成方框。Linux 装一下 fonts-noto-cjk（或文泉驿）再重启");
    }

    // ======================================================== scene：小程序码里带的参数，最多 32 个字符

    /** 商品：g.<团品 id 36 进制>.<提货点 id 36 进制>（最长 29 个字符）；提货点可以没有 */
    public static String goodsScene(String periodGoodsId, String pointId) {
        String g = b36(periodGoodsId), p = b36(pointId);
        if (g == null) throw new IllegalArgumentException("团品 id 不是数字：" + periodGoodsId);
        String s = "g." + g + (p == null ? "" : "." + p);
        return s.length() <= 32 ? s : "g." + g;
    }

    /** 首页（本期团）：i.<提货点 id 36 进制>，没有提货点就是 i */
    public static String homeScene(String pointId) {
        String p = b36(pointId);
        return p == null ? "i" : "i." + p;
    }

    /** 解析 scene → {type: goods/home, id, pt}；不认识返回 null。小程序端在 gb/scene.ts 里有一份一样的实现 */
    public static Map<String, String> parseScene(String scene) {
        if (scene == null) return null;
        String[] a = scene.trim().split("\\.");
        Map<String, String> m = new LinkedHashMap<>();
        try {
            if (a[0].equals("g") && a.length >= 2) {
                m.put("type", "goods");
                m.put("id", new BigInteger(a[1], 36).toString());
                if (a.length >= 3) m.put("pt", new BigInteger(a[2], 36).toString());
                return m;
            }
            if (a[0].equals("i")) {
                m.put("type", "home");
                if (a.length >= 2) m.put("pt", new BigInteger(a[1], 36).toString());
                return m;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    static String b36(String id) {
        if (id == null || !id.matches("\\d{1,30}")) return null;
        return new BigInteger(id).toString(36);
    }

    // ======================================================== 海报

    /** 商品海报 */
    public byte[] goodsPoster(String periodGoodsId, String pointId) {
        GbPeriodGoods pg = pgMapper.selectById(periodGoodsId);
        if (pg == null) throw fail("商品不存在");
        Map<String, Object> g = view.periodGoods(List.of(pg)).get(0);
        GbPeriod period = periodMapper.selectById(pg.getPeriodId());
        GbPoint pt = okPoint(pointId);
        String scene = goodsScene(pg.getId(), pt == null ? null : pt.getId());
        BufferedImage code = code(scene, "pages/goods/goods", "/pages/goods/goods?id=" + pg.getId() + (pt == null ? "" : "&pt=" + pt.getId()));
        return renderGoods(g, load((String) g.get("image")), period, pt, code);
    }

    /** 画商品海报（不查库，单元测试直接调） */
    byte[] renderGoods(Map<String, Object> g, BufferedImage photo, GbPeriod period, GbPoint pt, BufferedImage code) {
        int h = GOODS_H;
        BufferedImage img = new BufferedImage(W, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = start(img);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, W, h);
        // 商品大图 + 左上角品牌
        cover(g2, photo, 0, 0, W, W, 0);
        pill(g2, "邻里鲜 · 社区团购", 32, 32, GREEN, Color.WHITE, 26);
        Object tag = g.get("tag");
        if (tag != null && !tag.toString().isBlank()) pill(g2, tag.toString(), 32, 92, RED, Color.WHITE, 24);

        // 价格
        int y = W + 84;
        g2.setColor(RED);
        g2.setFont(font(Font.BOLD, 34));
        g2.drawString("¥", 40, y);
        int x = 40 + g2.getFontMetrics().stringWidth("¥") + 4;
        g2.setFont(font(Font.BOLD, 68));
        String price = plain((BigDecimal) g.get("price"));
        g2.drawString(price, x, y);
        x += g2.getFontMetrics().stringWidth(price) + 18;
        BigDecimal orig = (BigDecimal) g.get("origPrice");
        if (orig != null && orig.compareTo((BigDecimal) g.get("price")) > 0) {
            g2.setColor(LIGHT);
            g2.setFont(font(Font.PLAIN, 26));
            String o = "超市价 ¥" + plain(orig);
            g2.drawString(o, x, y - 4);
            int ow = g2.getFontMetrics().stringWidth(o);
            g2.drawLine(x, y - 13, x + ow, y - 13);
        }
        g2.setColor(GRAY);
        g2.setFont(font(Font.PLAIN, 24));
        String sold = "已团 " + g.get("sold") + " 份";
        g2.drawString(sold, W - 40 - g2.getFontMetrics().stringWidth(sold), y - 4);

        // 名称、规格
        g2.setColor(INK);
        g2.setFont(font(Font.BOLD, 38));
        y = wrap(g2, (String) g.get("name"), 40, y + 64, W - 80, 52, 2);
        g2.setColor(GRAY);
        g2.setFont(font(Font.PLAIN, 26));
        String spec = (String) g.get("spec");
        if (spec != null && !spec.isBlank()) y = wrap(g2, spec, 40, y + 4, W - 80, 36, 1);

        // 底部：自提信息 + 码
        footer(g2, h, period, pt, code, "长按识别小程序码，进店下单");
        g2.dispose();
        return png(img);
    }

    /** 本期团海报：本期前 4 个商品 + 提货点 */
    public byte[] homePoster(String pointId) {
        GbPeriod period = core.livePeriod();
        if (period == null) throw fail("现在没有正在进行的团，开团后再来生成海报");
        List<GbPeriodGoods> pgs = pgMapper.selectList(new LambdaQueryWrapper<GbPeriodGoods>().eq(GbPeriodGoods::getPeriodId, period.getId()).eq(GbPeriodGoods::getStatus, "on")
                .orderByAsc(GbPeriodGoods::getSortNo));
        List<Map<String, Object>> goods = view.periodGoods(pgs);
        GbPoint pt = okPoint(pointId);
        BufferedImage code = code(homeScene(pt == null ? null : pt.getId()), "pages/index/index", "/pages/index/index" + (pt == null ? "" : "?pt=" + pt.getId()));
        List<BufferedImage> photos = goods.stream().limit(4).map(g -> load((String) g.get("image"))).toList();
        return renderHome(goods, photos, period, pt, code);
    }

    /** 画本期团海报（不查库，单元测试直接调） */
    byte[] renderHome(List<Map<String, Object>> goods, List<BufferedImage> photos, GbPeriod period, GbPoint pt, BufferedImage code) {
        int h = homeHeight(goods.size());
        BufferedImage img = new BufferedImage(W, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = start(img);
        g2.setColor(BG);
        g2.fillRect(0, 0, W, h);
        // 头部
        g2.setColor(GREEN);
        g2.fillRect(0, 0, W, 300);
        g2.setColor(Color.WHITE);
        g2.setFont(font(Font.BOLD, 30));
        g2.drawString("邻里鲜 · 社区团购", 40, 76);
        g2.setFont(font(Font.BOLD, 56));
        g2.drawString("今日团购 " + goods.size() + " 款", 40, 158);
        g2.setFont(font(Font.PLAIN, 26));
        g2.setColor(new Color(255, 255, 255, 220));
        g2.drawString("今天下单，" + dayTime(period.getPickupTime()) + " 起到小区自提", 40, 212);
        if (pt != null) {
            g2.setFont(font(Font.PLAIN, 24));
            g2.drawString(ellipsis(g2, "团长 " + nz(pt.getLeaderName()) + " · " + pt.getName(), W - 80), 40, 256);
        }

        // 2 x 2 商品
        int cw = (W - 40 * 2 - CARD_GAP) / 2, top = GRID_TOP;
        for (int i = 0; i < Math.min(4, goods.size()); i++) {
            Map<String, Object> g = goods.get(i);
            int cx = 40 + (i % 2) * (cw + CARD_GAP), cy = top + (i / 2) * (CARD_H + CARD_GAP);
            g2.setColor(Color.WHITE);
            g2.fill(new RoundRectangle2D.Float(cx, cy, cw, CARD_H, 20, 20));
            cover(g2, i < photos.size() ? photos.get(i) : null, cx, cy, cw, 280, 20);
            g2.setColor(INK);
            g2.setFont(font(Font.BOLD, 28));
            g2.drawString(ellipsis(g2, (String) g.get("name"), cw - 32), cx + 16, cy + 328);
            g2.setColor(RED);
            g2.setFont(font(Font.BOLD, 22));
            g2.drawString("¥", cx + 16, cy + 382);
            g2.setFont(font(Font.BOLD, 40));
            String p = plain((BigDecimal) g.get("price"));
            g2.drawString(p, cx + 32, cy + 382);
            int px = cx + 32 + g2.getFontMetrics().stringWidth(p) + 10;
            BigDecimal orig = (BigDecimal) g.get("origPrice");
            if (orig != null && orig.compareTo((BigDecimal) g.get("price")) > 0) {
                g2.setColor(LIGHT);
                g2.setFont(font(Font.PLAIN, 22));
                String o = "¥" + plain(orig);
                g2.drawString(o, px, cy + 380);
                g2.drawLine(px, cy + 372, px + g2.getFontMetrics().stringWidth(o), cy + 372);
            }
        }
        if (goods.isEmpty()) {
            g2.setColor(GRAY);
            g2.setFont(font(Font.PLAIN, 28));
            g2.drawString("本期商品正在上架，扫码看看", 40, top + 60);
        }
        footer(g2, h, period, pt, code, goods.size() > 4 ? "长按识别小程序码，看全部 " + goods.size() + " 款" : "长按识别小程序码，进店下单");
        g2.dispose();
        return png(img);
    }

    /** 本期团海报高度：1 行或 2 行商品（没有商品时留一行写提示） */
    static int homeHeight(int goods) {
        int rows = goods > 2 ? 2 : 1;
        return GRID_TOP + rows * (CARD_H + CARD_GAP) + 10 + FOOTER_H;
    }

    /** 底部白条：自提时间、提货点、截单时间，右边是码 */
    private void footer(Graphics2D g2, int h, GbPeriod period, GbPoint pt, BufferedImage code, String hint) {
        int top = h - FOOTER_H;
        g2.setColor(Color.WHITE);
        g2.fillRect(0, top, W, FOOTER_H);
        g2.setColor(new Color(0xE3E7E4));
        g2.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{8, 8}, 0));
        g2.drawLine(40, top, W - 40, top);
        g2.setStroke(new BasicStroke(1));
        int tw = W - 80 - 200;
        g2.setColor(GREEN);
        g2.setFont(font(Font.BOLD, 30));
        g2.drawString(ellipsis(g2, period == null ? "到小区自提" : dayTime(period.getPickupTime()) + " 起到小区自提", tw), 40, top + 62);
        g2.setColor(INK);
        g2.setFont(font(Font.PLAIN, 24));
        g2.drawString(ellipsis(g2, pt == null ? "选一个离你近的提货点" : "在「" + pt.getName() + "」提货", tw), 40, top + 106);
        if (period != null && period.getCutoffTime() != null) {
            g2.setColor(RED);
            g2.drawString("截单 " + dayTime(period.getCutoffTime()), 40, top + 146);
        }
        g2.setColor(LIGHT);
        g2.setFont(font(Font.PLAIN, 22));
        g2.drawString(ellipsis(g2, code == null ? "微信搜「邻里鲜」小程序下单" : hint, tw), 40, top + 200);
        int cs = 180, cx = W - 40 - cs, cy = top + 35;
        if (code != null) {
            g2.drawImage(code, cx, cy, cs, cs, null);
        } else {
            g2.setColor(SOFT);
            g2.fill(new RoundRectangle2D.Float(cx, cy, cs, cs, 16, 16));
            g2.setColor(GREEN);
            g2.setFont(font(Font.BOLD, 26));
            String t = "邻里鲜";
            g2.drawString(t, cx + (cs - g2.getFontMetrics().stringWidth(t)) / 2, cy + cs / 2 + 10);
        }
    }

    // ======================================================== 码

    /** 小程序码；没配 AppID 时画 H5 链接二维码；都不行返回 null（海报照样出，只是没码） */
    BufferedImage code(String scene, String page, String h5Path) {
        if (wx.ready()) {
            String env = switch (props.getNotify().getState() == null ? "formal" : props.getNotify().getState()) {
                case "developer" -> "develop";
                case "trial" -> "trial";
                default -> "release";
            };
            String key = env + "|" + page + "|" + scene;
            try {
                byte[] b = codeCache.get(key);
                if (b == null) {
                    b = wx.wxacode(scene, page, env, 280);
                    if (codeCache.size() > 500) codeCache.clear();
                    codeCache.put(key, b);
                }
                BufferedImage im = ImageIO.read(new ByteArrayInputStream(b));
                if (im != null) return im;
                log.warn("[团购] 小程序码不是图片：{}", scene);
            } catch (Exception e) {
                log.warn("[团购] 生成小程序码失败（{}）：{}", scene, e.getMessage());
            }
            return null;
        }
        String base = props.getH5Base();
        if (base == null || base.isBlank()) return null;
        return qr(base.replaceAll("/+$", "") + "/#" + h5Path, 360);
    }

    static BufferedImage qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            BufferedImage im = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < m.getWidth(); x++)
                for (int y = 0; y < m.getHeight(); y++) im.setRGB(x, y, m.get(x, y) ? 0x1F2421 : 0xFFFFFF);
            return im;
        } catch (Exception e) {
            return null;
        }
    }

    // ======================================================== 画图工具

    private GbPoint okPoint(String pointId) {
        if (pointId == null || pointId.isBlank()) return null;
        GbPoint p = pointMapper.selectById(pointId);
        return p != null && "ok".equals(p.getStatus()) ? p : null;
    }

    private Graphics2D start(BufferedImage img) {
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g2;
    }

    Font font(int style, int size) {
        return new Font(family, style, size);
    }

    /** 商品图：上传目录里的相对路径或 http 地址；读不到返回 null（画浅色底） */
    BufferedImage load(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(6)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                return r.statusCode() == 200 ? ImageIO.read(new ByteArrayInputStream(r.body())) : null;
            }
            if (uploadPath == null || uploadPath.isBlank() || path.contains("..")) return null;
            File f = new File(uploadPath, path);
            return f.isFile() ? ImageIO.read(f) : null;
        } catch (Exception e) {
            log.debug("[团购] 海报读图失败 {}：{}", path, e.getMessage());
            return null;
        }
    }

    /** 等比铺满（裁掉多出来的部分），r>0 时圆角只圆上面两个角 */
    private void cover(Graphics2D g2, BufferedImage src, int x, int y, int w, int h, int r) {
        Shape old = g2.getClip();
        if (r > 0) {
            java.awt.geom.Area a = new java.awt.geom.Area(new RoundRectangle2D.Float(x, y, w, h + r, r, r));
            a.intersect(new java.awt.geom.Area(new Rectangle(x, y, w, h)));
            g2.setClip(a);
        } else {
            g2.setClip(x, y, w, h);
        }
        if (src == null) {
            g2.setColor(SOFT);
            g2.fillRect(x, y, w, h);
        } else {
            double s = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
            int dw = (int) Math.ceil(src.getWidth() * s), dh = (int) Math.ceil(src.getHeight() * s);
            g2.setColor(Color.WHITE);
            g2.fillRect(x, y, w, h);
            g2.drawImage(src, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
        }
        g2.setClip(old);
    }

    private void pill(Graphics2D g2, String text, int x, int y, Color bg, Color fg, int size) {
        g2.setFont(font(Font.BOLD, size));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(text) + size * 2, h = size + 22;
        g2.setColor(bg);
        g2.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
        g2.setColor(fg);
        g2.drawString(text, x + size, y + (h - fm.getHeight()) / 2 + fm.getAscent());
    }

    /** 按宽度折行，最多 maxLines 行（最后一行放不下加省略号），返回下一行的基线 y */
    int wrap(Graphics2D g2, String text, int x, int y, int maxW, int lineH, int maxLines) {
        if (text == null) return y;
        FontMetrics fm = g2.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (fm.stringWidth(cur + ch) > maxW && cur.length() > 0) {
                lines.add(cur.toString());
                cur.setLength(0);
                if (lines.size() == maxLines) break;
            }
            cur.append(ch);
            i += Character.charCount(cp);
        }
        if (lines.size() < maxLines && cur.length() > 0) lines.add(cur.toString());
        else if (i < text.length() || cur.length() > 0) {
            // 还有没画完的：最后一行加省略号
            int last = lines.size() - 1;
            lines.set(last, ellipsis(g2, lines.get(last) + cur + "…", maxW));
        }
        for (String l : lines) {
            g2.drawString(l, x, y);
            y += lineH;
        }
        return y;
    }

    static String ellipsis(Graphics2D g2, String s, int maxW) {
        if (s == null) return "";
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        String t = s.endsWith("…") ? s.substring(0, s.length() - 1) : s;
        while (!t.isEmpty() && fm.stringWidth(t + "…") > maxW) t = t.substring(0, t.offsetByCodePoints(t.length(), -1));
        return t + "…";
    }

    static String plain(BigDecimal v) {
        if (v == null) return "0";
        BigDecimal s = v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }

    /** 今天 / 明天 / 11月22日 + 16:00 */
    static String dayTime(Date d) {
        if (d == null) return "";
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTime(d);
        long days = Math.round((startOfDay(c) - startOfDay(now)) / 86400000.0);
        String day = days == 0 ? "今天" : days == 1 ? "明天" : days == 2 ? "后天" : (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
        return day + String.format(" %02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static long startOfDay(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 0);
        x.set(Calendar.MINUTE, 0);
        x.set(Calendar.SECOND, 0);
        x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
