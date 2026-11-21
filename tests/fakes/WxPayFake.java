// 测试用「假微信支付」，只用 JDK 自带的类（java WxPayFake.java ... 直接跑，不用编译）。
//   java WxPayFake.java keys <dir>                    生成商户私钥、微信支付公私钥（测试用，不要提交）
//   java WxPayFake.java serve <dir> <port> <apiV3Key> 起一个假的微信支付接口（目前只有商家转账）
// 假接口按微信支付 API v3 的规则给响应签名，后端的官方 SDK 能正常验签。
//   POST /v3/fund-app/mch-transfer/transfer-bills                         发起转账（按 /__mode 决定结果）
//   GET  /v3/fund-app/mch-transfer/transfer-bills/out-bill-no/{no}         查询
//   POST /v3/fund-app/mch-transfer/transfer-bills/out-bill-no/{no}/cancel  撤销
// 控制（测试用）：
//   GET  /__mode?transfer=ok|success|reject|error|drop   ok=等用户确认，success=直接成功，reject=余额不足，error=500，drop=建单后断开连接
//   GET  /__state?no=&state=&reason=     改转账单状态（模拟用户确认收款、失败、撤销完成）
//   GET  /__bills                        所有转账单（JSON），user_name 是解密后的
//   GET  /__reset
//   POST /__notify?type=&event=[&bad=1]  正文是明文 JSON，返回加密、签好名的通知 {"headers":{...},"body":"..."}
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WxPayFake {
    static final String SERIAL = "PUB_KEY_ID_TEST";
    static PrivateKey wxKey;
    static String apiV3Key;
    static volatile String mode = "ok";
    static final Map<String, Map<String, Object>> bills = new ConcurrentHashMap<>();
    static final List<String> order = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[1]);
        if (a[0].equals("keys")) {
            Files.createDirectories(dir);
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair merchant = g.generateKeyPair(), wx = g.generateKeyPair();
            pem(dir.resolve("apiclient_key.pem"), "PRIVATE KEY", merchant.getPrivate().getEncoded());
            pem(dir.resolve("pub_key.pem"), "PUBLIC KEY", wx.getPublic().getEncoded());
            pem(dir.resolve("wx_private.pem"), "PRIVATE KEY", wx.getPrivate().getEncoded());
            System.out.println("ok");
            return;
        }
        String b64 = Files.readString(dir.resolve("wx_private.pem")).replaceAll("-----[A-Z ]+-----|\\s", "");
        wxKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        apiV3Key = a[3];
        // 自己解析 HTTP：JDK 自带的 HttpServer 会把响应头名改成小写（Wechatpay-timestamp），SDK 按原样大小写取头会取不到
        ServerSocket ss = new ServerSocket(Integer.parseInt(a[2]), 50, InetAddress.getByName("127.0.0.1"));
        var pool = Executors.newFixedThreadPool(8);
        System.out.println("listening " + a[2]);
        while (true) {
            Socket so = ss.accept();
            pool.execute(() -> serve(so));
        }
    }

    /** 一次请求 */
    static class X {
        String method, path, rawQuery, body;
        Map<String, String> headers = new HashMap<>(); // 名字转小写
        int status;
        Map<String, String> respHeaders = new LinkedHashMap<>();
        String respBody;
        boolean drop;

        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    static void serve(Socket so) {
        try (so) {
            so.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(so.getInputStream());
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            X x = new X();
            String[] rl = line.split(" ");
            x.method = rl[0];
            String uri = rl[1];
            int qi = uri.indexOf('?');
            x.path = qi < 0 ? uri : uri.substring(0, qi);
            x.rawQuery = qi < 0 ? null : uri.substring(qi + 1);
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                int c = h.indexOf(':');
                if (c > 0) x.headers.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
            }
            int len = Integer.parseInt(x.headers.getOrDefault("content-length", "0"));
            x.body = new String(in.readNBytes(len), StandardCharsets.UTF_8);
            handle(x);
            if (x.drop) return;
            byte[] b = x.respBody.getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(x.status).append(x.status == 200 ? " OK" : " ERR").append("\r\n");
            x.respHeaders.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
            sb.append("Content-Type: application/json; charset=utf-8\r\nContent-Length: ").append(b.length).append("\r\nConnection: close\r\n\r\n");
            OutputStream out = so.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.write(b);
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return b.toString(StandardCharsets.UTF_8).replaceAll("\r$", "");
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString(StandardCharsets.UTF_8);
    }

    static void handle(X x) {
        try {
            String path = x.path, method = x.method;
            Map<String, String> q = query(x.rawQuery);
            String body = x.body;
            if (path.equals("/__mode")) {
                if (q.containsKey("transfer")) mode = q.get("transfer");
                plain(x, 200, "{\"transfer\":\"" + mode + "\"}");
                return;
            }
            if (path.equals("/__reset")) {
                bills.clear();
                order.clear();
                mode = "ok";
                plain(x, 200, "{\"ok\":true}");
                return;
            }
            if (path.equals("/__state")) {
                Map<String, Object> b = bills.get(q.get("no"));
                if (b == null) {
                    plain(x, 404, "{\"error\":\"no bill\"}");
                    return;
                }
                b.put("state", q.get("state"));
                if (q.containsKey("reason")) b.put("fail_reason", q.get("reason"));
                b.put("update_time", now());
                plain(x, 200, json(b));
                return;
            }
            if (path.equals("/__bills")) {
                StringBuilder sb = new StringBuilder("[");
                synchronized (order) {
                    for (String no : order) sb.append(sb.length() > 1 ? "," : "").append(json(bills.get(no)));
                }
                plain(x, 200, sb.append("]").toString());
                return;
            }
            if (path.equals("/__notify")) {
                plain(x, 200, notification(q.get("type"), q.get("event"), body, "1".equals(q.get("bad"))));
                return;
            }
            String base = "/v3/fund-app/mch-transfer/transfer-bills";
            String auth = x.header("Authorization");
            if (auth == null || !auth.startsWith("WECHATPAY2-SHA256-RSA2048 ")) {
                signed(x, 401, "{\"code\":\"SIGN_ERROR\",\"message\":\"没有签名\"}");
                return;
            }
            if (method.equals("POST") && path.equals(base)) {
                create(x, body);
                return;
            }
            if (path.startsWith(base + "/out-bill-no/")) {
                String rest = path.substring((base + "/out-bill-no/").length());
                boolean cancel = rest.endsWith("/cancel");
                String no = URLDecoder.decode(cancel ? rest.substring(0, rest.length() - "/cancel".length()) : rest, StandardCharsets.UTF_8);
                Map<String, Object> b = bills.get(no);
                if (b == null) {
                    signed(x, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"记录不存在\"}");
                    return;
                }
                if (cancel) {
                    if (!List.of("ACCEPTED", "PROCESSING", "WAIT_USER_CONFIRM").contains(b.get("state"))) {
                        signed(x, 400, "{\"code\":\"INVALID_REQUEST\",\"message\":\"当前状态不能撤销\"}");
                        return;
                    }
                    b.put("state", "CANCELING");
                    b.put("update_time", now());
                    signed(x, 200, "{\"out_bill_no\":\"" + no + "\",\"transfer_bill_no\":\"" + b.get("transfer_bill_no") + "\",\"state\":\"CANCELING\",\"update_time\":\"" + now() + "\"}");
                    return;
                }
                signed(x, 200, json(publicView(b)));
                return;
            }
            signed(x, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no such api\"}");
        } catch (Exception e) {
            e.printStackTrace();
            try {
                plain(x, 500, "{\"code\":\"FAKE_ERROR\",\"message\":\"" + e + "\"}");
            } catch (Exception ignored) {
            }
        }
    }

    static void create(X x, String body) throws Exception {
        String no = str(body, "out_bill_no");
        if (mode.equals("reject")) {
            signed(x, 403, "{\"code\":\"NOT_ENOUGH\",\"message\":\"资金不足\"}");
            return;
        }
        if (mode.equals("error")) {
            signed(x, 500, "{\"code\":\"SYSTEM_ERROR\",\"message\":\"系统繁忙，请稍后再试\"}");
            return;
        }
        Map<String, Object> b = bills.get(no);
        if (b == null) {
            b = new LinkedHashMap<>();
            b.put("out_bill_no", no);
            b.put("transfer_bill_no", "1330000" + String.format("%033d", Math.abs(new Random().nextLong()) % 1_000_000_000_000L));
            b.put("state", mode.equals("success") ? "SUCCESS" : "WAIT_USER_CONFIRM");
            b.put("package_info", "pkg_" + no + "_" + UUID.randomUUID().toString().substring(0, 8));
            b.put("transfer_amount", num(body, "transfer_amount"));
            b.put("openid", str(body, "openid"));
            b.put("appid", str(body, "appid"));
            b.put("transfer_scene_id", str(body, "transfer_scene_id"));
            b.put("user_recv_perception", str(body, "user_recv_perception"));
            b.put("transfer_remark", str(body, "transfer_remark"));
            b.put("notify_url", str(body, "notify_url"));
            b.put("report_infos", count(body, "info_type"));
            b.put("serial_header", x.header("Wechatpay-Serial"));
            String enc = str(body, "user_name");
            b.put("user_name", enc == null ? null : decrypt(enc));
            b.put("create_time", now());
            b.put("update_time", now());
            bills.put(no, b);
            order.add(no);
        }
        if (mode.equals("drop")) {
            // 建了单但响应丢了：后端只能靠查询知道结果
            x.drop = true;
            return;
        }
        String resp = "{\"out_bill_no\":\"" + no + "\",\"transfer_bill_no\":\"" + b.get("transfer_bill_no") + "\",\"create_time\":\"" + b.get("create_time")
                + "\",\"state\":\"" + b.get("state") + "\"" + ("WAIT_USER_CONFIRM".equals(b.get("state")) ? ",\"package_info\":\"" + b.get("package_info") + "\"" : "") + "}";
        signed(x, 200, resp);
    }

    /** 查询接口返回的字段（去掉测试记录用的） */
    static Map<String, Object> publicView(Map<String, Object> b) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("out_bill_no", "transfer_bill_no", "state", "transfer_amount", "openid", "fail_reason", "create_time", "update_time"))
            if (b.get(k) != null) m.put(k, b.get(k));
        if ("WAIT_USER_CONFIRM".equals(b.get("state"))) m.put("package_info", b.get("package_info"));
        m.put("mch_id", "1900000001");
        m.put("appid", b.get("appid"));
        return m;
    }

    // ---------------------------------------------------------------- 签名、加解密

    static void signed(X x, int status, String body) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000), nonce = UUID.randomUUID().toString().replace("-", "");
        x.respHeaders.put("Wechatpay-Timestamp", ts);
        x.respHeaders.put("Wechatpay-Nonce", nonce);
        x.respHeaders.put("Wechatpay-Signature", sign(ts + "\n" + nonce + "\n" + body + "\n"));
        x.respHeaders.put("Wechatpay-Serial", SERIAL);
        x.respHeaders.put("Wechatpay-Signature-Type", "WECHATPAY2-SHA256-RSA2048");
        x.respHeaders.put("Request-ID", UUID.randomUUID().toString());
        plain(x, status, body);
    }

    static void plain(X x, int status, String body) {
        x.status = status;
        x.respBody = body;
    }

    static String sign(String msg) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(wxKey);
        s.update(msg.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    static String decrypt(String b64) {
        for (String alg : List.of("RSA/ECB/OAEPWithSHA-1AndMGF1Padding", "RSA/ECB/PKCS1Padding")) {
            try {
                Cipher c = Cipher.getInstance(alg);
                c.init(Cipher.DECRYPT_MODE, wxKey);
                return new String(c.doFinal(Base64.getDecoder().decode(b64)), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return "<解密失败>";
    }

    static String notification(String type, String event, String plain, boolean bad) throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"), new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        c.updateAAD(type.getBytes(StandardCharsets.UTF_8));
        String cipher = Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        String body = "{\"id\":\"" + UUID.randomUUID() + "\",\"create_time\":\"" + now() + "\",\"resource_type\":\"encrypt-resource\",\"event_type\":\"" + event
                + "\",\"summary\":\"test\",\"resource\":{\"original_type\":\"" + type + "\",\"algorithm\":\"AEAD_AES_256_GCM\",\"ciphertext\":\"" + cipher
                + "\",\"associated_data\":\"" + type + "\",\"nonce\":\"" + nonce + "\"}}";
        String ts = String.valueOf(System.currentTimeMillis() / 1000), sigNonce = UUID.randomUUID().toString().replace("-", "");
        String sig = sign(ts + "\n" + sigNonce + "\n" + body + (bad ? "x" : "") + "\n");
        return "{\"headers\":{\"Wechatpay-Timestamp\":\"" + ts + "\",\"Wechatpay-Nonce\":\"" + sigNonce + "\",\"Wechatpay-Signature\":\"" + sig
                + "\",\"Wechatpay-Serial\":\"" + SERIAL + "\",\"Wechatpay-Signature-Type\":\"WECHATPAY2-SHA256-RSA2048\"},\"body\":" + quote(body) + "}";
    }

    // ---------------------------------------------------------------- 小工具（不引第三方 JSON 库）

    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return null;
        // 还原 JSON 字符串里的转义（Gson 默认把 = 写成 =）
        String v = m.group(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\\' || i + 1 >= v.length()) {
                sb.append(c);
                continue;
            }
            char n = v.charAt(++i);
            switch (n) {
                case 'u' -> {
                    sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    static Long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    static int count(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"").matcher(json);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static String json(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            sb.append(v == null ? "null" : v instanceof Number || v instanceof Boolean ? v.toString() : quote(v.toString()));
        }
        return sb.append('}').toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static String now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    static void pem(Path p, String type, byte[] der) throws Exception {
        Files.writeString(p, "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der) + "\n-----END " + type + "-----\n");
    }
}
