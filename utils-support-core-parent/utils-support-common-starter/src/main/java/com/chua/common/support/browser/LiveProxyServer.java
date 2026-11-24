package com.chua.common.support.browser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上游登录页反向代理服务器。
 *
 * <p>用户浏览器经本代理<b>原生加载</b>上游登录页（零截屏、零输入转发，无 CDP 推流卡顿/延迟），
 * 代理在返回的 HTML 中注入抓取脚本，页面内产生的登录态（JWT / Cookie / Storage）
 * 经同源回传端点 {@code /live/p/{token}/__chua_capture} 上交后端。</p>
 *
 * <p>每个会话一个 token：路径前缀 {@code /live/p/{token}/} 映射到该会话的上游站点；
 * Cookie 由服务端 Cookie 罐托管，响应中的 Set-Cookie 去除 Domain/Secure 后透传给浏览器。</p>
 *
 * <p>用法：</p>
 * <pre>
 * LiveProxyServer server = new LiveProxyServer();
 * server.start();                                  // 随机端口
 * server.setCaptureListener((token, payload) -&gt; ...);
 * String path = server.open("https://api.xxx.cc"); // 返回 /live/p/{token}
 * String url  = server.baseUrl() + path;           // 塞给前端 iframe
 * server.close(token);                             // 会话结束
 * server.close();                                  // 停服
 * </pre>
 */
public class LiveProxyServer {

    /** 单个代理会话：上游地址 + 服务端 Cookie 罐。 */
    public static final class Session {
        /** 会话令牌（路径段）。 */
        public final String token;
        /** 上游基址，如 {@code https://api.8888ok.cc}。 */
        public final String upstream;
        final long createdAt = System.currentTimeMillis();
        volatile long lastUsed = System.currentTimeMillis();
        /** 域名 -&gt; (Cookie 名 -&gt; 值)。 */
        final Map<String, Map<String, String>> cookieJar = new ConcurrentHashMap<>();
        /** 会话级自定义注入脚本（业务方提供的抓取逻辑）。 */
        volatile String injectScript;

        Session(String token, String upstream) {
            this.token = token;
            this.upstream = upstream;
        }

        /** 该会话在代理上的公开路径前缀。 */
        public String publicPath() {
            return "/live/p/" + token;
        }
    }

    /** 捕获回调：payload 为页面注入脚本 POST 上来的 JSON 文本。 */
    public interface CaptureListener {
        /**
         * 页面上交一批捕获数据。
         *
         * @param token   会话令牌
         * @param payload JSON 文本
         */
        void onCapture(String token, String payload);
    }

    private static final long IDLE_TIMEOUT_MS = 30L * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 不向浏览器透传的响应头（避免 CSP 拦注入脚本 / XFO 拦 iframe）。 */
    private static final String[] STRIP_RESPONSE_HEADERS = {
            "content-security-policy", "x-frame-options", "cross-origin-opener-policy",
            "cross-origin-embedder-policy", "cross-origin-resource-policy"
    };

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private HttpServer server;
    private int port;
    private volatile CaptureListener captureListener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread reaper;

