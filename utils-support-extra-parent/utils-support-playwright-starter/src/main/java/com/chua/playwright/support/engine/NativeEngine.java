package com.chua.playwright.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import com.chua.playwright.support.PlaywrightException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 基于 Rust 无头浏览器动态库（headless_rust）的浏览器自动化引擎（Panama FFM）。
 *
 * <p>通过 {@code pw_call(request_json) -> response_json} 单一入口把 {@link Engine} 的
 * 全部动作转发到 Rust 侧的 chromiumoxide（真实 CDP 驱动 Chromium），与
 * {@link JavaEngine}（playwright-Java 1.63.0）保持语义一致：元素句柄优先于 selector、
 * 动作类操作等待可操作性、evaluate 与 Playwright utilityScript 语义对齐。</p>
 *
 * <p>信封协议：请求 {@code {"action":...,"handle":...,"params":{...}}}，
 * 响应 {@code {"ok":...}} 或 {@code {"error":...}}；错误统一转为
 * {@link PlaywrightException} 抛出。</p>
 *
 * <p>原生库不可用（抽取失败 / 符号缺失 / 平台不支持）时由 {@link #isAvailable()}
 * 报告，调用方应回退到 {@link JavaEngine}。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
@Spi("native")
public class NativeEngine implements Engine {

    /**
     * 原生库逻辑名（文件名 headless_rust / libheadless_rust）
     */
    private static final String LIBRARY_NAME = "headless_rust";

    /**
     * 库生命周期作用域：进程级共享，进程内不卸载
     */
    private static final Arena LIB_ARENA = Arena.global();

    /**
     * 原生链接器
     */
    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * 加载锁
     */
    private static final Object LOCK = new Object();

    /**
     * JSON 映射器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 响应字符串读取窗口上限（全页截图等大 JSON 的 base64 可能达数百 MB）
     */
    private static final long MAX_RESPONSE = 512L * 1024 * 1024;

    /**
     * 调度入口（pw_call）的句柄
     */
    private static volatile MethodHandle pwCall;

    /**
     * 释放响应字符串（pw_free_string）的句柄
     */
    private static volatile MethodHandle pwFreeString;

    /**
     * 取库版本（pw_version）的句柄
     */
    private static volatile MethodHandle pwVersion;

    /**
     * 是否已加载
     */
    private static volatile boolean loaded = false;

    /**
     * 加载失败原因；加载成功时为 null
     */
    private static volatile Throwable loadError;

    /**
     * 最近一次 {@link #newAPIRequest()} 的句柄。
     * Engine.apiRequest 不带句柄参数，与 JavaEngine 一致按"当前请求上下文"处理。
     */
    private volatile long apiRequestHandle = -1;

    // ==================== 加载与可用性 ====================

    /**
     * 加载原生库并绑定符号。线程安全，重复调用无副作用。
     */
    public static void loadLibrary() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            try {
                Path targetDir = NativeUtils.tempRoot().resolve("headless");
                NativeLoader.of("headless-rust")
                        .toTarget(targetDir)
                        .glob("*" + LIBRARY_NAME + "*")
                        .extractOnly(true)
                        .load();
                Path libPath = findExtractedLibrary(targetDir);
                SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, LIB_ARENA);
                pwCall = downcall(lookup, "pw_call",
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                pwFreeString = downcall(lookup, "pw_free_string",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                pwVersion = downcall(lookup, "pw_version",
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                loaded = true;
                loadError = null;
                log.info("[headless] 原生库加载成功（FFM），版本 {}", nativeVersion());
            } catch (Throwable e) {
                loadError = e;
                loaded = false;
                log.warn("[headless] 原生库不可用，将由调用方回退 JavaEngine: {}", e.getMessage());
            }
        }
    }

    /**
     * 在抽取目录内定位动态库。
     *
     * @param targetDir 抽取目录
     * @return 动态库路径
     */
    private static Path findExtractedLibrary(Path targetDir) {
        try (Stream<Path> entries = Files.list(targetDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains(LIBRARY_NAME))
                    .filter(p -> !p.getFileName().toString().endsWith(".a"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "抽取目录内没有 " + LIBRARY_NAME + " 动态库：" + targetDir));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取抽取目录失败：" + targetDir, e);
        }
    }

    /**
     * 绑定一个导出符号。
     *
     * @param lookup     符号查找器
     * @param symbol     符号名
     * @param descriptor 函数签名
     * @return 下调方法句柄
     */
    private static MethodHandle downcall(SymbolLookup lookup, String symbol,
                                          FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(
                lookup.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError(symbol + " not found")),
                descriptor);
    }

    /**
     * 查询原生库是否可用（加载成功且版本可读）。
     *
     * @return 可用返回 true
     */
    public boolean isAvailable() {
        try {
            loadLibrary();
            return loaded && !nativeVersion().isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 获取原生库加载失败原因。
     *
     * @return 失败异常；加载成功或未尝试时返回 null
     */
    public static Throwable getLoadError() {
        return loadError;
    }

    /**
     * 读取原生库版本串（pw_version，静态数据无需释放）。
     *
     * @return 版本串；未加载或读取失败时为空串
     */
    private static String nativeVersion() {
        if (!loaded) {
            return "";
        }
        try {
            MemorySegment ptr = (MemorySegment) pwVersion.invokeExact();
            if (ptr == null || ptr.equals(MemorySegment.NULL)) {
                return "";
            }
            return ptr.reinterpret(256).getString(0, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    // ==================== 信封调用 ====================

    /**
     * 执行一个动作并返回响应 {@code ok} 节点。
     *
     * @param action 动作名
     * @param handle 对象句柄（无句柄动作传 0）
     * @param params 参数对象，可为 空
     * @return ok 节点
     */
    private JsonNode call(String action, long handle, Object params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", action);
        request.put("handle", handle);
        request.put("params", params == null ? Map.of() : params);
        String requestJson;
        try {
            requestJson = MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new PlaywrightException("请求序列化失败: " + action, e);
        }
        String responseJson = callRaw(requestJson);
        JsonNode root;
        try {
            root = MAPPER.readTree(responseJson);
        } catch (JsonProcessingException e) {
            throw new PlaywrightException("原生响应非 JSON: " + responseJson);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new PlaywrightException(error.asText());
        }
        JsonNode ok = root.get("ok");
        if (ok == null) {
            throw new PlaywrightException("原生响应缺少 ok 字段: " + responseJson);
        }
        return ok;
    }

    /**
     * 调用 pw_call 并读取响应字符串（响应字符串必须 pw_free_string 释放）。
     *
     * @param requestJson 请求 JSON
     * @return 响应 JSON
     */
    private static String callRaw(String requestJson) {
        requireLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment request = arena.allocateFrom(requestJson, StandardCharsets.UTF_8);
            MemorySegment ptr = (MemorySegment) pwCall.invokeExact(request);
            if (ptr == null || ptr.equals(MemorySegment.NULL)) {
                throw new PlaywrightException("pw_call 返回 null");
            }
            try {
                return ptr.reinterpret(MAX_RESPONSE).getString(0, StandardCharsets.UTF_8);
            } finally {
                pwFreeString.invokeExact(ptr);
            }
        } catch (PlaywrightException e) {
            throw e;
        } catch (Throwable e) {
            throw new PlaywrightException("原生调用失败", e);
        }
    }

    /**
     * 校验已加载。
     */
    private static void requireLoaded() {
        if (!loaded) {
            throw new PlaywrightException("headless_rust 原生库未加载："
                    + (loadError == null ? "未尝试加载" : loadError.getMessage()));
        }
    }

    // ==================== 响应解析辅助 ====================

    /**
     * 取 ok 节点中的长整型句柄字段。
     *
     * @param ok    ok 节点
     * @param field 字段名
     * @return 句柄值
     */
    private static long longValue(JsonNode ok, String field) {
        JsonNode node = ok.get(field);
        if (node == null || !node.isNumber()) {
            throw new PlaywrightException("原生响应缺少 " + field + " 字段: " + ok);
        }
        return node.asLong();
    }

    /**
     * 取 ok 节点中的字符串字段（null / 缺失 → null）。
     *
     * @param ok    ok 节点
     * @param field 字段名
     * @return 字段值
     */
    private static String stringValue(JsonNode ok, String field) {
        JsonNode node = ok.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 解析导航响应信封：{@code null} 信封 → null（无网络响应），否则组装 ResponseData。
     *
     * @param ok ok 节点
     * @return 响应数据；无响应时返回 null
     */
    private static ResponseData toResponseData(JsonNode ok) {
        if (ok == null || ok.isNull()) {
            return null;
        }
        JsonNode status = ok.get("status");
        if (status == null || !status.isNumber()) {
            return null;
        }
        return new ResponseData(status.asInt(), stringValue(ok, "url"));
    }

    /**
     * 把 evaluate 的 JSON 值转成 Java 对象（Map / List / 标量 / null）。
     *
     * @param node JSON 节点
     * @return Java 对象
     */
    private static Object toJava(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return MAPPER.convertValue(node, Object.class);
    }

    /**
     * 组装元素动作参数（selector 在前，options 不覆盖协议键）。
     *
     * @param selector 元素选择器，可为 空（元素句柄时）
     * @param options  额外选项，可为 空
     * @return 参数对象
     */
    private static Map<String, Object> elemParams(String selector, Map<String, Object> options) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (options != null) {
            p.putAll(options);
        }
        p.put("selector", selector);
        return p;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动 Chromium 浏览器。
     *
     * @param headless 是否无头运行
     * @param executablePath 浏览器可执行文件路径，可为 空（使用系统默认查找）
     * @param args 浏览器启动参数，可为 空
     * @return 浏览器句柄
     */
    @Override
    public long launch(boolean headless, String executablePath, List<String> args) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("headless", headless);
        if (executablePath != null && !executablePath.isBlank()) {
            p.put("executablePath", executablePath);
        }
        if (args != null) {
            p.put("args", args);
        }
        return longValue(call("launch", 0, p), "handle");
    }

    /**
     * 创建浏览器上下文。
     *
     * @param browserHandle 浏览器句柄
     * @param options 上下文选项（原生侧暂不生效，与 JavaEngine 忽略一致）
     * @return 浏览器上下文句柄
     */
    @Override
    public long newContext(long browserHandle, Map<String, Object> options) {
        return longValue(call("newContext", browserHandle, options), "handle");
    }

    /**
     * 创建页面（浏览器或浏览器上下文句柄）。
     *
     * @param targetHandle 浏览器或浏览器上下文句柄
     * @return 页面句柄
     */
    @Override
    public long newPage(long targetHandle) {
        return longValue(call("newPage", targetHandle, null), "handle");
    }

    /**
     * 关闭对象（浏览器 / 页面 / 上下文 / 元素 / API 请求上下文）。句柄不存在时静默成功。
     *
     * @param handle 对象句柄
     */
    @Override
    public void close(long handle) {
        call("close", handle, null);
    }

    // ==================== 导航 ====================

    /**
     * 导航到指定 URL。
     *
     * @param pageHandle 页面句柄
     * @param url 目标 URL
     * @param options 导航选项（timeout 毫秒）
     * @return 响应数据；无网络响应时返回 null
     */
    @Override
    public ResponseData gotoPage(long pageHandle, String url, Map<String, Object> options) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (options != null) {
            p.putAll(options);
        }
        p.put("url", url);
        return toResponseData(call("gotoPage", pageHandle, p));
    }

    /**
     * 重新加载页面。
     *
     * @param pageHandle 页面句柄
     */
    @Override
    public void reload(long pageHandle) {
        call("reload", pageHandle, null);
    }

    /**
     * 导航到上一页。
     *
     * @param pageHandle 页面句柄
     * @return 响应数据；越界或失败返回 null
     */
    @Override
    public ResponseData goBack(long pageHandle) {
        return toResponseData(call("goBack", pageHandle, null));
    }

    /**
     * 导航到下一页。
     *
     * @param pageHandle 页面句柄
     * @return 响应数据；越界或失败返回 null
     */
    @Override
    public ResponseData goForward(long pageHandle) {
        return toResponseData(call("goForward", pageHandle, null));
    }

    // ==================== 交互 ====================

    /**
     * 点击页面元素或元素句柄。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param options 点击选项（原生侧暂不生效）
     */
    @Override
    public void click(long handle, String selector, Map<String, Object> options) {
        call("click", handle, elemParams(selector, options));
    }

    /**
     * 双击页面元素或元素句柄。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     */
    @Override
    public void dblclick(long handle, String selector) {
        call("dblclick", handle, elemParams(selector, null));
    }

    /**
     * 填充页面输入框或元素句柄。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param value 填充值
     */
    @Override
    public void fill(long handle, String selector, String value) {
        Map<String, Object> p = elemParams(selector, null);
        p.put("value", value);
        call("fill", handle, p);
    }

    /**
     * 输入文本（逐字符按键）。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param text 输入文本
     */
    @Override
    public void type(long handle, String selector, String text) {
        Map<String, Object> p = elemParams(selector, null);
        p.put("text", text);
        call("type", handle, p);
    }

    /**
     * 按下键盘按键（支持 Control+A 等组合键）。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param key 按键
     */
    @Override
    public void press(long handle, String selector, String key) {
        Map<String, Object> p = elemParams(selector, null);
        p.put("key", key);
        call("press", handle, p);
    }

    /**
     * 勾选或取消勾选元素。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param checked 是否勾选
     */
    @Override
    public void check(long handle, String selector, boolean checked) {
        Map<String, Object> p = elemParams(selector, null);
        p.put("checked", checked);
        call("check", handle, p);
    }

    /**
     * 悬停到元素。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     */
    @Override
    public void hover(long handle, String selector) {
        call("hover", handle, elemParams(selector, null));
    }

    // ==================== 读取 ====================

    /**
     * 获取元素文本内容。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @return 文本内容
     */
    @Override
    public String textContent(long handle, String selector) {
        return stringValue(call("textContent", handle, elemParams(selector, null)), "value");
    }

    /**
     * 获取元素内部文本。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @return 内部文本
     */
    @Override
    public String innerText(long handle, String selector) {
        return stringValue(call("innerText", handle, elemParams(selector, null)), "value");
    }

    /**
     * 获取元素 HTML。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @return HTML 内容
     */
    @Override
    public String innerHTML(long handle, String selector) {
        return stringValue(call("innerHTML", handle, elemParams(selector, null)), "value");
    }

    /**
     * 获取元素属性。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param name 属性名
     * @return 属性值；不存在时返回 null
     */
    @Override
    public String getAttribute(long handle, String selector, String name) {
        Map<String, Object> p = elemParams(selector, null);
        p.put("name", name);
        return stringValue(call("getAttribute", handle, p), "value");
    }

    /**
     * 获取输入框值。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @return 输入值
     */
    @Override
    public String inputValue(long handle, String selector) {
        return stringValue(call("inputValue", handle, elemParams(selector, null)), "value");
    }

    /**
     * 获取页面截图（仅页面句柄；只支持 fullPage 选项，输出 PNG）。
     *
     * @param handle 页面句柄
     * @param options 截图选项
     * @return PNG 字节
     */
    @Override
    public byte[] screenshot(long handle, Map<String, Object> options) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (options != null) {
            p.putAll(options);
        }
        String base64 = stringValue(call("screenshot", handle, p), "pngBase64");
        if (base64 == null) {
            throw new PlaywrightException("原生截图响应缺少 pngBase64 字段");
        }
        return Base64.getDecoder().decode(base64);
    }

    /**
     * 在页面或元素中执行 JavaScript。
     *
     * @param handle 页面或元素句柄
     * @param expression JavaScript 表达式（函数时以 (arg) / (element, arg) 调用）
     * @param arg 可选参数
     * @return 执行结果
     */
    @Override
    public Object evaluate(long handle, String expression, Object arg) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("expression", expression);
        p.put("arg", arg);
        return toJava(call("evaluate", handle, p).get("value"));
    }

    // ==================== 查询与等待 ====================

    /**
     * 查询单个元素（立即执行，不等待）。
     *
     * @param handle 页面句柄
     * @param selector 元素选择器
     * @return 元素句柄；未找到时返回 -1
     */
    @Override
    public long querySelector(long handle, String selector) {
        return longValue(call("querySelector", handle, elemParams(selector, null)), "handle");
    }

    /**
     * 查询多个元素（立即执行，不等待）。
     *
     * @param handle 页面句柄
     * @param selector 元素选择器
     * @return 元素句柄列表
     */
    @Override
    public List<Long> querySelectorAll(long handle, String selector) {
        List<Long> result = Wraps.list();
        JsonNode handles = call("querySelectorAll", handle, elemParams(selector, null)).get("handles");
        if (handles != null && handles.isArray()) {
            for (JsonNode node : handles) {
                result.add(node.asLong());
            }
        }
        return result;
    }

    /**
     * 等待选择器可见（默认 30s，Playwright 默认 state=visible）。
     *
     * @param handle 页面句柄
     * @param selector 元素选择器
     * @param timeoutMs 超时时间；null 用默认 30000
     */
    @Override
    public void waitForSelector(long handle, String selector, Long timeoutMs) {
        Map<String, Object> p = elemParams(selector, null);
        p.put("timeout", timeoutMs);
        call("waitForSelector", handle, p);
    }

    // ==================== 页面信息与视口 ====================

    /**
     * 设置页面视口大小。
     *
     * @param handle 页面句柄
     * @param width 宽度
     * @param height 高度
     */
    @Override
    public void setViewportSize(long handle, int width, int height) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("width", width);
        p.put("height", height);
        call("setViewportSize", handle, p);
    }

    /**
     * 获取页面标题。
     *
     * @param handle 页面句柄
     * @return 页面标题
     */
    @Override
    public String title(long handle) {
        return stringValue(call("title", handle, null), "value");
    }

    /**
     * 获取页面 URL。
     *
     * @param handle 页面句柄
     * @return 页面 URL
     */
    @Override
    public String url(long handle) {
        return stringValue(call("url", handle, null), "value");
    }

    /**
     * 选择下拉选项（字符串按 value 或 label 匹配，与 Playwright valueOrLabel 一致）。
     *
     * @param handle 页面句柄
     * @param selector 元素选择器
     * @param values 选项值；空数组取消全部选择
     * @return 选中的值
     */
    @Override
    public List<String> selectOption(long handle, String selector, String[] values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        p.put("values", values == null ? List.of() : List.of(values));
        List<String> result = Wraps.list();
        JsonNode selected = call("selectOption", handle, p).get("values");
        if (selected != null && selected.isArray()) {
            for (JsonNode node : selected) {
                result.add(node.asText());
            }
        }
        return result;
    }

    // ==================== API 请求 ====================

    /**
     * 创建独立的 HTTP API 请求上下文。
     *
     * @return API 请求上下文句柄
     */
    @Override
    public long newAPIRequest() {
        apiRequestHandle = longValue(call("newAPIRequest", 0, null), "handle");
        return apiRequestHandle;
    }

    /**
     * 执行 API 请求（使用最近一次 {@link #newAPIRequest()} 的上下文）。
     *
     * @param action 请求动作（apiGet / apiPost / apiPut / apiDelete / …）
     * @param url 请求 URL
     * @param body 请求体；非字符串时序列化为 JSON
     * @return API 响应数据
     */
    @Override
    public ApiResponseData apiRequest(String action, String url, Object body) {
        if (apiRequestHandle < 0) {
            throw new PlaywrightException("尚未创建 API 请求上下文");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        p.put("url", url);
        if (body != null) {
            if (body instanceof String text) {
                p.put("body", text);
            } else {
                try {
                    p.put("body", MAPPER.writeValueAsString(body));
                } catch (JsonProcessingException e) {
                    throw new PlaywrightException("API 请求体序列化失败", e);
                }
            }
        }
        JsonNode ok = call("apiRequest", apiRequestHandle, p);
        return new ApiResponseData(ok.path("status").asInt(), stringValue(ok, "body"));
    }

    // ==================== 批量 ====================

    /**
     * 批量执行浏览器命令（白名单：launch / newPage / goto / click / fill / screenshot / evaluate / close）。
     *
     * @param commands 命令列表
     * @param stopOnError 是否遇到错误停止
     * @return 命令执行结果；screenshot 项解码为 byte[]（与 JavaEngine 一致）
     */
    @Override
    public List<Object> batch(List<Map<String, Object>> commands, boolean stopOnError) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("commands", commands);
        p.put("stopOnError", stopOnError);
        List<Object> results = Wraps.list();
        JsonNode items = call("batch", 0, p).get("results");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (item.isObject() && item.has("pngBase64") && item.size() == 1) {
                    results.add(Base64.getDecoder().decode(item.get("pngBase64").asText()));
                } else {
                    results.add(toJava(item));
                }
            }
        }
        return results;
    }

    // ==================== 版本与渲染 ====================

    /**
     * 获取引擎版本（协议版本，如 1.63.0-native）。
     *
     * @return 引擎版本
     */
    @Override
    public String version() {
        String version = stringValue(call("version", 0, null), "version");
        if (version == null) {
            throw new PlaywrightException("原生版本响应缺少 version 字段");
        }
        return version;
    }

    /**
     * 将当前页面渲染为 PDF（纯 base64 字符串）。
     *
     * @param pageHandle 页面句柄
     * @param options PDF 配置选项（format / landscape / printBackground / scale / pageRanges），可为 空
     * @return PDF 的 base64 编码
     */
    @Override
    public String printPageToPdf(long pageHandle, Map<String, Object> options) {
        JsonNode ok = call("printPageToPdf", pageHandle, options);
        if (!ok.isTextual()) {
            throw new PlaywrightException("原生 PDF 响应格式错误: " + ok);
        }
        return ok.asText();
    }

    /**
     * 将指定的 HTML 内容渲染为 PNG 图片（纯 base64 字符串）。
     *
     * @param pageHandle 页面句柄
     * @param html HTML 内容字符串
     * @return PNG 的 base64 编码
     */
    @Override
    public String convertHtmlToPng(long pageHandle, String html) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("html", html);
        JsonNode ok = call("convertHtmlToPng", pageHandle, p);
        if (!ok.isTextual()) {
            throw new PlaywrightException("原生 PNG 响应格式错误: " + ok);
        }
        return ok.asText();
    }
}
