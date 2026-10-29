package com.chua.openai.support.inception;

import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.browser.cdp.CdpBrowserEngine;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于真实 Chromium 的 Inception 传输通道
 *
 * <p>上游被 Vercel 安全网关保护，网关按 TLS / HTTP2 指纹判定调用方，
 * 纯 HTTP 客户端即使用同一份 Cookie、同一 User-Agent 也会被 429 拒绝。
 * 本通道改为「在浏览器页面里发请求」：</p>
 * <ol>
 *   <li>启动本机 Chrome / Chromium（无头模式，独立临时用户目录）；</li>
 *   <li>打开站点首页，等待 Vercel 检查点的 WASM 挑战自动完成；</li>
 *   <li>{@code GET /api/session} 拿本轮会话 token；</li>
 *   <li>{@code POST /api/chat} 并把 SSE 分片逐块转给 {@link InceptionStreamParser}。</li>
 * </ol>
 *
 * <p>浏览器生命周期与 CDP 协议细节由 common-starter 的轻量
 * {@link BrowserEngine}（SPI 名称 {@code cdp}）负责，本类只保留 Inception 站点
 * 特有的「就绪判定 + token + 协议」逻辑。</p>
 *
 * <p>一个传输通道只启动一个浏览器进程，页面在多次对话之间复用；仅当页面失效
 * 或显式 {@link #close()} 时才关闭。这样连续多轮对话不会反复启动浏览器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class InceptionBrowserTransport implements InceptionTransport {

    /**
     * 站点就绪等待时间（毫秒）
     */
    private static final long READY_TIMEOUT_MILLIS = 120_000L;

    /**
     * 被检查点拦截时的最大尝试次数
     */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 重试基础等待时间（毫秒），实际等待按次数递增
     */
    private static final long RETRY_DELAY_MILLIS = 6_000L;

    /**
     * 页面已加载完成但界面未就绪时，等待多久改用接口直接探测（毫秒）
     *
     * <p>实测站点有时一直停在检查点页，但同源的 {@code /api/chat} 已经可用，
     * 此时继续等输入框只会白等；先探测接口，失败再按重试策略处理。</p>
     */
    private static final long PROCEED_AFTER_READY_MILLIS = 12_000L;

    /**
     * 站点根地址
     */
    private final String baseUrl;

    /**
     * 单轮对话超时（毫秒）
     */
    private final long timeoutMillis;

    /**
     * 浏览器路径，可为空
     */
    private final String chromePath;

    /**
     * 是否无头模式
     */
    private final boolean headless;

    /**
     * 浏览器代理，可为空
     */
    private final String proxy;

    /**
     * 自定义 UA，可为空
     */
    private final String userAgent;

    /**
     * 是否随机轮换 UA
     */
    private final boolean rotateUserAgent;

    /**
     * 串行化对话，页面内同一时刻只允许一条流
     */
    private final Object lock = new Object();

    /**
     * 浏览器引擎
     */
    private BrowserEngine engine;

    /**
     * 当前页面
     */
    private BrowserPage page;

    /**
     * 构造浏览器通道
     *
     * @param baseUrl       站点根地址
     * @param timeoutMillis 单轮对话超时（毫秒）
     * @param chromePath    浏览器路径，可为空（自动探测）
     * @param headless      是否无头模式
     * @param proxy         浏览器代理，可为空
     * @param userAgent     自定义 UA，可为空
     * @param rotateUserAgent 是否随机轮换 UA
     */
    public InceptionBrowserTransport(String baseUrl, long timeoutMillis, String chromePath,
                                     boolean headless, String proxy, String userAgent,
                                     boolean rotateUserAgent) {
        this.baseUrl = baseUrl;
        this.timeoutMillis = timeoutMillis;
        this.chromePath = chromePath;
        this.headless = headless;
        this.proxy = proxy;
        this.userAgent = userAgent;
        this.rotateUserAgent = rotateUserAgent;
    }

    @Override
    public void chat(String payloadJson, InceptionStreamListener listener) throws Exception {
        synchronized (lock) {
            ensurePage();
            IllegalStateException lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                lastError = null;
                String token = readSessionToken();

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("content-type", "application/json");
                headers.put("accept", "text/event-stream");
                if (StringUtils.isNotBlank(token)) {
                    headers.put("x-session-token", token);
                }

                InceptionStreamParser parser = new InceptionStreamParser(listener);
                StringBuilder errorBody = new StringBuilder();
                int[] statusHolder = {0};
                boolean[] contentReceived = {false};
                int status;
                try {
                    status = page.fetchStream("POST", baseUrl + "/api/chat", payloadJson, headers,
                            timeoutMillis, new BrowserPage.FetchSink() {
                                @Override
                                public void onStatus(int code) {
                                    statusHolder[0] = code;
                                }

                                @Override
                                public void onChunk(String chunk) {
                                    if (statusHolder[0] == 200) {
                                        contentReceived[0] = true;
                                        parser.feed(chunk);
                                    } else {
                                        errorBody.append(chunk == null ? "" : chunk);
                                    }
                                }
                            });
                } catch (IllegalStateException e) {
                    if (isPageAlive()) {
                        throw e;
                    }
                    closeBrowser();
                    throw new IllegalStateException("Inception 浏览器页面已失效，请重试: " + e.getMessage(), e);
                }

                parser.flush();
                if (status == 200) {
                    if (parser.error() != null) {
                        throw new IllegalStateException("Inception 上游返回错误: " + parser.error());
                    }
                    return;
                }

                lastError = new IllegalStateException(describeFailure(status, errorBody.toString()));
                boolean challenged = (status == 429 || status == 403) && !contentReceived[0];
                if (!challenged || attempt >= MAX_ATTEMPTS) {
                    throw lastError;
                }
                long delay = RETRY_DELAY_MILLIS * attempt;
                log.warn("[inception] 第 {} 次请求被 Vercel 检查点拦截，{}ms 后重试", attempt, delay);
                sleep(delay);
                if (attempt >= 2) {
                    refreshPage();
                }
            }
            if (lastError != null) {
                throw lastError;
            }
        }
    }

    /**
     * 重新加载站点并等待检查点通过
     */
    private void refreshPage() {
        try {
            page.navigate(baseUrl + "/");
            waitReady();
        } catch (RuntimeException e) {
            log.warn("[inception] 刷新页面失败，重建浏览器: {}", e.getMessage());
            closeBrowser();
            ensurePage();
        }
    }

    /**
     * 确保页面已打开且检查点已通过
     */
    private void ensurePage() {
        if (page != null && isPageAlive()) {
            return;
        }
        closeBrowser();
        engine = createEngine();
        BrowserOptions options = new BrowserOptions()
                .setHeadless(headless)
                .setExecutablePath(locateBrowser(chromePath))
                .setProxy(proxy)
                .setUserAgent(userAgent)
                .setRotateUserAgent(rotateUserAgent);
        try {
            page = engine.openPage(options);
            page.navigate(baseUrl + "/");
            waitReady();
        } catch (RuntimeException e) {
            closeBrowser();
            throw e;
        }
    }

    /**
     * 创建浏览器引擎，优先走 SPI
     *
     * @return 浏览器引擎
     */
    private static BrowserEngine createEngine() {
        try {
            return BrowserEngine.cdp();
        } catch (RuntimeException e) {
            log.debug("[inception] SPI 未找到 cdp 引擎，使用内置实现: {}", e.getMessage());
            return new CdpBrowserEngine();
        }
    }

    /**
     * 页面是否仍可执行脚本
     *
     * @return true 表示可复用
     */
    private boolean isPageAlive() {
        try {
            page.evaluate("1", false);
            return true;
        } catch (Exception e) {
            log.debug("[inception] 浏览器页面已失效，将重新启动: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 等待站点就绪（检查点通过且输入框出现）
     *
     * <p>正常情况下几秒内就能看到输入框；若页面已加载完成但长时间停在检查点页
     * （实测此时同源接口可能已经可用），不再死等，先交给接口探测，避免白等到超时。</p>
     */
    private void waitReady() {
        boolean hosted = baseUrl.contains("inceptionlabs.ai");
        long start = System.currentTimeMillis();
        long deadline = start + READY_TIMEOUT_MILLIS;
        String lastState = "";
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> state = pageState();
            lastState = Json.toJson(state);
            boolean blocked = Boolean.TRUE.equals(state.get("blocked"));
            boolean complete = "complete".equals(state.get("ready"));
            boolean inputReady = Boolean.TRUE.equals(state.get("input"));
            if (!blocked && complete && (inputReady || !hosted)) {
                log.debug("[inception] 站点已就绪: {}", lastState);
                return;
            }
            if (complete && System.currentTimeMillis() - start >= PROCEED_AFTER_READY_MILLIS) {
                log.warn("[inception] 页面停留 {}ms 仍未出现输入框，改用接口探测: {}",
                        PROCEED_AFTER_READY_MILLIS, lastState);
                return;
            }
            sleep(500L);
        }
        throw new IllegalStateException("等待 Inception 站点就绪超时，最后一次页面状态: "
                + truncate(lastState));
    }

    /**
     * 读取页面当前状态
     *
     * @return 状态（ready / blocked / input / title）
     */
    private Map<String, Object> pageState() {
        String expression = "JSON.stringify({"
                + "ready:document.readyState,"
                + "title:document.title||'',"
                + "blocked:/Security Checkpoint|无法验证您的浏览器|Just a moment/.test((document.title||'')"
                + "+((document.body&&document.body.innerText)||'')),"
                + "input:!!document.querySelector('textarea')"
                + "})";
        String value = page.evaluate(expression);
        try {
            Map<String, Object> state = Json.fromJson(value);
            return state == null ? Map.of() : state;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 读取本轮会话 token
     *
     * @return 会话 token，可能为空串
     */
    private String readSessionToken() {
        String script = "(async () => {"
                + "try {"
                + "const r = await fetch('/api/session', {credentials:'include', headers:{accept:'application/json'}});"
                + "const t = await r.text();"
                + "return JSON.stringify({status:r.status, body:t});"
                + "} catch (e) { return JSON.stringify({status:-1, body:String(e)}); }"
                + "})()";
        String value = page.evaluate(script);
        Map<String, Object> result;
        try {
            result = Json.fromJson(value);
        } catch (Exception e) {
            throw new IllegalStateException("解析 /api/session 结果失败: " + value, e);
        }
        if (result == null) {
            throw new IllegalStateException("读取 /api/session 无返回");
        }
        Object status = result.get("status");
        int code = status instanceof Number number ? number.intValue() : -1;
        String body = asString(result.get("body"));
        if (code != 200) {
            throw new IllegalStateException(describeFailure(code, body));
        }
        Map<String, Object> parsed;
        try {
            parsed = Json.fromJson(body);
        } catch (Exception e) {
            throw new IllegalStateException("解析 /api/session 响应失败: " + truncate(body), e);
        }
        String token = parsed == null ? null : asString(parsed.get("token"));
        if (StringUtils.isBlank(token)) {
            log.warn("[inception] /api/session 未返回 token，将不带 x-session-token 请求");
            return "";
        }
        return token;
    }

    /**
     * 关闭浏览器并释放资源
     */
    private void closeBrowser() {
        if (page != null) {
            try {
                page.close();
            } catch (Exception e) {
                log.debug("[inception] 关闭页面失败: {}", e.getMessage());
            }
            page = null;
        }
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception e) {
                log.debug("[inception] 关闭浏览器失败: {}", e.getMessage());
            }
            engine = null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeBrowser();
        }
    }

    /**
     * 定位可用的浏览器
     *
     * @param configured 显式配置的路径，可为空
     * @return 可执行文件路径
     */
    static String locateBrowser(String configured) {
        return CdpBrowserEngine.locateBrowser(configured);
    }

    /**
     * 组装错误信息，并对风控页给出可操作的提示
     *
     * @param status 状态码
     * @param body   响应体
     * @return 错误描述
     */
    private static String describeFailure(int status, String body) {
        String text = body == null ? "" : body.trim();
        if (status == 429 || status == 403 || text.contains("Security Checkpoint")
                || text.contains("challenge")) {
            return "Inception 上游被 Vercel 安全网关拦截（HTTP " + status + "）：浏览器未能通过人机验证，"
                    + "请确认本机 Chrome 可正常打开 " + "https://chat.inceptionlabs.ai"
                    + "（无头模式被判定时可用 -Dinception.headless=false 走有头模式）。响应片段: " + truncate(text);
        }
        return "Inception 上游返回 HTTP " + status + "，响应片段: " + truncate(text);
    }

    /**
     * 截断文本
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    /**
     * 安全转换字符串
     *
     * @param value 原始值
     * @return 字符串，类型不符返回 null
     */
    private static String asString(Object value) {
        return value instanceof String str ? str : null;
    }

    /**
     * 休眠
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
