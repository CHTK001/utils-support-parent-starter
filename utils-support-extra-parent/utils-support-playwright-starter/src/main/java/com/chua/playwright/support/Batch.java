package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

import java.util.*;

/**
 * 双模式批量命令构建器。一次 JNI 调用（native 模式）或一次循环（Java 模式）执行多条命令。
 * 方法返回int为命令索引，用于 {@link Result#handle(int)} 获取真实句柄。
 */
public class Batch {

    private final Engine engine;
    private final List<Map<String, Object>> commands = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private boolean stopOnError = true;

    public Batch() {
        this.engine = Playwright.getEngine();
    }

    public Batch stopOnError(boolean stop) { this.stopOnError = stop; return this; }
    public int size() { return commands.size(); }

    public int launch(boolean headless) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("headless", headless);
        return add("launch", null, p);
    }

    public int newPage(int targetHandle) { return add("newPage", targetHandle, null); }

    public void gotoPage(int pageHandle, String url) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("url", url);
        add("goto", pageHandle, p);
    }

    public void click(int handle, String selector) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        add("click", handle, p);
    }

    public void fill(int handle, String selector, String value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selector", selector);
        p.put("value", value);
        add("fill", handle, p);
    }

    public void screenshot(int handle) { add("screenshot", handle, null); }

    public void evaluate(int handle, String expression) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("expression", expression);
        add("evaluate", handle, p);
    }

    public void close(int handle) { add("close", handle, null); }

    public void raw(String action, Integer handle, Map<String, Object> params) {
        add(action, handle, params);
    }

    public Result execute() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("commands", commands);
        params.put("stopOnError", stopOnError);
        List<Object> rawData = engine.batch(commands, stopOnError);

        // Extract REAL handles returned from Rust and store them
        // Rust returns List of result maps, each may contain "handle" field
        if (rawData instanceof List) {
            List<?> results = (List<?>) rawData;
            for (int i = 0; i < results.size() && i < commands.size(); i++) {
                Object result = results.get(i);
                if (result instanceof Map) {
                    Map<?, ?> resultMap = (Map<?, ?>) result;
                    if (resultMap.containsKey("handle")) {
                        long handle = ((Number) resultMap.get("handle")).longValue();
                        // Store the REAL handle returned by Rust for this command index
                        commands.get(i).put("handle", handle);
                    }
                }
            }
        }

        return new Result(names, rawData);
    }

    private int add(String action, Integer handle, Map<String, Object> params) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action", action);
        if (handle != null) cmd.put("handle", handle);
        else cmd.put("handle", 0);  // placeholder - will be overwritten by execute()
        if (params != null) cmd.put("params", params);
        commands.add(cmd);
        names.add(action);
        return commands.size() - 1;
    }

    public static class Result {
        private final List<String> names;
        private final List<Object> data;

        Result(List<String> names, List<Object> data) {
            this.names = names;
            this.data = data;
        }

        @SuppressWarnings("unchecked")
        public long handle(int index) {
            Map<String, Object> d = (Map<String, Object>) data.get(index);
            return ((Number) d.get("handle")).longValue();
        }

        @SuppressWarnings("unchecked")
        public String string(int index) {
            Object o = data.get(index);
            return o == null ? null : String.valueOf(o);
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(int index) {
            return (Map<String, Object>) data.get(index);
        }

        public int size() { return data.size(); }
    }
}