    /** 启动代理监听（随机端口，可重复调用，已启动则忽略）。 */
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(0), 0);
        created.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "live-proxy-" + RANDOM.nextInt(10000));
            t.setDaemon(true);
            return t;
        }));
        created.createContext("/", this::handle);
        created.start();
        this.server = created;
        this.port = created.getAddress().getPort();
        running.set(true);
        this.reaper = new Thread(this::reapLoop, "live-proxy-reaper");
        this.reaper.setDaemon(true);
        this.reaper.start();
    }

    /**
     * 为上游站点建立代理会话（无自定义注入脚本）。
     *
     * @param upstreamUrl 上游基址或完整登录页 URL（取 scheme+host+port 为基址）
     * @return 代理公开路径，如 {@code /live/p/{token}}
     */
    public synchronized String open(String upstreamUrl) throws IOException {
        return open(upstreamUrl, null);
    }

    /**
     * 为上游站点建立代理会话，并附带业务方注入脚本（追加在引导脚本之后）。
     *
     * @param upstreamUrl  上游基址或完整登录页 URL
     * @param injectScript 业务方脚本（&lt;script&gt;...&lt;/script&gt; 或纯 JS，可为 null）
     * @return 代理公开路径，如 {@code /live/p/{token}}
     */
    public synchronized String open(String upstreamUrl, String injectScript) throws IOException {
        start();
        URI uri = URI.create(upstreamUrl);
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String token = newToken();
        Session session = new Session(token, base);
        session.injectScript = injectScript;
        sessions.put(token, session);
        return "/live/p/" + token;
    }

    /** 代理基址，如 {@code http://127.0.0.1:port}。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** 监听端口。 */
    public int port() {
        return port;
    }

    /** 注册捕获回调。 */
    public void setCaptureListener(CaptureListener listener) {
        this.captureListener = listener;
    }

    /** 是否有指定会话。 */
    public boolean hasSession(String token) {
        return token != null && sessions.containsKey(token);
    }

    /** 关闭单个会话。 */
    public synchronized void close(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 停止代理并清空全部会话。 */
    public synchronized void close() {
        running.set(false);
        sessions.clear();
        if (reaper != null) {
            reaper.interrupt();
            reaper = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static String newToken() {
        byte[] buf = new byte[12];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(24);
        for (byte b : buf) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 请求分发

    private void handle(HttpExchange exchange) throws IOException {
        try {
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            if (path.startsWith("/live/p/")) {
                String rest = path.substring("/live/p/".length());
                int slash = rest.indexOf('/');
                String token = slash < 0 ? rest : rest.substring(0, slash);
                String sub = slash < 0 ? "/" : rest.substring(slash);
                Session session = sessions.get(token);
                if (session == null) {
                    sendText(exchange, 404, "live proxy session not found");
                    return;
                }
                session.lastUsed = System.currentTimeMillis();
                if (sub.startsWith("/__chua_capture")) {
                    handleCapture(exchange, session);
                } else {
                    proxy(exchange, session, sub, uri.getRawQuery());
                }
            } else {
                sendText(exchange, 404, "not found");
            }
        } catch (Exception e) {
            sendText(exchange, 502, "live proxy error: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    /** 回传端点：页面注入脚本把捕获到的登录态 POST 上来。 */
    private void handleCapture(HttpExchange exchange, Session session) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String payload = new String(body, StandardCharsets.UTF_8);
        session.lastUsed = System.currentTimeMillis();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 2);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        CaptureListener listener = captureListener;
        if (listener != null) {
            try {
                listener.onCapture(session.token, payload);
            } catch (Exception ignored) {
                // 回调失败不影响页面
            }
        }
    }

    // ------------------------------------------------------------------ 反向代理

    private void proxy(HttpExchange exchange, Session session, String subPath, String query) throws IOException {
        String target = session.upstream + subPath + (query == null ? "" : "?" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(30));
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        URI base = URI.create(session.upstream);
        for (Map.Entry<String, List<String>> en : exchange.getRequestHeaders().entrySet()) {
            String name = en.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("host") || lower.equals("connection") || lower.equals("content-length")
                    || lower.equals("cookie") || lower.equals("origin") || lower.equals("referer")
                    || lower.equals("accept-encoding") || lower.startsWith("sec-fetch")
                    || lower.startsWith("x-forwarded") || lower.equals("upgrade") || lower.equals("expect")
                    || lower.equals("transfer-encoding") || lower.equals("te") || lower.equals("via")
                    || lower.startsWith("proxy-")) {
                continue;
            }
            for (String value : en.getValue()) {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                    // JDK HttpClient 受限头（Upgrade 等）直接跳过，不影响转发
                }
            }
        }
        builder.header("Origin", base.getScheme() + "://" + base.getRawAuthority());
        builder.header("Referer", session.upstream + "/");
        builder.header("Accept-Encoding", "gzip, deflate");
        String cookie = cookieHeader(session, base.getHost());
        if (!cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length > 0) {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("live proxy interrupted", e);
        }
        writeResponse(exchange, session, response);
    }

    private void writeResponse(HttpExchange exchange, Session session, HttpResponse<byte[]> response) throws IOException {
        int status = response.statusCode();
        Map<String, List<String>> rh = response.headers().map();
        byte[] out = response.body();
        String contentType = firstHeader(rh, "content-type", "application/octet-stream");
        String contentEncoding = firstHeader(rh, "content-encoding", "");
        boolean html = contentType.toLowerCase(Locale.ROOT).contains("text/html");
        if (html && out.length > 0) {
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                out = inflate(out);
            }
            out = inject(session, out);
        }
        // 重定向 Location 改写为代理相对路径，避免跳出代理
        List<String> location = rh.get("location");
        if (location != null && !location.isEmpty()) {
            String loc = location.get(0);
            String rewritten = rewriteLocation(session, loc);
            rh = new LinkedHashMap<>(rh);
            rh.put("location", List.of(rewritten));
        }
        var respHeaders = exchange.getResponseHeaders();
        for (Map.Entry<String, List<String>> en : rh.entrySet()) {
            String name = en.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("content-length") || lower.equals("transfer-encoding")
                    || lower.equals("set-cookie") || lower.equals("content-encoding")
                    || lower.equals("connection") || lower.equals("keep-alive")
                    || lower.equals("date") || lower.equals("server")) {
                continue;
            }
            boolean stripped = false;
            for (String s : STRIP_RESPONSE_HEADERS) {
                if (lower.equals(s)) {
                    stripped = true;
                    break;
                }
            }
            if (stripped) {
                continue;
            }
            for (String value : en.getValue()) {
                respHeaders.add(name, value);
            }
        }
        for (String setCookie : rh.getOrDefault("set-cookie", List.of())) {
            String rewritten = storeCookie(session, setCookie);
            if (rewritten != null) {
                respHeaders.add("Set-Cookie", rewritten);
            }
        }
        if (status >= 300 && status < 400) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        }
    }

    private String rewriteLocation(Session session, String location) {
        try {
            URI uri = URI.create(location);
            if (uri.isAbsolute()) {
                URI base = URI.create(session.upstream);
                if (!base.getHost().equals(uri.getHost())) {
                    return location; // 外部地址（OAuth 等）原样放行
                }
                String query = uri.getRawQuery();
                return session.publicPath() + uri.getRawPath() + (query == null ? "" : "?" + query);
            }
            if (location.startsWith("/")) {
                return session.publicPath() + location;
            }
            return session.publicPath() + "/" + location;
        } catch (Exception e) {
            return session.publicPath() + "/";
        }
    }

    // ------------------------------------------------------------------ Cookie 罐

    /**
     * 解析上游 Set-Cookie：值存入服务端罐，并改写为代理域可用的 Cookie
     * （去 Domain/Secure/HttpOnly/SameSite，Path 固定为 /，保留 Max-Age/Expires）。
     */
    private String storeCookie(Session session, String setCookie) {
        if (setCookie == null || setCookie.isEmpty()) {
            return null;
        }
        String pair = setCookie.split(";", 2)[0];
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        URI base = URI.create(session.upstream);
        session.cookieJar.computeIfAbsent(base.getHost(), k -> new ConcurrentHashMap<>()).put(name, value);
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value).append("; Path=/");
        for (String attr : setCookie.split(";")) {
            String raw = attr.trim();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.startsWith("max-age=") || lower.startsWith("expires=")) {
                sb.append("; ").append(raw);
            }
        }
        return sb.toString();
    }

    /** 汇总要发给上游的 Cookie 头（含父域匹配）。 */
    private String cookieHeader(Session session, String host) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> en : session.cookieJar.entrySet()) {
            String domain = en.getKey();
            if (host.equals(domain) || host.endsWith("." + domain)) {
                for (Map.Entry<String, String> c : en.getValue().entrySet()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(c.getKey()).append('=').append(c.getValue());
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 脚本注入

    private byte[] inject(Session session, byte[] html) {
        String page = rewriteRootAbsoluteUrls(new String(html, StandardCharsets.UTF_8), session.publicPath());
        String script = bootstrapScript(session.token)
                + (session.injectScript == null ? "" : session.injectScript);
        int idx = lastIndexOfIgnoreCase(page, "</head>");
        if (idx < 0) {
            idx = lastIndexOfIgnoreCase(page, "</body>");
        }
        String out = idx >= 0 ? page.substring(0, idx) + script + page.substring(idx) : script + page;
        return out.getBytes(StandardCharsets.UTF_8);
    }

    /** 根绝对路径引用：href/src/action 等属性值以单个 / 开头（排除协议相对 //host）。 */
    private static final Pattern ROOT_ABSOLUTE_ATTR = Pattern.compile(
            "(?i)(\\s(?:href|src|action|poster|formaction|data-src)\\s*=\\s*[\"'])/(?!/)");

    /** 内联样式里的根绝对 url() 引用，含可选引号。 */
    private static final Pattern ROOT_ABSOLUTE_STYLE_URL = Pattern.compile(
            "(?i)(url\\(\\s*['\"]?)/(?!/)");

    /**
     * 重写 HTML 中的根绝对路径引用，拼上会话前缀：根绝对路径否则会脱离代理
     * 会话直接打到代理根路径上（src="/static/..."、form action="/login" 均属此类）。
     * 动态请求（fetch / XHR）由引导脚本在页面内补丁。
     */
    private String rewriteRootAbsoluteUrls(String html, String prefix) {
        String quoted = Matcher.quoteReplacement(prefix);
        String rewritten = ROOT_ABSOLUTE_ATTR.matcher(html).replaceAll("$1" + quoted + "/");
        return ROOT_ABSOLUTE_STYLE_URL.matcher(rewritten).replaceAll("$1" + quoted + "/");
    }

    /**
     * 引导脚本：向页面暴露 __chuaLive 前缀与 __chuaCapturePost 回传函数；
     * 并补丁 fetch / XMLHttpRequest，把页面里根绝对路径的动态请求
     * （fetch('/api/...') 等）重写到会话前缀下，避免脱离代理会话。
     */
    private String bootstrapScript(String token) {
        return "<script>(function(){var P='/live/p/" + token + "';"
                + "window.__chuaLive={token:'" + token + "',prefix:P};"
                + "function fix(u){try{if(typeof u==='string'&&u.charAt(0)==='/'&&u.charAt(1)!=='/'&&u.indexOf(P)!==0){return P+u}}catch(e){}return u}"
                + "var of=window.fetch;"
                + "if(of){window.fetch=function(u,o){try{"
                + "if(u&&typeof u==='object'&&u.url){u=new Request(fix(u.url),u)}else{u=fix(u)}"
                + "}catch(e){}return of.call(this,u,o)}}"
                + "var oo=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(){try{"
                + "if(arguments.length>1){arguments[1]=fix(arguments[1])}"
                + "}catch(e){}return oo.apply(this,arguments)};"
                + "window.__chuaCapturePost=function(p){try{fetch(P+'/__chua_capture',{method:'POST',"
                + "headers:{'Content-Type':'application/json'},body:JSON.stringify(p||{})});}catch(e){}};"
                + "})();</script>";
    }

    private static int lastIndexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.ROOT).lastIndexOf(needle.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ 杂项

    private static String firstHeader(Map<String, List<String>> headers, String name, String def) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            for (Map.Entry<String, List<String>> en : headers.entrySet()) {
                if (en.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                    values = en.getValue();
                    break;
                }
            }
        }
        return values == null || values.isEmpty() ? def : values.get(0);
    }

    private static byte[] inflate(byte[] gz) throws IOException {
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ------------------------------------------------------------------ 空闲回收

    private void reapLoop() {
        while (running.get()) {
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            sessions.values().removeIf(s -> now - s.lastUsed > IDLE_TIMEOUT_MS);
        }
    }
}
