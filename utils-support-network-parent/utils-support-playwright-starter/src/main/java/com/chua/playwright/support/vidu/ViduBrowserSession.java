// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.playwright.support.vidu;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.utils.StringUtils;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Vidu 浏览器会话。
 *
 * <p>基于 Playwright 启动 Chromium，将登录态 Cookie 注入 {@code .vidu.cn} 域，
 * 加载 Vidu 首页后，在同一页面上下文内以原生 {@code fetch} 调用
 * {@code service.vidu.cn} 的任务接口（{@code credentials: 'include'}）。
 * 这与 Vidu 网页前端自身的请求方式完全一致：中国站无签名墙 / WAF 令牌要求，
 * Cookie 即为完整登录态，无需复刻任何签名算法。</p>
 *
 * <p>会话内维护一个常驻页面：所有接口调用都在该页面上执行，避免每次请求
 * 重新导航带来的开销（视频生成需要持续轮询数分钟）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
public class ViduBrowserSession implements AutoCloseable {

    /**
     * Vidu 首页地址，用于建立登录态上下文。
     */
    public static final String HOME_URL = "https://www.vidu.cn/";

    /**
     * Cookie 注入域（覆盖 www / service 等全部子域）。
     */
    public static final String VIDU_DOMAIN = ".vidu.cn";

    /**
     * 浏览器启动超时。
     */
    private static final Duration LAUNCH_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 页面加载超时。
     */
    private static final Duration NAV_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 反检测启动参数（降低无头浏览器指纹被识别概率）。
     */
    private static final String[] STEALTH_ARGS = {
            "--disable-blink-features=AutomationControlled",
            "--no-sandbox",
            "--disable-web-security",
            "--disable-features=IsolateOrigins,site-per-process",
            "--disable-site-isolation-trials",
            "--disable-setuid-sandbox",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions"
    };

    /**
     * 页面内 fetch 脚本（GET / POST 通用，携带 Vidu 网页端同款请求头）。
     */
    private static final String FETCH_SCRIPT = """
            /**
             * async
             * @param args 参数
             * @return 返回值
             */
            async (args) => {
                const headers = {
                    'Accept': 'application/json, text/plain, */*',
                    'Accept-Language': 'zh-CN,zh;q=0.9'
                };
                if (args.body) {
                    headers['Content-Type'] = 'application/json';
                }
                if (args.method !== 'GET') {
                    headers['X-Platform'] = 'web';
                    try { headers['X-Request-Id'] = crypto.randomUUID(); } catch (e) { /* 随机源不可用时省略 */ }
                    const appVersion = window.__APP_VERSION__ || window.__VIDU_APP_VERSION__ || '';
                    if (appVersion) { headers['X-App-Version'] = String(appVersion); }
                }
                const resp = await fetch(args.url, {
                    method: args.method,
                    headers: headers,
                    body: args.body ? args.body : undefined,
                    credentials: 'include'
                });
                const text = await resp.text();
                return { status: resp.status, ok: resp.ok, text: text };
            }
            """;

    /**
     * Playwright 实例。
     */
    private final Playwright playwright;

    /**
     * 浏览器实例。
     */
    private final Browser browser;

    /**
     * 浏览器上下文。
     */
    private final BrowserContext context;

    /**
     * 会话 Cookie 解析结果。
     */
    private final Map<String, String> cookies;

    /**
     * 常驻页面（init 后可用）。
     */
    private Page page;

    /**
     * 构造 Vidu 浏览器会话。
     *
     * @param cookieString Cookie 串，形如 "sessionid=...; uid=...; ..."
     */
    public ViduBrowserSession(String cookieString) {
        this.cookies = parseCookies(cookieString);
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of(STEALTH_ARGS))
                .setTimeout(LAUNCH_TIMEOUT.toMillis()));
        this.context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 720)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/130.0.0.0 Safari/537.36"));
        this.context.setDefaultNavigationTimeout(NAV_TIMEOUT.toMillis());
    }

    /**
     * 启动会话：注入 Cookie 并加载 Vidu 首页建立登录态上下文。
     */
    public void init() {
        injectCookies();
        this.page = context.newPage();
        page.navigate(HOME_URL);
        log.info("Vidu 首页加载完成，Cookie 登录态已就绪");
    }

    /**
     * 在页面上下文内发起一次 HTTP 请求并解析 JSON 响应。
     *
     * @param method   HTTP 方法（GET / POST / PATCH）
     * @param url      完整请求地址
     * @param jsonBody JSON 请求体字符串，GET 请求传 {@code null}
     * @return 解析后的 JSON 映射（空响应返回空映射）
     * @throws IllegalStateException HTTP 非 2xx、响应非 JSON 或页面执行失败时抛出
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchJson(String method, String url, String jsonBody) {
        JsonObject args = JsonObject.create()
                .fluent("method", method)
                .fluent("url", url)
                .fluent("body", jsonBody == null ? "" : jsonBody);
        try {
            Object result = page.evaluate(FETCH_SCRIPT, args);
            if (!(result instanceof Map<?, ?> raw)) {
                throw new IllegalStateException("浏览器脚本未返回有效结果");
            }
            boolean ok = Boolean.TRUE.equals(raw.get("ok"));
            int status = raw.get("status") instanceof Number n ? n.intValue() : 0;
            String text = raw.get("text") instanceof String s ? s : "";
            if (!ok) {
                throw new IllegalStateException("Vidu 接口响应异常 HTTP " + status + ": " + abbreviate(text));
            }
            if (StringUtils.isBlank(text)) {
                return Wraps.linkedMap();
            }
            Map<String, Object> parsed = Json.fromJson(text, Map.class);
            return parsed == null ? Wraps.linkedMap() : parsed;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Vidu 页面请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        Exception ex = null;
        try {
            if (page != null) {
                page.close();
            }
        } catch (Exception e) {
            ex = e;
        }
        try {
            context.close();
        } catch (Exception e) {
            if (ex == null) {
                ex = e;
            }
        }
        try {
            browser.close();
        } catch (Exception e) {
            if (ex == null) {
                ex = e;
            }
        }
        try {
            playwright.close();
        } catch (Exception e) {
            if (ex == null) {
                ex = e;
            }
        }
        if (ex != null) {
            log.warn("释放 Vidu 浏览器会话失败", ex);
        }
    }

    /**
     * 注入认证 Cookie 到 Vidu 主域。
     */
    private void injectCookies() {
        List<com.microsoft.playwright.options.Cookie> cookieList = Wraps.list(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            cookieList.add(new com.microsoft.playwright.options.Cookie(entry.getKey(), entry.getValue())
                    .setDomain(VIDU_DOMAIN)
                    .setPath("/"));
        }
        context.addCookies(cookieList);
    }

    /**
     * 解析 Cookie 串。
     *
     * @param cookieString Cookie 字符串
     * @return 键值对映射
     */
    private static Map<String, String> parseCookies(String cookieString) {
        Map<String, String> map = Wraps.linkedMap();
        if (StringUtils.isBlank(cookieString)) {
            return map;
        }
        for (String pair : cookieString.split(";")) {
            String trim = pair.trim();
            int idx = trim.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            map.put(trim.substring(0, idx).trim(), trim.substring(idx + 1).trim());
        }
        return map;
    }

    /**
     * 截断长文本用于错误信息展示。
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
