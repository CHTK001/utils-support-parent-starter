package com.chua.playwright.support.spi;

import java.util.List;
import java.util.Map;

/**
 * 浏览器自动化引擎 SPI。使用 playwright-java（{@code com.microsoft.playwright}）作为底层引擎。
 */
public interface Engine {

    long launch(boolean headless, String executablePath, List<String> args);

    long newContext(long browserHandle, Map<String, Object> options);

    long newPage(long targetHandle);

    ResponseData gotoPage(long pageHandle, String url, Map<String, Object> options);

    void click(long handle, String selector, Map<String, Object> options);

    void dblclick(long handle, String selector);

    void fill(long handle, String selector, String value);

    void type(long handle, String selector, String text);

    void press(long handle, String selector, String key);

    void check(long handle, String selector, boolean checked);

    void hover(long handle, String selector);

    String textContent(long handle, String selector);

    String innerText(long handle, String selector);

    String innerHTML(long handle, String selector);

    String getAttribute(long handle, String selector, String name);

    String inputValue(long handle, String selector);

    byte[] screenshot(long handle, Map<String, Object> options);

    Object evaluate(long handle, String expression, Object arg);

    long querySelector(long handle, String selector);

    List<Long> querySelectorAll(long handle, String selector);

    void waitForSelector(long handle, String selector, Long timeoutMs);

    void setViewportSize(long handle, int width, int height);

    String title(long handle);

    String url(long handle);

    void reload(long handle);

    ResponseData goBack(long handle);

    ResponseData goForward(long handle);

    List<String> selectOption(long handle, String selector, String[] values);

    void close(long handle);

    long newAPIRequest();

    ApiResponseData apiRequest(String action, String url, Object body);

    List<Object> batch(List<Map<String, Object>> commands, boolean stopOnError);

    String version();

    /**
     * 将当前页面渲染为 PDF。
     * 使用 Chrome DevTools Protocol 的 Page.printToPdf 接口。
     *
     * @param pageHandle 页面句柄
     * @param options    PDF 配置选项（margin, format, pageRanges 等），可为 null
     * @return PDF 文件的 base64 编码字符串
     */
    String printPageToPdf(long pageHandle, Map<String, Object> options);

    /**
     * 将指定的 HTML 内容渲染为 PNG 图片。
     * 通过 data: URI 加载 HTML 到页面，然后截图生成 PNG。
     *
     * @param pageHandle 页面句柄
     * @param html       HTML 内容字符串
     * @return PNG 图片的 base64 编码字符串
     */
    String convertHtmlToPng(long pageHandle, String html);

    class ResponseData {
        public final int status;
        public final String url;

        public ResponseData(int status, String url) {
            this.status = status;
            this.url = url;
        }
    }

    class ApiResponseData {
        public final int status;
        public final String body;

        public ApiResponseData(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
