// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.duckduckgo.support;

import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.browser.cdp.CdpBrowserEngine;
import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Duck.ai 浏览器通道
 *
 * <p>与 Inception 不同，Duck.ai 的接口全部挂在 {@code duck.ai} 同源路径下，
 * 但每次对话前要执行官方下发的一段挑战脚本（{@code x-vqd-hash-1}）：
 * 脚本会读取 {@code navigator.userAgent}、测量 DOM 尺寸，再由客户端把结果
 * 做 SHA-256 后拼成请求头。因此这里必须在真实页面上下文里执行挑战，不能硬编码。</p>
 *
 * <h2>请求流程</h2>
 * <ol>
 *   <li>{@code GET /duckchat/v1/status}（{@code x-vqd-accept: 1}）拿到挑战脚本；</li>
 *   <li>在页面里 {@code eval} 脚本，得到 server/client 哈希原文；</li>
 *   <li>对 client 原文做 SHA-256 + Base64，拼成 {@code x-vqd-hash-1}；</li>
 *   <li>{@code POST /duckchat/v1/chat} 取 SSE，逐段回调 {@code message}。</li>
 * </ol>
 *
 * @author CHUA
 */
@Slf4j
public class DuckBrowserTransport implements AutoCloseable {

    /**
     * 默认模型
     */
    static final String DEFAULT_MODEL = "gpt-6-luna";

    /**
     * 检查点重试次数
     */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 重试基础等待（毫秒）
     */
    private static final long RETRY_DELAY_MILLIS = 2_000L;

    /**
     * 页面就绪等待上限（毫秒）
     */
    private static final long READY_TIMEOUT_MILLIS = 20_000L;

    /**
     * 站点根地址
     */
    private final String baseUrl;

    /**
     * 单轮超时（毫秒）
     */
    private final long timeoutMillis;

    /**
     * 浏览器路径
     */
    private final String chromePath;

    /**
     * 是否无头
     */
    private final boolean headless;

    /**
     * 代理
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
     * 并发锁
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
     * 页面版本号
     */
    private String version = "-";

