package com.chua.common.support.browser;

import java.util.Map;

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
     * 关闭页面
     */
    @Override
    void close();
}
