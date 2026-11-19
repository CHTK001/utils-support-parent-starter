package com.chua.common.support.browser;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 浏览器页面
 *
 * <p>对 CDP 页面会话的最小抽象，覆盖爬虫渲染、截图与「页面内 fetch」三类需求：</p>
 * <ul>
 *   <li>导航、取标题、取渲染后的 HTML；</li>
 *   <li>执行 JavaScript 表达式；</li>
 *   <li>在页面上下文里发起请求，并把流式响应分片回传给 Java；</li>
 *   <li>设置 Cookie、请求头与截图。</li>
 * </ul>
 *
 * <p>请求由浏览器自己发出，TLS 指纹、HTTP2 设置、Cookie 与挑战令牌天然一致，
 * 适合被安全网关按指纹拦截的站点。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface BrowserPage extends AutoCloseable {

    /**
     * 当前页面地址
     *
     * @return 地址
     */
    String url();

    /**
     * 页面标题
     *
     * @return 标题
     */
    String title();

    /**
     * 渲染后的 HTML
     *
     * @return {@code document.documentElement.outerHTML}
     */
    String content();

    /**
     * 导航到目标地址，并等待文档加载完成
     *
     * @param url 地址
     */
    void navigate(String url);

    /**
     * 执行 JavaScript 表达式并等待 Promise
     *
     * @param expression 表达式
     * @return 求值结果，无返回值时返回 null
     */
    default String evaluate(String expression) {
        return evaluate(expression, true);
    }

    /**
     * 执行 JavaScript 表达式
     *
     * @param expression   表达式
     * @param awaitPromise 是否等待 Promise
     * @return 求值结果，无返回值时返回 null
     */
    String evaluate(String expression, boolean awaitPromise);

    /**
     * 设置 Cookie
     *
     * @param cookies 键值对
     * @param domain  域，如 {@code .example.com}
     */
    void setCookies(Map<String, String> cookies, String domain);

    /**
     * 设置 Cookie（按 URL 归属）
     *
     * @param cookies 键值对
     * @param url     归属地址
     */
    void setCookiesForUrl(Map<String, String> cookies, String url);

    /**
     * 设置额外请求头
     *
     * @param headers 请求头
     */
    void setExtraHeaders(Map<String, String> headers);

    /**
     * 读取当前页面的全部 Cookie（含 HttpOnly）
     *
     * @return Cookie 键值对
     */
    Map<String, String> cookies();

    /**
     * 注入在每次页面加载前执行的脚本（用于埋点、指纹修正等）
     *
     * @param script 脚本内容
     */
    default void addInitScript(String script) {
    }

    /**
     * 在指定坐标点击鼠标左键
     *
     * <p>坐标为页面视口内的 CSS 像素坐标（与截图尺寸同口径）。</p>
     *
     * @param x 横坐标
     * @param y 纵坐标
     */
    default void click(int x, int y) {
    }

    /**
     * 向当前焦点元素逐字输入文本
     *
     * <p>逐字符派发按键事件，能触发 React / Vue 等框架的受控输入；
     * 调用前通常先 {@link #click(int, int)} 聚焦目标输入框。</p>
     *
     * @param text 文本
     */
    default void type(String text) {
    }

    /**
     * 按下并释放一个功能键
     *
     * <p>支持 {@code enter / backspace / delete / tab / escape / space} 与方向键；
     * 未识别的键按普通文本输入处理。</p>
     *
     * @param key 键名，如 {@code enter}
     */
    default void pressKey(String key) {
    }

    /**
     * 向当前焦点元素整段提交文本
     *
     * <p>等价于 CDP {@code Input.insertText}：不逐键派发事件，直接把文本作为
     * 输入提交，对输入法、特殊字符最可靠。调用前通常先 {@link #click(int, int)}
     * 聚焦目标输入框。</p>
     *
     * @param text 文本，空文本忽略
     */
    default void insertText(String text) {
    }

    /**
     * 移动鼠标到指定坐标（不按键）
     *
     * @param x 横坐标
     * @param y 纵坐标
     */
    default void mouseMove(int x, int y) {
    }

    /**
     * 在指定坐标按下鼠标左键（配合 {@link #mouseUp(int, int)} 可实现拖拽）
     *
     * @param x 横坐标
     * @param y 纵坐标
     */
    default void mouseDown(int x, int y) {
    }

    /**
     * 在指定坐标释放鼠标左键
     *
     * @param x 横坐标
     * @param y 纵坐标
     */
    default void mouseUp(int x, int y) {
    }

    /**
     * 在指定坐标滚动滚轮
     *
     * @param x       横坐标
     * @param y       纵坐标
     * @param deltaX  横向滚动量
     * @param deltaY  纵向滚动量
     */
    default void mouseWheel(int x, int y, double deltaX, double deltaY) {
    }

    /**
     * 把页面带到前台（激活标签页）
     *
     * <p>无头浏览器同时打开多个标签页时，只有前台标签页能稳定接收输入事件：
     * 后台页面的鼠标事件会被限流，滚轮事件甚至可能得不到响应。对后台页面做
     * 点击 / 输入 / 滚动前，先调用本方法把它激活。</p>
     */
    default void bringToFront() {
    }

    /**
     * 截图
     *
     * @param fullPage 是否整页
     * @return PNG 字节
     */
    byte[] screenshot(boolean fullPage);

    /**
     * 在页面内发起请求并把响应分片回传
     *
     * <p>方法会阻塞到流结束或超时。状态码先于响应体回调，响应体（含错误体）
     * 逐片交给 {@code sink}，由调用方决定如何解析。</p>
     *
     * @param method    HTTP 方法
     * @param url       请求地址，可为相对路径（相对当前页面）
     * @param body      请求体，可为空
     * @param headers   请求头
     * @param timeoutMs 超时（毫秒）
     * @param sink      回调，可为空
     * @return HTTP 状态码；请求未拿到响应时抛出异常
     */
    int fetchStream(String method, String url, String body, Map<String, String> headers,
                    long timeoutMs, FetchSink sink);

    /**
     * 在页面内发起请求并把响应体聚合成完整文本
     *
     * <p>{@link #fetchStream(String, String, String, Map, long, FetchSink)} 的便捷封装，
     * 适合非流式的小响应；大响应或 SSE 仍建议直接用 {@code fetchStream}。</p>
     *
     * @param method    HTTP 方法
     * @param url       请求地址，可为相对路径
     * @param body      请求体，可为空
     * @param headers   请求头
     * @param timeoutMs 超时（毫秒）
     * @return 响应体文本
     */
    default String fetchText(String method, String url, String body, Map<String, String> headers,
                             long timeoutMs) {
        StringBuilder buffer = new StringBuilder();
        fetchStream(method, url, body, headers, timeoutMs, new FetchSink() {
            @Override
            public void onStatus(int status) {
            }

            @Override
            public void onChunk(String chunk) {
                buffer.append(chunk);
            }
        });
        return buffer.toString();
    }

    /**
     * 执行 JavaScript 表达式并返回原始 JSON 值
     *
     * <p>与 {@link #evaluate(String, boolean)} 不同，本方法保留值的结构：
     * 对象返回 {@code Map}、数组返回 {@code List}、数值返回 {@code Number}。
     * 仅部分实现支持，不支持时抛出 {@link UnsupportedOperationException}。</p>
     *
     * @param expression   表达式
     * @param awaitPromise 是否等待 Promise
     * @return 求值结果；表达式求值为 {@code undefined} 时返回 null
     */
    default Object evaluateJson(String expression, boolean awaitPromise) {
        throw new UnsupportedOperationException("当前浏览器页面实现不支持 evaluateJson");
    }

    /**
     * 判断选择器命中的元素是否存在
     *
     * @param selector CSS 选择器
     * @return true 表示存在；页面未就绪或执行失败返回 false
     */
    default boolean exists(String selector) {
        if (StringUtils.isBlank(selector)) {
            return false;
        }
        try {
            String value = evaluate("!!document.querySelector(" + Json.toJson(selector) + ")", false);
            return Boolean.parseBoolean(value);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等待选择器命中的元素出现
     *
     * @param selector      CSS 选择器
     * @param timeoutMillis 最长等待时间（毫秒）
     * @return true 表示等到元素；超时或被中断返回 false
     */
    default boolean waitForSelector(String selector, long timeoutMillis) {
        if (StringUtils.isBlank(selector)) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(timeoutMillis, 0L);
        while (true) {
            try {
                if (exists(selector)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 页面尚未就绪时忽略，继续等待
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * 读取选择器命中的第一个元素的文本
     *
     * @param selector CSS 选择器
     * @return {@code textContent}；元素不存在返回 null
     */
    default String text(String selector) {
        if (StringUtils.isBlank(selector)) {
            return null;
        }
        String expression = "(function () { var el = document.querySelector(" + Json.toJson(selector)
                + "); return el == null ? null : el.textContent; })()";
        return evaluate(expression, false);
    }

    /**
     * 读取选择器命中的全部元素的文本
     *
     * @param selector CSS 选择器
     * @return 文本列表；无命中元素时为空列表
     */
    default List<String> texts(String selector) {
        List<String> result = new ArrayList<>();
        if (StringUtils.isBlank(selector)) {
            return result;
        }
        String expression = "JSON.stringify(Array.from(document.querySelectorAll(" + Json.toJson(selector)
                + ")).map(function (item) { return item.textContent == null ? '' : item.textContent; }))";
        try {
            String json = evaluate(expression, false);
            if (StringUtils.isBlank(json)) {
                return result;
            }
            List<String> list = Json.fromJsonToList(json, String.class);
            if (list != null) {
                result.addAll(list);
            }
        } catch (Exception ignored) {
            // 查询失败按空结果处理
        }
        return result;
    }

    /**
     * 读取选择器命中的第一个元素的属性值
     *
     * @param selector      CSS 选择器
     * @param attributeName 属性名
     * @return 属性值；元素或属性不存在返回 null
     */
    default String attribute(String selector, String attributeName) {
        if (StringUtils.isBlank(selector) || StringUtils.isBlank(attributeName)) {
            return null;
        }
        String expression = "(function () { var el = document.querySelector(" + Json.toJson(selector)
                + "); return el == null ? null : el.getAttribute(" + Json.toJson(attributeName) + "); })()";
        return evaluate(expression, false);
    }

    /**
     * 填充表单控件的值并触发 input / change 事件
     *
     * <p>通过原生 value setter 写入，能被 React / Vue 等受控组件感知；
     * 适用于 {@code input / textarea / select}。</p>
     *
     * @param selector CSS 选择器
     * @param value    要填入的值
     * @return true 表示找到控件并写入；控件不存在返回 false
     */
    default boolean fill(String selector, String value) {
        if (StringUtils.isBlank(selector)) {
            return false;
        }
        String expression = "(function () {"
                + "var el = document.querySelector(" + Json.toJson(selector) + ");"
                + "if (!el) { return false; }"
                + "var value = " + Json.toJson(value == null ? "" : value) + ";"
                + "var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype"
                + " : (el instanceof HTMLInputElement ? HTMLInputElement.prototype : null);"
                + "if (proto) { var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;"
                + "setter.call(el, value); } else { el.value = value; }"
                + "el.dispatchEvent(new Event('input', { bubbles: true }));"
                + "el.dispatchEvent(new Event('change', { bubbles: true }));"
                + "return true; })()";
        try {
            return Boolean.parseBoolean(evaluate(expression, false));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 滚动到指定坐标
     *
     * @param x 横坐标
     * @param y 纵坐标
     */
    default void scrollTo(int x, int y) {
        evaluate("window.scrollTo(" + Math.max(x, 0) + ", " + Math.max(y, 0) + ")", false);
    }

    /**
     * 重新加载当前页面并等待加载完成
     */
    default void reload() {
        evaluate("location.reload()", false);
    }

    /**
     * 后退一页（等价于 {@code history.back()}）
     */
    default void goBack() {
        evaluate("history.back()", false);
    }

    /**
     * 前进一页（等价于 {@code history.forward()}）
     */
    default void goForward() {
        evaluate("history.forward()", false);
    }

    /**
     * 清空当前浏览器上下文的全部 Cookie
     *
     * <p>默认不支持，具体实现可覆盖。</p>
     */
    default void clearCookies() {
    }

    /**
     * 注册 JavaScript 对话框（alert / confirm / prompt）处理器
     *
     * <p>未注册处理器时，实现应自动关闭对话框，避免页面脚本被阻塞。
     * 只保留最近注册的一个处理器。</p>
     *
     * @param handler 处理器，传 null 恢复默认行为
     */
    default void onDialog(Consumer<Dialog> handler) {
    }

    /**
     * 开启实时画面推送（screencast）
     *
     * <p>浏览器每渲染一帧就把 JPEG 字节回调给 {@code frameListener}；
     * 页面静止时帧频率自然降低，适合长挂的嵌入场景。回调运行在浏览器
     * 事件线程上，监听器内部只做轻量处理（如入队），不要长时间阻塞。</p>
     *
     * <p>典型用法：配合 {@code CdpMjpegStream} 输出 {@code multipart/x-mixed-replace}，
     * 三方前端用 {@code <img>} 嵌入只读画面；需要<strong>可交互</strong>（点击、输入、滚动）
     * 时改用 {@code CdpLiveServer}，三方前端用 {@code <iframe>} 嵌入即可，仍无需任何 JS。</p>
     *
     * @param frameListener 每帧回调，入参为 JPEG 字节；不能为空
     * @return 关闭句柄，{@code close()} 停止推送（不关闭页面）
     * @throws UnsupportedOperationException 当前实现不支持实时画面
     */
    default AutoCloseable startScreencast(Consumer<byte[]> frameListener) {
        throw new UnsupportedOperationException("当前浏览器页面实现不支持实时画面（screencast）");
    }

    /**
     * 流式请求回调
     *
     * <p>状态码到达时回调一次 {@link #onStatus(int)}，随后每个响应分片回调
     * {@link #onChunk(String)}。</p>
     */
    interface FetchSink {

        /**
         * 收到响应状态码
         *
         * @param status HTTP 状态码
         */
        void onStatus(int status);

        /**
         * 收到响应分片
         *
         * @param chunk 文本分片
         */
        void onChunk(String chunk);
    }

    /**
     * JavaScript 对话框（alert / confirm / prompt）
     *
     * <p>通过 {@link #onDialog(Consumer)} 注册处理器；处理器必须在有限时间内
     * 调用 {@link #accept(String)} 或 {@link #dismiss()} 之一，否则页面脚本会一直阻塞。
     * 每个对话框只能被处理一次，重复处理会被忽略。</p>
     */
    interface Dialog {

        /**
         * 对话框类型：{@code alert / confirm / prompt}
         *
         * @return 类型名
         */
        String type();

        /**
         * 对话框文本
         *
         * @return 文本
         */
        String message();

        /**
         * prompt 对话框的默认输入值
         *
         * @return 默认值；非 prompt 对话框为 null
         */
        String defaultValue();

        /**
         * 接受对话框
         *
         * <p>confirm 等价于「确定」，prompt 等价于以 {@code promptText} 提交。</p>
         *
         * @param promptText prompt 的输入文本，可为空
         */
        void accept(String promptText);

        /**
         * 接受对话框（prompt 不填文本）
         */
        default void accept() {
            accept(null);
        }

        /**
         * 关闭对话框（confirm 等价于「取消」）
         */
        void dismiss();
    }

    /**
     * 关闭页面
     */
    @Override
    void close();
}