    /**
     * 构造通道
     *
     * @param baseUrl       站点根地址
     * @param timeoutMillis 单轮超时
     * @param chromePath    浏览器路径，可为空
     * @param headless      是否无头
     * @param proxy         代理，可为空
     * @param userAgent     自定义 UA，可为空
     * @param rotateUserAgent 是否随机轮换 UA
     */
    public DuckBrowserTransport(String baseUrl, long timeoutMillis, String chromePath,
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

    /**
     * 发起一轮对话
     *
     * @param model    模型
     * @param messages 消息列表
     * @param listener 文本回调
     */
    void chat(String model, List<Map<String, Object>> messages, DuckStreamListener listener) {
        chat(model, messages, listener, false, null, null, 1, null, null);
    }

    /**
     * 发起一轮对话，支持文生图
     *
     * <p>图像生成走同一条 {@code /duckchat/v1/chat} SSE 流：请求体带上
     * {@code canDelegateImageGeneration=true} 与 {@code toolChoice.GenerateImage=true}，
     * 流内可能出现 {@code tool-invocation}、{@code generated-image}、
     * {@code partial-image} 事件，解析器把图片数据交给 {@code listener.onImage}。</p>
     *
     * @param model    模型
     * @param messages 消息列表
     * @param listener 文本与图片回调
     * @param imageGeneration  是否启用文生图
     * @param imageWidth       目标宽度，可为空
     * @param imageHeight      目标高度，可为空
     * @param imageCount       期望生成数量，最小 1
     * @param quality          图像质量，可为空
     * @param refImageKey      参考图标识，可为空
     */
    void chat(String model, List<Map<String, Object>> messages, DuckStreamListener listener,
              boolean imageGeneration, Integer imageWidth, Integer imageHeight,
              int imageCount, String quality, String refImageKey) {
        synchronized (lock) {
            ensurePage();
            IllegalStateException lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                String journeyId = randomId();
                Challenge challenge = solveChallenge(journeyId);
                if (challenge.header == null || challenge.header.isEmpty()) {
                    if (attempt < MAX_ATTEMPTS) {
                        log.warn("[duck] 获取挑战脚本失败（第 {} 次）: status={} body={}，稍后重试",
                                attempt, challenge.status, challenge.body);
                        if (challenge.status == -1) {
                            reloadPage();
                        }
                        sleep(RETRY_DELAY_MILLIS);
                        continue;
                    }
                    throw new IllegalStateException("Duck.ai 未返回挑战脚本: status=" + challenge.status
                            + " body=" + challenge.body);
                }

                Map<String, String> headers = buildHeaders(challenge.header, journeyId);
                String payload = buildPayload(model, messages, imageGeneration,
                        imageWidth, imageHeight, imageCount, quality, refImageKey);
                StringBuilder errorBody = new StringBuilder();
                DuckStreamParser parser = new DuckStreamParser(listener);
                int[] statusHolder = {0};
                int status = page.fetchStream("POST", baseUrl + "/duckchat/v1/chat", payload, headers,
                        timeoutMillis, new BrowserPage.FetchSink() {
                            @Override
                            public void onStatus(int code) {
                                statusHolder[0] = code;
                            }

                            @Override
                            public void onChunk(String chunk) {
                                if (statusHolder[0] == 200) {
                                    parser.feed(chunk);
                                } else {
                                    errorBody.append(chunk == null ? "" : chunk);
                                }
                            }
                        });
                parser.flush();

                if (status == 200 && parser.error() == null) {
                    return;
                }
                if (status == 200) {
                    throw new IllegalStateException("Duck.ai 返回错误: " + parser.describeError());
                }

                lastError = new IllegalStateException(describeFailure(status, errorBody.toString()));
                boolean retryable = status == 418 || status == 429 || status == 403;
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw lastError;
                }
                log.warn("[duck] 第 {} 次请求被上游拦截（HTTP {}），{}ms 后重试", attempt, status,
                        RETRY_DELAY_MILLIS * attempt);
                sleep(RETRY_DELAY_MILLIS * attempt);
            }
            if (lastError != null) {
                throw lastError;
            }
        }
    }

    /**
     * 确保页面可用
     */
    private void ensurePage() {
        if (page != null && isPageAlive()) {
            return;
        }
        closeBrowser();
        engine = createEngine();
        BrowserOptions options = new BrowserOptions()
                .setHeadless(headless)
                .setProxy(proxy)
                .setUserAgent(userAgent)
                .setRotateUserAgent(rotateUserAgent);
        if (chromePath != null && !chromePath.isBlank()) {
            options.setExecutablePath(chromePath);
        }
        try {
            page = engine.openPage(options);
            page.navigate(baseUrl + "/");
            waitReady();
            version = readVersion();
        } catch (RuntimeException e) {
            closeBrowser();
            throw e;
        }
    }

    /**
     * 创建浏览器引擎
     *
     * @return 引擎
     */
    private static BrowserEngine createEngine() {
        try {
            return BrowserEngine.cdp();
        } catch (RuntimeException e) {
            return new CdpBrowserEngine();
        }
    }

    /**
     * 页面是否可执行脚本
     *
     * @return true 表示可复用
     */
    private boolean isPageAlive() {
        try {
            page.evaluate("1", false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等待页面可交互
     *
     * <p>即使输入框迟迟不出现，只要文档加载完成就继续：挑战与对话都走接口，
     * 不依赖界面渲染。</p>
     */
    private void waitReady() {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                String state = page.evaluate("JSON.stringify({"
                        + "ready:document.readyState,"
                        + "input:!!document.querySelector('textarea,[contenteditable=true]')})");
                Map<String, Object> data = Json.fromJson(state);
                if (data != null && "complete".equals(data.get("ready"))
                        && Boolean.TRUE.equals(data.get("input"))) {
                    return;
                }
            } catch (RuntimeException e) {
                log.debug("[duck] 读取页面状态失败: {}", e.getMessage());
            }
            sleep(300L);
        }
        log.debug("[duck] 页面未出现输入框，直接使用接口");
    }

    /**
     * 重新加载页面
     *
     * <p>浏览器可能停在错误页或代理隧道中断，重新导航一次即可恢复。</p>
     */
    private void reloadPage() {
        try {
            page.navigate(baseUrl + "/");
            waitReady();
            version = readVersion();
        } catch (RuntimeException e) {
            log.warn("[duck] 重新加载页面失败: {}", e.getMessage());
            closeBrowser();
            ensurePage();
        }
    }

    /**
     * 读取页面版本号
     *
     * @return 形如 {@code serp_xxx-sha} 的版本
     */
    private String readVersion() {
        try {
            String value = page.evaluate("JSON.stringify({"
                    + "tag:document.documentElement.getAttribute('data-version-tag')||'',"
                    + "sha:document.documentElement.getAttribute('data-version-sha')||''})");
            Map<String, Object> data = Json.fromJson(value);
            if (data == null) {
                return "-";
            }
            String tag = String.valueOf(data.getOrDefault("tag", ""));
            String sha = String.valueOf(data.getOrDefault("sha", ""));
            if (tag.isEmpty()) {
                return "-";
            }
            return sha.isEmpty() ? tag : tag + "-" + sha;
        } catch (RuntimeException e) {
            return "-";
        }
    }

    /**
     * 执行挑战脚本
     *
     * @param journeyId 请求链路 ID
     * @return 挑战结果
     */
    private Challenge solveChallenge(String journeyId) {
        String expression = "(async()=>{"
                + "try{"
                + "const s=await fetch('/duckchat/v1/status',{credentials:'include',"
                + "headers:{'x-vqd-accept':'1','x-ddg-journey-id':'" + journeyId + "'}});"
                + "const ch=s.headers.get('x-vqd-hash-1');"
                + "const body=(await s.text()).slice(0,200);"
                + "if(!ch)return JSON.stringify({status:s.status,body:body});"
                + "const r=await eval(atob(ch));"
                + "const enc=new TextEncoder();"
                + "const b64=b=>btoa(Array.from(new Uint8Array(b))"
                + ".map(x=>String.fromCharCode(x)).join(''));"
                + "const hashed=[];"
                + "for(const item of r.client_hashes){"
                + "const raw=typeof item==='string'?item:JSON.stringify(item);"
                + "hashed.push(b64(await crypto.subtle.digest('SHA-256',enc.encode(raw))));}"
                + "const payload={server_hashes:r.server_hashes,client_hashes:hashed,"
                + "signals:r.signals,meta:Object.assign({},r.meta,{origin:location.origin,"
                + "stack:(new Error()).stack,duration:String(Math.round(performance.now()))})};"
                + "return JSON.stringify({status:s.status,header:btoa(JSON.stringify(payload))});"
                + "}catch(e){return JSON.stringify({status:-1,body:'EX '+e+' url='+location.href"
                + "+' ready='+document.readyState});}})()";
        String value;
        try {
            value = page.evaluate(expression);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Duck.ai 执行挑战脚本失败: " + e.getMessage(), e);
        }
        Map<String, Object> data = Json.fromJson(value);
        Challenge challenge = new Challenge();
        if (data == null) {
            challenge.status = -1;
            challenge.body = "空响应";
            return challenge;
        }
        challenge.status = data.get("status") instanceof Number number ? number.intValue() : -1;
        Object header = data.get("header");
        challenge.header = header == null ? null : String.valueOf(header);
        Object body = data.get("body");
        challenge.body = body == null ? "" : String.valueOf(body);
        return challenge;
    }

    /**
     * 构建对话请求头
     *
     * @param vqdHash   挑战结果
     * @param journeyId 请求链路 ID
     * @return 请求头
     */
    private Map<String, String> buildHeaders(String vqdHash, String journeyId) {
        long now = System.currentTimeMillis();
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("start", now);
        signals.put("events", new ArrayList<>());
        signals.put("end", now);
        String signalsValue = Base64.getEncoder().encodeToString(
                Json.toJson(signals).getBytes(StandardCharsets.UTF_8));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("accept", "text/event-stream");
        headers.put("origin", baseUrl);
        headers.put("referer", baseUrl + "/");
        headers.put("x-ddg-journey-id", journeyId);
        headers.put("x-fe-version", version);
        headers.put("x-fe-signals", signalsValue);
        headers.put("x-duckai-response-id", UUID.randomUUID().toString());
        headers.put("x-vqd-hash-1", vqdHash);
        return headers;
    }

    /**
     * 构建请求体
     *
     * @param model           模型
     * @param messages        消息
     * @param imageGeneration 是否启用文生图
     * @param imageWidth      目标宽度，可为空
     * @param imageHeight     目标高度，可为空
     * @param imageCount      期望生成数量
     * @param quality         图像质量，可为空
     * @param refImageKey     参考图标识，可为空
     * @return JSON
     */
    private String buildPayload(String model, List<Map<String, Object>> messages,
                                boolean imageGeneration, Integer imageWidth,
                                Integer imageHeight, int imageCount, String quality,
                                String refImageKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model == null || model.isBlank() ? DEFAULT_MODEL : model);
        payload.put("messages", messages);
        payload.put("canUseTools", imageGeneration);
        payload.put("reasoningEffort", "none");
        payload.put("canDelegateImageGeneration", imageGeneration);
        payload.put("canUseWebSearch", false);
        payload.put("canUploadFiles", false);
        payload.put("canShowGreeting", false);
        if (imageGeneration) {
            Map<String, Object> toolChoice = new LinkedHashMap<>();
            toolChoice.put("GenerateImage", true);
            Map<String, Object> imageSpec = new LinkedHashMap<>();
            if (imageWidth != null) {
                imageSpec.put("width", imageWidth);
            }
            if (imageHeight != null) {
                imageSpec.put("height", imageHeight);
            }
            if (imageCount > 1) {
                imageSpec.put("count", imageCount);
            }
            if (quality != null && !quality.isBlank()) {
                imageSpec.put("quality", quality);
            }
            if (refImageKey != null && !refImageKey.isBlank()) {
                imageSpec.put("refImageKey", refImageKey);
            }
            if (!imageSpec.isEmpty()) {
                toolChoice.put("imageSpec", imageSpec);
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("toolChoice", toolChoice);
            payload.put("metadata", metadata);
        }
        String json = Json.toJson(payload);
        log.debug("[duck] payload={}", json);
        return json;
    }

    /**
     * 构造错误描述
     *
     * @param status 状态码
     * @param body   响应体
     * @return 描述
     */
    private String describeFailure(int status, String body) {
        String detail = body == null ? "" : body.trim();
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "...";
        }
        if (status == 418) {
            return "Duck.ai 拒绝了本次请求（HTTP 418，通常是出口 IP 被风控或频率限制）：" + detail;
        }
        if (status == 429) {
            return "Duck.ai 请求过于频繁（HTTP 429）：" + detail;
        }
        return "Duck.ai 请求失败（HTTP " + status + "）：" + detail;
    }

    /**
     * 生成 32 位十六进制 ID
     *
     * @return ID
     */
    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
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

    /**
     * 关闭浏览器
     */
    private void closeBrowser() {
        if (engine != null) {
            try {
                engine.close();
            } catch (RuntimeException e) {
                log.debug("[duck] 关闭浏览器失败: {}", e.getMessage());
            }
            engine = null;
        }
        page = null;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeBrowser();
        }
    }

    /**
     * 流事件回调
     */
    interface DuckStreamListener {

        /**
         * 收到文本片段
         *
         * @param delta 片段
         */
        void onText(String delta);

        /**
         * 收到生成图片
         *
         * @param b64Image  Base64 图片数据
         * @param format   图片格式，如 {@code png}、{@code jpeg}
         * @param width    宽度
         * @param height   高度
         */
        default void onImage(String b64Image, String format, int width, int height) {
        }
    }

    /**
     * 挑战结果
     */
    private static final class Challenge {

        /**
         * 状态码
         */
        private int status;

        /**
         * 挑战头
         */
        private String header;

        /**
         * 响应体
         */
        private String body;
    }
}
