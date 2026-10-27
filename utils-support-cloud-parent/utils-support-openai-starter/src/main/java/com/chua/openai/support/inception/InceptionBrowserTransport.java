package com.chua.openai.support.inception;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 *   <li>在页面上下文里 {@code fetch('/api/chat')}，通过
 *       {@code Runtime.addBinding} 把响应流分片回传给 Java。</li>
 * </ol>
 *
 * <p>因为请求由浏览器自己发出，TLS 指纹、HTTP2 设置、Cookie、挑战令牌全部
 * 天然一致，不需要手工抓 Cookie。代价是每次调用要占用一个浏览器进程，
 * 因此通道内部复用同一个页面，并按客户端实例串行化请求。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class InceptionBrowserTransport implements InceptionTransport {

    /**
     * 页面与 Java 之间的桥接函数名
     */
    private static final String BRIDGE = "__inceptionChatBridge";

    /**
     * 默认等待站点就绪的时间（毫秒）
     */
    private static final long READY_TIMEOUT_MILLIS = 120_000L;

    /**
     * 启动浏览器后等待 DevTools 端口的时间（毫秒）
     */
    private static final long DEVTOOLS_TIMEOUT_MILLIS = 60_000L;

    /**
     * 单条 CDP 命令的等待时间
     */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 站点根地址
     */
    private final String baseUrl;

    /**
     * 单轮对话超时（毫秒）
     */
    private final long timeoutMillis;

    /**
     * 指定的浏览器可执行文件路径，可为空
     */
    private final String chromePath;

    /**
     * 是否使用无头模式
     */
    private final boolean headless;

    /**
     * 浏览器代理地址，可为空
     */
    private final String proxy;

    /**
     * 请求串行锁：同一个页面同一时刻只跑一轮对话
     */
    private final Object lock = new Object();

    /**
     * 浏览器进程
     */
    private Process process;

    /**
     * 临时用户目录
     */
    private Path profileDir;

    /**
     * CDP 客户端
     */
    private InceptionCdpClient cdp;

    /**
     * 页面会话 ID
     */
    private String sessionId;

    /**
     * 当前流事件的接收器
     */
    private volatile java.util.function.Consumer<String> sink;

    /**
     * 是否已注册 JVM 关闭钩子
     */
    private boolean shutdownHookRegistered;

    /**
     * 构造浏览器通道
     *
     * @param baseUrl       站点根地址
     * @param timeoutMillis 单轮对话超时（毫秒）
     * @param chromePath    浏览器路径，可为空（自动探测）
     * @param headless      是否无头模式
     * @param proxy         浏览器代理，可为空
     */
    public InceptionBrowserTransport(String baseUrl, long timeoutMillis, String chromePath,
                                     boolean headless, String proxy) {
        this.baseUrl = baseUrl;
        this.timeoutMillis = timeoutMillis;
        this.chromePath = chromePath;
        this.headless = headless;
        this.proxy = proxy;
    }

    @Override
    public void chat(String payloadJson, InceptionStreamListener listener) throws Exception {
        synchronized (lock) {
            ensurePage();
            String token = readSessionToken();

            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            sink = queue::offer;
            try {
                evaluate(streamScript(payloadJson, token), false, COMMAND_TIMEOUT);

                InceptionStreamParser parser = new InceptionStreamParser(listener);
                StringBuilder errorBody = new StringBuilder();
                int status = 0;
                long deadline = System.currentTimeMillis() + timeoutMillis;

                while (true) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        throw new IllegalStateException("等待 Inception 上游响应超时（" + timeoutMillis + "ms），"
                                + "可能是页面被风控拦截或浏览器已退出");
                    }
                    String item = queue.poll(Math.min(remain, 1000L), TimeUnit.MILLISECONDS);
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
                        status = value instanceof Number number ? number.intValue() : 0;
                        continue;
                    }
                    if ("chunk".equals(kind)) {
                        String chunk = asString(message.get("v"));
                        if (status == 200) {
                            parser.feed(chunk);
                        } else {
                            errorBody.append(chunk == null ? "" : chunk);
                        }
                        continue;
                    }
                    if ("error".equals(kind)) {
                        throw new IllegalStateException("浏览器内请求失败: " + asString(message.get("v")));
                    }
                    if ("end".equals(kind)) {
                        break;
                    }
                }

                parser.flush();
                if (status != 200) {
                    throw new IllegalStateException(describeFailure(status, errorBody.toString()));
                }
                if (parser.error() != null) {
                    throw new IllegalStateException("Inception 上游返回错误: " + parser.error());
                }
            } finally {
                sink = null;
            }
        }
    }

    /**
     * 确保页面已打开且检查点已通过
     */
    private void ensurePage() {
        if (cdp != null && isPageAlive()) {
            return;
        }
        closeBrowser();
        launchBrowser();
        openPage();
    }

    /**
     * 页面是否仍可执行脚本
     *
     * @return true 表示可复用
     */
    private boolean isPageAlive() {
        try {
            evaluate("1", true, Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.debug("[inception] 浏览器页面已失效，将重新启动: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 启动浏览器并连接 CDP
     */
    private void launchBrowser() {
        String executable = locateBrowser(chromePath);
        try {
            profileDir = Files.createTempDirectory("inception-chrome-");
        } catch (IOException e) {
            throw new IllegalStateException("创建浏览器临时目录失败: " + e.getMessage(), e);
        }

        List<String> command = new ArrayList<>();
        command.add(executable);
        if (headless) {
            command.add("--headless=new");
        }
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + profileDir.toAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-blink-features=AutomationControlled");
        command.add("--disable-dev-shm-usage");
        command.add("--disable-features=Translate,OptimizationHints");
        command.add("--window-size=1280,900");
        command.add("--lang=zh-CN");
        if (StringUtils.isNotBlank(proxy)) {
            command.add("--proxy-server=" + proxy.trim());
        }
        command.add("about:blank");

        Path logFile = profileDir.resolve("chrome.log");
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("启动浏览器失败（" + executable + "）: " + e.getMessage(), e);
        }
        registerShutdownHook();

        Path activePortFile = profileDir.resolve("DevToolsActivePort");
        long deadline = System.currentTimeMillis() + DEVTOOLS_TIMEOUT_MILLIS;
        String port = null;
        String wsPath = null;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("浏览器进程已退出（exit=" + process.exitValue() + "），日志: "
                        + tail(logFile));
            }
            if (Files.exists(activePortFile)) {
                try {
                    List<String> lines = Files.readAllLines(activePortFile, StandardCharsets.UTF_8);
                    if (lines.size() >= 2) {
                        port = lines.get(0).trim();
                        wsPath = lines.get(1).trim();
                        break;
                    }
                } catch (IOException e) {
                    log.debug("[inception] 读取 DevToolsActivePort 失败: {}", e.getMessage());
                }
            }
            sleep(200L);
        }
        if (port == null || wsPath == null) {
            throw new IllegalStateException("等待浏览器 DevTools 端口超时，日志: " + tail(logFile));
        }

        URI endpoint = URI.create("ws://127.0.0.1:" + port + wsPath);
        cdp = InceptionCdpClient.connect(endpoint, Duration.ofSeconds(30));
        cdp.on("Runtime.bindingCalled", this::onBindingCalled);
        log.debug("[inception] 浏览器已启动: {} (headless={}, port={})", executable, headless, port);
    }

    /**
     * 处理页面桥接回调
     *
     * @param message CDP 事件
     */
    private void onBindingCalled(Map<String, Object> message) {
        Map<String, Object> params = asMap(message.get("params"));
        if (params == null || !BRIDGE.equals(params.get("name"))) {
            return;
        }
        Object payload = params.get("payload");
        java.util.function.Consumer<String> current = sink;
        if (current != null && payload != null) {
            current.accept(String.valueOf(payload));
        }
    }

    /**
     * 打开站点首页并等待检查点通过
     */
    private void openPage() {
        Map<String, Object> created = cdp.call("Target.createTarget",
                Map.of("url", "about:blank"), null, COMMAND_TIMEOUT);
        String targetId = asString(created.get("targetId"));
        if (StringUtils.isBlank(targetId)) {
            throw new IllegalStateException("创建浏览器标签页失败: " + Json.toJson(created));
        }
        Map<String, Object> attached = cdp.call("Target.attachToTarget",
                Map.of("targetId", targetId, "flatten", true), null, COMMAND_TIMEOUT);
        sessionId = asString(attached.get("sessionId"));
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalStateException("附加浏览器标签页失败: " + Json.toJson(attached));
        }
        cdp.call("Page.enable", Map.of(), sessionId, COMMAND_TIMEOUT);
        cdp.call("Runtime.enable", Map.of(), sessionId, COMMAND_TIMEOUT);
        // 绑定名对「当前及以后创建的上下文」都生效，必须在导航前注册
        cdp.call("Runtime.addBinding", Map.of("name", BRIDGE), sessionId, COMMAND_TIMEOUT);
        cdp.call("Page.navigate", Map.of("url", baseUrl + "/"), sessionId, COMMAND_TIMEOUT);

        boolean hosted = baseUrl.contains("inceptionlabs.ai");
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
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
            sleep(500L);
        }
        throw new IllegalStateException("等待 Inception 站点就绪超时，最后一次页面状态: "
                + (lastState.length() > 300 ? lastState.substring(0, 300) + "..." : lastState));
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
        String value = evaluate(expression, true, Duration.ofSeconds(15));
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
        String value = evaluate(script, true, COMMAND_TIMEOUT);
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
     * 构建页面内的流式请求脚本
     *
     * @param payloadJson 请求体
     * @param token       会话 token
     * @return JavaScript 表达式
     */
    private String streamScript(String payloadJson, String token) {
        String tokenLiteral = Json.toJson(token == null ? "" : token);
        String bodyLiteral = Json.toJson(payloadJson);
        return "(async () => {"
                + "const send = (o) => { try { window." + BRIDGE + "(JSON.stringify(o)); } catch (e) {} };"
                + "try {"
                + "const res = await fetch('/api/chat', {"
                + "method:'POST', credentials:'include',"
                + "headers:{'content-type':'application/json','accept':'text/event-stream',"
                + "'x-session-token':" + tokenLiteral + "},"
                + "body:" + bodyLiteral
                + "});"
                + "send({k:'status', v:res.status});"
                + "if (!res.body) { send({k:'chunk', v: await res.text()}); send({k:'end'}); return; }"
                + "const reader = res.body.getReader();"
                + "const decoder = new TextDecoder('utf-8');"
                + "for (;;) {"
                + "const step = await reader.read();"
                + "if (step.done) break;"
                + "send({k:'chunk', v: decoder.decode(step.value, {stream:true})});"
                + "}"
                + "send({k:'end'});"
                + "} catch (e) { send({k:'error', v: String(e && e.message ? e.message : e)}); }"
                + "})()";
    }

    /**
     * 在页面内求值
     *
     * @param expression 表达式
     * @param awaitPromise 是否等待 Promise
     * @param timeout    超时
     * @return 求值结果（字符串或 null）
     */
    private String evaluate(String expression, boolean awaitPromise, Duration timeout) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", awaitPromise);
        params.put("userGesture", false);
        Map<String, Object> result = cdp.call("Runtime.evaluate", params, sessionId, timeout);
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

    /**
     * 关闭浏览器并清理临时目录
     */
    private void closeBrowser() {
        if (cdp != null) {
            cdp.close();
            cdp = null;
        }
        sessionId = null;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
        if (profileDir != null) {
            deleteQuietly(profileDir);
            profileDir = null;
        }
    }

    /**
     * 注册 JVM 关闭钩子，避免残留浏览器进程
     */
    private void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeBrowser, "inception-browser-cleanup"));
        } catch (Exception e) {
            log.debug("[inception] 注册关闭钩子失败: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeBrowser();
        }
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
        String trimmed = text.trim();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400) + "...";
    }

    /**
     * 读取日志尾部
     *
     * @param logFile 日志文件
     * @return 末尾若干行
     */
    private static String tail(Path logFile) {
        try {
            if (!Files.exists(logFile)) {
                return "<无日志>";
            }
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 15);
            return String.join(" | ", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "<读取日志失败: " + e.getMessage() + ">";
        }
    }

    /**
     * 递归删除目录（尽力而为）
     *
     * @param dir 目录
     */
    private static void deleteQuietly(Path dir) {
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 浏览器可能仍占用部分文件，忽略即可
                }
            });
        } catch (IOException e) {
            log.debug("[inception] 清理临时目录失败: {}", e.getMessage());
        }
    }

    /**
     * 定位可用的 Chromium 内核浏览器
     *
     * <p>优先级：显式配置 → 系统属性 {@code inception.chrome} → 环境变量
     * {@code INCEPTION_CHROME} → 常见安装路径 → Playwright 缓存。</p>
     *
     * @param configured 显式配置的路径
     * @return 可执行文件路径
     */
    static String locateBrowser(String configured) {
        if (StringUtils.isNotBlank(configured) && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        String property = System.getProperty("inception.chrome");
        if (StringUtils.isNotBlank(property) && Files.isExecutable(Path.of(property))) {
            return property;
        }
        for (String env : new String[]{"INCEPTION_CHROME", "CHROME_PATH", "CHROME_BIN"}) {
            String value = System.getenv(env);
            if (StringUtils.isNotBlank(value) && Files.isExecutable(Path.of(value))) {
                return value;
            }
        }
        for (String candidate : candidates()) {
            if (StringUtils.isNotBlank(candidate) && Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        String playwright = locatePlaywrightChromium();
        if (playwright != null) {
            return playwright;
        }
        throw new IllegalStateException("未找到可用的 Chrome/Chromium 内核浏览器。"
                + "请设置系统属性 -Dinception.chrome=<chrome 可执行文件路径>，"
                + "或环境变量 INCEPTION_CHROME；也可以使用 -Dinception.transport=http 改走纯 HTTP 通道");
    }

    /**
     * 常见安装路径候选
     *
     * @return 候选路径
     */
    private static List<String> candidates() {
        List<String> list = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            addIfPresent(list, localAppData, "Google/Chrome/Application/chrome.exe");
            addIfPresent(list, programFiles, "Google/Chrome/Application/chrome.exe");
            addIfPresent(list, programFilesX86, "Google/Chrome/Application/chrome.exe");
            addIfPresent(list, localAppData, "Chromium/Application/chrome.exe");
            addIfPresent(list, localAppData, "Microsoft/Edge/Application/msedge.exe");
            addIfPresent(list, programFiles, "Microsoft/Edge/Application/msedge.exe");
            addIfPresent(list, programFilesX86, "Microsoft/Edge/Application/msedge.exe");
        } else if (os.contains("mac")) {
            list.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            list.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            list.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            list.add("/usr/bin/google-chrome");
            list.add("/usr/bin/google-chrome-stable");
            list.add("/usr/bin/chromium");
            list.add("/usr/bin/chromium-browser");
            list.add("/usr/bin/microsoft-edge");
        }
        return list;
    }

    /**
     * 追加候选路径
     *
     * @param list 候选列表
     * @param base 基础目录
     * @param rest 相对路径
     */
    private static void addIfPresent(List<String> list, String base, String rest) {
        if (StringUtils.isBlank(base)) {
            return;
        }
        list.add(Path.of(base, rest.split("/")).toString());
    }

    /**
     * 从 Playwright 缓存里找 Chromium（本机已下载过浏览器时最省事）
     *
     * @return 可执行文件路径，找不到返回 null
     */
    private static String locatePlaywrightChromium() {
        String localAppData = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        if (StringUtils.isNotBlank(localAppData)) {
            roots.add(Path.of(localAppData, "ms-playwright"));
        }
        if (StringUtils.isNotBlank(home)) {
            roots.add(Path.of(home, ".cache", "ms-playwright"));
            roots.add(Path.of(home, "Library", "Caches", "ms-playwright"));
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> dirs;
            try (java.util.stream.Stream<Path> stream = Files.list(root)) {
                dirs = stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("chromium-"))
                        .sorted(Comparator.comparingLong(InceptionBrowserTransport::revisionOf).reversed())
                        .toList();
            } catch (IOException e) {
                continue;
            }
            for (Path dir : dirs) {
                for (String relative : new String[]{"chrome-win64/chrome.exe", "chrome-win/chrome.exe",
                        "chrome-linux/chrome", "chrome-mac/Chromium.app/Contents/MacOS/Chromium"}) {
                    Path executable = dir.resolve(relative.replace('/', java.io.File.separatorChar));
                    if (Files.isExecutable(executable)) {
                        return executable.toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 解析 Playwright 目录名里的版本号
     *
     * @param dir 目录
     * @return 版本号，解析失败返回 0
     */
    private static long revisionOf(Path dir) {
        String name = dir.getFileName().toString();
        int index = name.lastIndexOf('-');
        if (index < 0) {
            return 0L;
        }
        try {
            return Long.parseLong(name.substring(index + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
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
            throw new IllegalStateException("等待被中断", e);
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
}
