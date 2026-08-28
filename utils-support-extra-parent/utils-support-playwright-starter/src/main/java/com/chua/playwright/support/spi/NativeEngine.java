package com.chua.playwright.support.spi;

import com.chua.playwright.support.bridge.PlaywrightNative;
import com.chua.playwright.support.PlaywrightException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * JNI + headless_chrome 实现。所有操作通过 {@link PlaywrightNative#execute(String)} 转发到 Rust 层。
 */
public class NativeEngine implements Engine {

    private static final ObjectMapper OM = new ObjectMapper();

    private static String call(String action, Long handle, Map<String, Object> params) {
        return command(OM, action, handle, params);
    }

    static String command(ObjectMapper mapper, String action, Long handle, Map<String, Object> params) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action", action);
        if (handle != null) cmd.put("handle", handle);
        if (params != null) cmd.put("params", params);
        try {
            return mapper.writeValueAsString(cmd);
        } catch (Exception e) {
            throw new PlaywrightException("命令序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String action, Long handle, Map<String, Object> params) {
        String json = call(action, handle, params);
        try {
            String resp = PlaywrightNative.execute(json);
            Map<String, Object> m = OM.readValue(resp, Map.class);
            if (!Boolean.TRUE.equals(m.get("ok"))) {
                throw new PlaywrightException(String.valueOf(m.get("error")));
            }
            Object data = m.get("data");
            if (data instanceof Map) return (Map<String, Object>) data;
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("value", data);
            return wrap;
        } catch (PlaywrightException e) {
            throw e;
        } catch (Exception e) {
            throw new PlaywrightException("命令执行失败: " + action, e);
        }
    }

    private Object requestData(String action, Long handle, Map<String, Object> params) {
        String json = call(action, handle, params);
        try {
            String resp = PlaywrightNative.execute(json);
            Map<String, Object> m = OM.readValue(resp, Map.class);
            if (!Boolean.TRUE.equals(m.get("ok"))) {
                throw new PlaywrightException(String.valueOf(m.get("error")));
            }
            return m.get("data");
        } catch (PlaywrightException e) {
            throw e;
        } catch (Exception e) {
            throw new PlaywrightException("命令执行失败: " + action, e);
        }
    }

    private long requestHandle(String action, Long handle, Map<String, Object> params) {
        Map<String, Object> d = request(action, handle, params);
        return ((Number) d.get("handle")).longValue();
    }

    // ===================== Engine 实现 =====================

    @Override
    public long launch(boolean headless, String executablePath, List<String> args) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("headless", headless);
        if (executablePath != null) p.put("executablePath", executablePath);
        if (args != null && !args.isEmpty()) p.put("args", args);
        return requestHandle("launch", null, p);
    }

    @Override
    public long newContext(long browserHandle, Map<String, Object> options) {
        return requestHandle("newContext", browserHandle, options);
    }

    @Override
    public long newPage(long targetHandle) {
        return requestHandle("newPage", targetHandle, null);
    }

    @Override
    public ResponseData gotoPage(long pageHandle, String url, Map<String, Object> options) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("url", url);
        if (options != null) p.putAll(options);
        Map<String, Object> d = request("goto", pageHandle, p);
        return new ResponseData(
            ((Number) d.get("status")).intValue(),
            (String) d.get("url")
        );
    }

    @Override
    public void click(long handle, String selector, Map<String, Object> options) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        if (options != null) p.putAll(options);
        request("click", handle, p);
    }

    @Override
    public void dblclick(long handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        request("dblclick", handle, p);
    }

    @Override
    public void fill(long handle, String selector, String value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        p.put("value", value);
        request("fill", handle, p);
    }

    @Override
    public void type(long handle, String selector, String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        p.put("value", text);
        request("type", handle, p);
    }

    @Override
    public void press(long handle, String selector, String key) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        p.put("key", key);
        request("press", handle, p);
    }

    @Override
    public void check(long handle, String selector, boolean checked) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        request(checked ? "check" : "uncheck", handle, p);
    }

    @Override
    public void hover(long handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        request("hover", handle, p);
    }

    @Override
    public String textContent(long handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (selector != null) p.put("selector", selector);
        Object d = requestData("textContent", handle, p);
        return d == null ? null : String.valueOf(d);
    }

    @Override
    public String innerText(long handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        Object d = requestData("innerText", handle, p);
        return d == null ? null : String.valueOf(d);
    }

    @Override
    public String innerHTML(long handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        Object d = requestData("innerHTML", handle, p);
        return d == null ? null : String.valueOf(d);
    }

    @Override
    public String getAttribute(long handle, String selector, String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        p.put("name", name);
        Object d = requestData("getAttribute", handle, p);
        return d == null ? null : String.valueOf(d);
    }

    @Override
    public String inputValue(long handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        Object d = requestData("inputValue", handle, p);
        return d == null ? null : String.valueOf(d);
    }

    @Override
    public byte[] screenshot(long handle, Map<String, Object> options) {
        Map<String, Object> d = request("screenshot", handle, options);
        String b64 = (String) d.get("base64");
        return Base64.getDecoder().decode(b64);
    }

    @Override
    public Object evaluate(long handle, String expression, Object arg) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("expression", expression);
        if (arg != null) p.put("arg", arg);
        return requestData("evaluate", handle, p);
    }

    @Override
    public long querySelector(long handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        return requestHandle("querySelector", handle, p);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> querySelectorAll(long handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        Map<String, Object> d = request("querySelectorAll", handle, p);
        return (List<Long>) d.get("handles");
    }

    @Override
    public void waitForSelector(long handle, String selector, Long timeoutMs) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        if (timeoutMs != null) p.put("timeout", timeoutMs);
        request("waitForSelector", handle, p);
    }

    @Override
    public void setViewportSize(long handle, int width, int height) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("width", width);
        p.put("height", height);
        request("setViewportSize", handle, p);
    }

    @Override
    public String title(long handle) {
        Object d = requestData("title", handle, null);
        return d == null ? null : String.valueOf(d);
    }

    @Override
    public String url(long handle) {
        Object d = requestData("url", handle, null);
        return d == null ? null : String.valueOf(d);
    }

    @Override
    public void reload(long handle) {
        request("reload", handle, null);
    }

    @Override
    public ResponseData goBack(long handle) {
        Object d = requestData("goBack", handle, null);
        if (d instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) d;
            return new ResponseData(
                m.containsKey("status") ? ((Number) m.get("status")).intValue() : 0,
                (String) m.get("url")
            );
        }
        return null;
    }

    @Override
    public ResponseData goForward(long handle) {
        Object d = requestData("goForward", handle, null);
        if (d instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) d;
            return new ResponseData(
                m.containsKey("status") ? ((Number) m.get("status")).intValue() : 0,
                (String) m.get("url")
            );
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> selectOption(long handle, String selector, String[] values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        p.put("values", values);
        Object d = requestData("selectOption", handle, p);
        if (d instanceof List) return (List<String>) d;
        return new ArrayList<>();
    }

    @Override
    public void close(long handle) {
        request("close", handle, null);
    }

    @Override
    public long newAPIRequest() {
        return requestHandle("newAPIRequest", null, null);
    }

    @Override
    public ApiResponseData apiRequest(String action, String url, Object body) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("url", url);
        if (body != null) p.put("body", body);
        Map<String, Object> d = request(action, null, p);
        return new ApiResponseData(
            ((Number) d.get("status")).intValue(),
            (String) d.get("body")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object> batch(List<Map<String, Object>> commands, boolean stopOnError) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("commands", commands);
        p.put("stopOnError", stopOnError);
        Object d = requestData("batch", null, p);
        if (d instanceof List) return (List<Object>) d;
        return new ArrayList<>();
    }

    @Override
    public String version() {
        return PlaywrightNative.getVersion();
    }
}