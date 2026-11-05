// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.browser.cdp;

import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 基于 CDP 的浏览器页面
 *
 * <p>每个实例对应一个浏览器标签页（target）与一个 flatten 会话。</p>
 *
 * <p>「页面内 fetch」通过 {@code Runtime.addBinding} 建立 Java 与页面之间的单向通道：
 * 页面把状态码、响应分片与结束标记序列化成 JSON 回调给 Java，Java 侧用阻塞队列接收。
 * 这样既能流式读取 SSE，也不会把整段响应堆在内存里。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
final class CdpPage implements BrowserPage {

    /**
     * 绑定名序号
     */
    private static final AtomicInteger BINDING_SEQUENCE = new AtomicInteger();

    /**
     * CDP 连接
     */
    private final CdpConnection connection;

    /**
     * 标签页 ID
     */
    private final String targetId;

    /**
     * 会话 ID
     */
    private final String sessionId;

    /**
     * 命令超时
     */
    private final Duration commandTimeout;

    /**
     * 桥接函数名
     */
    private final String binding;

    /**
     * 当前流事件的接收器
     */
    private volatile Consumer<String> sink;

    /**
     * 同一页面同一时刻只跑一条流
     */
    private final Object streamLock = new Object();

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 构造页面
     * @param commandTimeout 命令超时
     * @param connection CDP 连接
     * @param sessionId 会话 ID
     * @param targetId 标签页 ID
     * @return 返回值
    */
    CdpPage(CdpConnection connection, String targetId, String sessionId, Duration commandTimeout) {
        this.connection = connection;
        this.targetId = targetId;
        this.sessionId = sessionId;
        this.commandTimeout = commandTimeout;
        this.binding = "__chuaBrowserBridge_" + BINDING_SEQUENCE.incrementAndGet();
        this.connection.on("Runtime.bindingCalled", this::onBindingCalled);
        this.connection.call("Runtime.addBinding", Map.of("name", binding), sessionId, commandTimeout);
    }

    /**
     * 处理页面桥接回调
     *
     * @param message CDP 事件
     */
    private void onBindingCalled(Map<String, Object> message) {
        Map<String, Object> params = asMap(message.get("params"));
        if (params == null || !binding.equals(params.get("name"))) {
            return;
        }
        Object payload = params.get("payload");
        Consumer<String> current = sink;
        if (current != null && payload != null) {
            current.accept(String.valueOf(payload));
        }
    }

    @Override
    public String url() {
        return evaluate("location.href", false);
    }

    @Override
    public String title() {
        return evaluate("document.title", false);
    }

    @Override
    public String content() {
        return evaluate("document.documentElement ? document.documentElement.outerHTML : ''", false);
    }

