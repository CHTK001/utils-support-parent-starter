package com.chua.playwright.support.spi;

import java.util.List;
import java.util.Map;

/**
 * 浏览器自动化引擎 SPI。双模式：
 * <ul>
 *   <li>{@link NativeEngine} — JNI + headless_chrome（默认，性能优先）</li>
 *   <li>{@link JavaEngine} — playwright-java 回退</li>
 * </ul>
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