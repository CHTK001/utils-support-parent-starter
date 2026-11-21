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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.chua.common.support.utils.StringUtils;

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
     * 功能键描述：键名小写 → { key, code, 虚拟键码, 产生文本 }
     */
    private static final Map<String, String[]> KEY_DESCRIPTORS = Map.ofEntries(
            Map.entry("enter", new String[]{"Enter", "Enter", "13", "\r"}),
            Map.entry("backspace", new String[]{"Backspace", "Backspace", "8", null}),
            Map.entry("delete", new String[]{"Delete", "Delete", "46", null}),
            Map.entry("tab", new String[]{"Tab", "Tab", "9", null}),
            Map.entry("escape", new String[]{"Escape", "Escape", "27", null}),
            Map.entry("space", new String[]{" ", "Space", "32", " "}),
            Map.entry("arrowup", new String[]{"ArrowUp", "ArrowUp", "38", null}),
            Map.entry("arrowdown", new String[]{"ArrowDown", "ArrowDown", "40", null}),
            Map.entry("arrowleft", new String[]{"ArrowLeft", "ArrowLeft", "37", null}),
            Map.entry("arrowright", new String[]{"ArrowRight", "ArrowRight", "39", null}),
            Map.entry("home", new String[]{"Home", "Home", "36", null}),
            Map.entry("end", new String[]{"End", "End", "35", null}),
            Map.entry("pageup", new String[]{"PageUp", "PageUp", "33", null}),
            Map.entry("pagedown", new String[]{"PageDown", "PageDown", "34", null}));

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
     * JavaScript 对话框处理器；为空时自动关闭对话框
     */
    private volatile Consumer<BrowserPage.Dialog> dialogHandler;

    /**
     * 页面关闭后的回调（引擎用它维护页面集合）
     */
    private volatile Runnable onCloseCallback;

    /**
     * 实时画面帧监听器；为空表示未开启 screencast
     */
    private volatile Consumer<byte[]> screencastListener;

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
        this.connection.on("Page.javascriptDialogOpening", this::onDialogOpening);
        this.connection.on("Page.screencastFrame", this::onScreencastFrame);
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

    /**
     * 处理 JavaScript 对话框事件
     *
     * <p>已注册处理器时交给处理器决定；未注册或处理器未处理时自动关闭对话框，
     * 避免页面脚本被 alert / confirm / prompt 永久阻塞。</p>
     *
     * @param message CDP 事件
     */
    private void onDialogOpening(Map<String, Object> message) {
        Map<String, Object> params = asMap(message.get("params"));
        if (params == null) {
            return;
        }
        CdpDialog dialog = new CdpDialog(
                asString(params.get("type")),
                asString(params.get("message")),
                asString(params.get("defaultPrompt")));
        Consumer<BrowserPage.Dialog> handler = dialogHandler;
        if (handler != null) {
            try {
                handler.accept(dialog);
            } catch (Exception e) {
                log.warn("[cdp] 对话框处理失败，自动关闭: {}", e.getMessage());
            }
        }
        if (!dialog.isAnswered()) {
            dialog.dismiss();
        }
    }

    @Override
    public void onDialog(Consumer<BrowserPage.Dialog> handler) {
        this.dialogHandler = handler;
    }

    /**
     * 处理实时画面帧事件
     *
     * <p>先应答帧会话（浏览器收到应答前不会继续推帧），再把 JPEG 字节交给
     * 监听器。本方法运行在 WebSocket 读线程上，监听器不允许长时间阻塞。</p>
     *
     * @param message CDP 事件
     */
    private void onScreencastFrame(Map<String, Object> message) {
        Map<String, Object> params = asMap(message.get("params"));
        if (params == null) {
            return;
        }
        if (params.get("sessionId") instanceof Number frameSession) {
            connection.post("Page.screencastFrameAck",
                    Map.of("sessionId", frameSession.intValue()), sessionId);
        }
        Consumer<byte[]> listener = screencastListener;
        if (listener == null) {
            return;
        }
        Object data = params.get("data");
        if (!(data instanceof String base64) || base64.isEmpty()) {
            return;
        }
        try {
            listener.accept(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.debug("[cdp] 处理实时画面帧失败: {}", e.getMessage());
        }
    }

    @Override
    public AutoCloseable startScreencast(Consumer<byte[]> frameListener) {
        if (frameListener == null) {
            throw new IllegalArgumentException("frameListener 不能为空");
        }
        this.screencastListener = frameListener;
        var params = new LinkedHashMap<String, Object>();
        params.put("format", "jpeg");
        params.put("quality", 70);
        connection.call("Page.startScreencast", params, sessionId, commandTimeout);
        return this::stopScreencast;
    }

    /**
     * 停止实时画面推送
     */
    public void stopScreencast() {
        this.screencastListener = null;
        try {
            connection.call("Page.stopScreencast", Map.of(), sessionId, commandTimeout);
        } catch (Exception e) {
            log.debug("[cdp] 停止实时画面失败: {}", e.getMessage());
        }
    }

    /**
     * 基于 {@code Page.handleJavaScriptDialog} 的对话框实现
     */
    private final class CdpDialog implements BrowserPage.Dialog {

        /**
         * 对话框类型
         */
        private final String type;

        /**
         * 对话框文本
         */
        private final String message;

        /**
         * prompt 默认值
         */
        private final String defaultValue;

        /**
         * 是否已处理
         */
        private final AtomicBoolean answered = new AtomicBoolean();

        /**
         * 构造对话框
         *
         * @param type         类型
         * @param message      文本
         * @param defaultValue prompt 默认值
         */
        private CdpDialog(String type, String message, String defaultValue) {
            this.type = type;
            this.message = message;
            this.defaultValue = defaultValue;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public void accept(String promptText) {
            respond(true, promptText);
        }

        @Override
        public void dismiss() {
            respond(false, null);
        }

        /**
         * 是否已被处理
         *
         * @return true 表示已 accept / dismiss
         */
        private boolean isAnswered() {
            return answered.get();
        }

        /**
         * 回应对话框（只生效一次）
         *
         * @param accept     true 表示接受
         * @param promptText prompt 输入文本
         */
        private void respond(boolean accept, String promptText) {
            if (!answered.compareAndSet(false, true)) {
                return;
            }
            var params = new LinkedHashMap<String, Object>();
            params.put("accept", accept);
            if (accept && promptText != null) {
                params.put("promptText", promptText);
            }
            // 事件回调运行在 WebSocket 读线程上，这里必须只发不等：
            // 同步等待会阻塞消息分发，造成对话框与触发它的命令互相死锁
            connection.post("Page.handleJavaScriptDialog", params, sessionId);
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
        if (StringUtils.isBlank(script)) {
            return;
        }
        connection.call("Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", script), sessionId, commandTimeout);
    }

    @Override
    public void click(int x, int y) {
        var move = new LinkedHashMap<String, Object>();
        move.put("type", "mouseMoved");
        move.put("x", x);
        move.put("y", y);
        connection.call("Input.dispatchMouseEvent", move, sessionId, commandTimeout);
        dispatchMouseButton("mousePressed", x, y);
        dispatchMouseButton("mouseReleased", x, y);
    }

    @Override
    public void type(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint == '\n' || codePoint == '\r') {
                // 换行必须派发 Enter 键，仅传 text 无法触发部分输入框的提交
                dispatchKey("keyDown", "Enter", "Enter", "\r", 13);
                dispatchKey("keyUp", "Enter", "Enter", null, 13);
            } else {
                String ch = new String(Character.toChars(codePoint));
                // 普通字符必须在 keyDown 上带 text，浏览器才会把它写进输入框
                dispatchKey("keyDown", ch, ch, ch, 0);
                dispatchKey("keyUp", ch, ch, null, 0);
            }
            i += Character.charCount(codePoint);
        }
    }

    @Override
    public void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        connection.call("Input.insertText", Map.of("text", text), sessionId, commandTimeout);
    }

    @Override
    public void pressKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String[] descriptor = KEY_DESCRIPTORS.get(key.trim().toLowerCase(Locale.ROOT));
        if (descriptor == null) {
            // 未登记的键按普通文本输入处理
            type(key.trim());
            return;
        }
        int virtualKey = Integer.parseInt(descriptor[2]);
        dispatchKey(descriptor[3] == null ? "rawKeyDown" : "keyDown",
                descriptor[0], descriptor[1], descriptor[3], virtualKey);
        dispatchKey("keyUp", descriptor[0], descriptor[1], null, virtualKey);
    }

    @Override
    public void mouseMove(int x, int y) {
        var params = new LinkedHashMap<String, Object>();
        params.put("type", "mouseMoved");
        params.put("x", x);
        params.put("y", y);
        connection.call("Input.dispatchMouseEvent", params, sessionId, commandTimeout);
    }

    @Override
    public void mouseDown(int x, int y) {
        dispatchMouseButton("mousePressed", x, y);
    }

    @Override
    public void mouseUp(int x, int y) {
        dispatchMouseButton("mouseReleased", x, y);
    }

    @Override
    public void mouseWheel(int x, int y, double deltaX, double deltaY) {
        var params = new LinkedHashMap<String, Object>();
        params.put("type", "mouseWheel");
        params.put("x", x);
        params.put("y", y);
        params.put("deltaX", deltaX);
        params.put("deltaY", deltaY);
        connection.call("Input.dispatchMouseEvent", params, sessionId, commandTimeout);
    }

    /**
     * 把页面带到前台
     *
     * <p>多标签页场景下，被观看 / 被操作的页面必须是前台标签页，否则滚轮等
     * 输入事件可能被浏览器静默丢弃。</p>
     */
    @Override
    public void bringToFront() {
        connection.call("Page.bringToFront", Map.of(), sessionId, commandTimeout);
    }

    /**
     * 派发一次鼠标按键事件
     *
     * @param type 事件类型（mousePressed / mouseReleased）
     * @param x    横坐标
     * @param y    纵坐标
     */
    private void dispatchMouseButton(String type, int x, int y) {
        var params = new LinkedHashMap<String, Object>();
        params.put("type", type);
        params.put("x", x);
        params.put("y", y);
        params.put("button", "left");
        params.put("clickCount", 1);
        connection.call("Input.dispatchMouseEvent", params, sessionId, commandTimeout);
    }

    /**
     * 派发一次键盘事件
     *
     * @param type       事件类型（keyDown / rawKeyDown / keyUp）
     * @param key        键名
     * @param code       键码名
     * @param text       产生的文本，无则为 null
     * @param virtualKey Windows 虚拟键码，普通字符传 0
     */
    private void dispatchKey(String type, String key, String code, String text, int virtualKey) {
        var params = new LinkedHashMap<String, Object>();
        params.put("type", type);
        params.put("key", key);
        params.put("code", code);
        if (virtualKey > 0) {
            params.put("windowsVirtualKeyCode", virtualKey);
            params.put("keyCode", virtualKey);
        }
        if (text != null) {
            params.put("text", text);
        }
        connection.call("Input.dispatchKeyEvent", params, sessionId, commandTimeout);
    }

    @Override
    public void navigate(String url) {
        Map<String, Object> result = connection.call("Page.navigate",
                Map.of("url", url), sessionId, commandTimeout);
        String errorText = asString(result.get("errorText"));
        if (StringUtils.isNotBlank(errorText)) {
            throw new IllegalStateException("页面导航失败: " + url + " (" + errorText + ")");
        }
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
        var params = new LinkedHashMap<String, Object>();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", awaitPromise);
        params.put("userGesture", false);
        Map<String, Object> result = connection.call("Runtime.evaluate", params, sessionId, commandTimeout);
        Map<String, Object> exception = asMap(result.get("exceptionDetails"));
        if (exception != null) {
            throw evaluateException(exception);
        }
        Map<String, Object> value = asMap(result.get("result"));
        if (value == null) {
            return null;
        }
        Object raw = value.get("value");
        return raw == null ? null : String.valueOf(raw);
    }

    @Override
    public Object evaluateJson(String expression, boolean awaitPromise) {
        var params = new LinkedHashMap<String, Object>();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", awaitPromise);
        params.put("userGesture", false);
        Map<String, Object> result = connection.call("Runtime.evaluate", params, sessionId, commandTimeout);
        Map<String, Object> exception = asMap(result.get("exceptionDetails"));
        if (exception != null) {
            throw evaluateException(exception);
        }
        Map<String, Object> value = asMap(result.get("result"));
        return value == null ? null : value.get("value");
    }

    /**
     * 把 CDP 的 exceptionDetails 转成 Java 异常
     *
     * @param exception exceptionDetails
     * @return 运行时异常
     */
    private static IllegalStateException evaluateException(Map<String, Object> exception) {
        Object detail = exception.get("exception");
        Object description = detail instanceof Map<?, ?> map ? map.get("description") : null;
        if (description == null) {
            description = exception.get("text");
        }
        return new IllegalStateException("页面脚本执行失败: "
                + (description == null ? Json.toJson(exception) : description));
    }

    @Override
    public void clearCookies() {
        connection.call("Network.clearBrowserCookies", Map.of(), sessionId, commandTimeout);
    }

    @Override
    public void reload() {
        connection.call("Page.reload", Map.of(), sessionId, commandTimeout);
        waitForDocument();
    }

    @Override
    public void goBack() {
        navigateHistory(-1);
    }

    @Override
    public void goForward() {
        navigateHistory(1);
    }

    /**
     * 沿浏览历史前进或后退
     *
     * @param delta 偏移量，负数后退、正数前进
     */
    private void navigateHistory(int delta) {
        Map<String, Object> history = connection.call("Page.getNavigationHistory",
                Map.of(), sessionId, commandTimeout);
        Number index = history.get("index") instanceof Number number ? number : null;
        Object rawEntries = history.get("entries");
        if (index == null || !(rawEntries instanceof List<?> entries)) {
            return;
        }
        int target = index.intValue() + delta;
        if (target < 0 || target >= entries.size()) {
            return;
        }
        Map<String, Object> entry = asMap(entries.get(target));
        Number entryId = entry == null ? null
                : (entry.get("id") instanceof Number number ? number : null);
        if (entryId == null) {
            return;
        }
        connection.call("Page.navigateToHistoryEntry",
                Map.of("entryId", entryId.intValue()), sessionId, commandTimeout);
        waitForDocument();
    }

    @Override
    public void setCookies(Map<String, String> cookies, String domain) {
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            var cookie = new LinkedHashMap<String, Object>();
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
            var cookie = new LinkedHashMap<String, Object>();
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
        var cookies = new LinkedHashMap<String, String>();
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
            var override = new LinkedHashMap<String, Object>();
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
        var params = new LinkedHashMap<String, Object>();
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
                .append("](JSON.stringify(o));}catch(e){/* 忽略：绑定回调未就绪或调用失败，事件仅尽力上报 */}};")
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
        Runnable callback = onCloseCallback;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                log.debug("[cdp] 页面关闭回调失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 注册页面关闭回调（引擎用它维护存活页面集合）
     *
     * @param callback 回调
     */
    void onClose(Runnable callback) {
        this.onCloseCallback = callback;
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
        var result = new LinkedHashMap<String, Object>();
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