    @Override
    public void addInitScript(String script) {
        if (script == null || script.isBlank()) {
            return;
        }
        connection.call("Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", script), sessionId, commandTimeout);
    }

    @Override
    public void navigate(String url) {
        connection.call("Page.navigate", Map.of("url", url), sessionId, commandTimeout);
        waitForDocument();
    }

    /**
     * 等待文档加载完成
     */
    private void waitForDocument() {
        long deadline = System.currentTimeMillis() + commandTimeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                if ("complete".equals(evaluate("document.readyState", false))) {
                    return;
                }
            } catch (Exception e) {
                log.debug("[cdp] 读取 readyState 失败: {}", e.getMessage());
            }
            sleep(100L);
        }
        log.debug("[cdp] 等待文档加载超时: {}", url());
    }

    @Override
    public String evaluate(String expression, boolean awaitPromise) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", awaitPromise);
        params.put("userGesture", false);
        Map<String, Object> result = connection.call("Runtime.evaluate", params, sessionId, commandTimeout);
        Map<String, Object> exception = asMap(result.get("exceptionDetails"));
        if (exception != null) {
            throw new IllegalStateException("页面脚本执行失败: " + Json.toJson(exception));
        }
        Map<String, Object> value = asMap(result.get("result"));
        if (value == null) {
            return null;
        }
        Object raw = value.get("value");
        return raw == null ? null : String.valueOf(raw);
    }

    @Override
    public void setCookies(Map<String, String> cookies, String domain) {
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            Map<String, Object> cookie = new LinkedHashMap<>();
            cookie.put("name", entry.getKey());
            cookie.put("value", entry.getValue());
            cookie.put("domain", domain);
            cookie.put("path", "/");
            list.add(cookie);
        }
        connection.call("Network.setCookies", Map.of("cookies", list), sessionId, commandTimeout);
    }

    @Override
    public void setCookiesForUrl(Map<String, String> cookies, String url) {
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            Map<String, Object> cookie = new LinkedHashMap<>();
            cookie.put("name", entry.getKey());
            cookie.put("value", entry.getValue());
            cookie.put("url", url);
            list.add(cookie);
        }
        connection.call("Network.setCookies", Map.of("cookies", list), sessionId, commandTimeout);
    }

    @Override
    public void setExtraHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        connection.call("Network.setExtraHTTPHeaders",
                Map.of("headers", new LinkedHashMap<>(headers)), sessionId, commandTimeout);
    }

    @Override
    public Map<String, String> cookies() {
        Map<String, Object> result = connection.call("Network.getCookies", Map.of(), sessionId, commandTimeout);
        Map<String, String> cookies = new LinkedHashMap<>();
        Object raw = result.get("cookies");
        if (!(raw instanceof List<?> list)) {
            return cookies;
        }
        for (Object item : list) {
            Map<String, Object> cookie = asMap(item);
            if (cookie == null) {
                continue;
            }
            String name = asString(cookie.get("name"));
            String value = asString(cookie.get("value"));
            if (name != null) {
                cookies.put(name, value == null ? "" : value);
            }
        }
        return cookies;
    }

    @Override
    public byte[] screenshot(boolean fullPage) {
        String captured;
        if (fullPage) {
            Map<String, Object> metrics = connection.call("Page.getLayoutMetrics", Map.of(), sessionId, commandTimeout);
            Map<String, Object> css = asMap(metrics.get("cssContentSize"));
            int width = (int) Math.ceil(asDouble(css == null ? null : css.get("width"), 1280));
            int height = (int) Math.ceil(asDouble(css == null ? null : css.get("height"), 900));
            Map<String, Object> override = new LinkedHashMap<>();
            override.put("width", Math.max(width, 1));
            override.put("height", Math.max(height, 1));
            override.put("deviceScaleFactor", 1);
            override.put("mobile", false);
            connection.call("Emulation.setDeviceMetricsOverride", override, sessionId, commandTimeout);
            try {
                captured = capturePng();
            } finally {
                connection.call("Emulation.clearDeviceMetricsOverride", Map.of(), sessionId, commandTimeout);
            }
        } else {
            captured = capturePng();
        }
        return captured == null ? new byte[0] : Base64.getDecoder().decode(captured);
    }

    /**
     * 截取当前视口
     *
     * @return Base64 编码的 PNG
     */
    private String capturePng() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("format", "png");
        params.put("captureBeyondViewport", true);
        Map<String, Object> result = connection.call("Page.captureScreenshot", params, sessionId, commandTimeout);
        Object data = result.get("data");
        return data instanceof String str ? str : null;
    }

    @Override
    public int fetchStream(String method, String url, String body, Map<String, String> headers,
                           long timeoutMs, BrowserPage.FetchSink consumer) {
        synchronized (streamLock) {
            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            sink = queue::offer;
            try {
                evaluate(streamScript(method, url, body, headers), false);
                int status = -1;
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (true) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        throw new IllegalStateException("等待浏览器内请求响应超时（" + timeoutMs + "ms）");
                    }
                    String item;
                    try {
                        item = queue.poll(Math.min(remain, 1000L), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("等待浏览器内请求被中断", e);
                    }
                    if (item == null) {
                        continue;
                    }
                    Map<String, Object> message = Json.fromJson(item);
                    if (message == null) {
                        continue;
                    }
                    String kind = asString(message.get("k"));
                    if ("status".equals(kind)) {
                        Object value = message.get("v");
                        status = value instanceof Number number ? number.intValue() : -1;
                        if (consumer != null) {
                            consumer.onStatus(status);
                        }
                    } else if ("chunk".equals(kind)) {
                        String chunk = asString(message.get("v"));
                        if (consumer != null && chunk != null) {
                            consumer.onChunk(chunk);
                        }
                    } else if ("error".equals(kind)) {
                        throw new IllegalStateException("浏览器内请求失败: " + asString(message.get("v")));
                    } else if ("end".equals(kind)) {
                        return status;
                    }
                }
            } finally {
                sink = null;
            }
        }
    }

    /**
     * 构建页面内的流式请求脚本
     *
     * @param method  HTTP 方法
     * @param url     请求地址
     * @param body    请求体，可为空
     * @param headers 请求头
     * @return JavaScript 表达式
     */
    private String streamScript(String method, String url, String body, Map<String, String> headers) {
        String methodLiteral = Json.toJson(method == null ? "GET" : method);
        String urlLiteral = Json.toJson(url == null ? "" : url);
        String headersLiteral = Json.toJson(headers == null ? new LinkedHashMap<>() : headers);
        String bindingLiteral = Json.toJson(binding);
        StringBuilder script = new StringBuilder();
        script.append("(async () => {")
                .append("const send=(o)=>{try{window[").append(bindingLiteral)
                .append("](JSON.stringify(o));}catch(e){}};")
                .append("try{")
                .append("const res=await fetch(").append(urlLiteral)
                .append(",{method:").append(methodLiteral)
                .append(",credentials:'include',headers:").append(headersLiteral);
        if (body != null) {
            script.append(",body:").append(Json.toJson(body));
        }
        script.append("});")
                .append("send({k:'status',v:res.status});")
                .append("if(!res.body){send({k:'chunk',v:await res.text()});send({k:'end'});return;}")
                .append("const reader=res.body.getReader();")
                .append("const decoder=new TextDecoder('utf-8');")
                .append("for(;;){const s=await reader.read();if(s.done)break;")
                .append("send({k:'chunk',v:decoder.decode(s.value,{stream:true})});}")
                .append("send({k:'end'});")
                .append("}catch(e){send({k:'error',v:String(e&&e.message?e.message:e)});}")
                .append("})()");
        return script.toString();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.call("Target.closeTarget", Map.of("targetId", targetId), null, Duration.ofSeconds(10));
        } catch (Exception e) {
            log.debug("[cdp] 关闭标签页失败: {}", e.getMessage());
        }
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
     * 安全转换 Map
     *
     * @param value 原始值
     * @return 字符串键 Map，类型不符返回 null
     */
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 安全转换数值
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 数值
     */
    private static double asDouble(Object value, double defaultValue) {
        return value instanceof Number number ? number.doubleValue() : defaultValue;
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