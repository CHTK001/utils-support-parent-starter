package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

import java.util.*;

/**
* 批量命令构建器。逐条命令通过 {@link Engine} 顺序执行，模拟一次批量调用的语义。
* @author CH
* @since 4.0.0
* @param handle 处理
* @param selector selector
* @param value 值
* @param pageHandle page处理
* @param url url
 */
public class Batch {

    /**
    * 批量。
     */
    private final Engine engine;
    private final List<Map<String, Object>> commands = new ArrayList<>(); // 命令
    private final List<String> names = new ArrayList<>(); // 名称
    private boolean stopOnError = true; // 停止on错误
/**
* 停止on错误。
* @param stop 停止
* @return 停止on错误的结果
 */

    /**
    * 大小。
    * @return 大小的结果
     */
    public Batch() {
        this.engine = Playwright.getEngine();
    /**
    * launch。
    * @param headless headless
    * @return launch的结果
    * @param stop 停止
     */
    }

    public Batch stopOnError(boolean stop) { this.stopOnError = stop; return this; }
    /**
    * 大小。
    * @return 大小的结果
     */
    public int size() { return commands.size(); }

    /**
    * launch。
    * @param headless headless
    * @return launch的结果
     */
    public int launch(boolean headless) {
        /**
        * 新page。
        * @param targetHandle Target处理
        * @return 新page的结果
         */
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("headless", headless);
        return add("launch", null, p);
    }

    public int newPage(int targetHandle) { return add("newPage", targetHandle, null); }

    /**
    * gotoPage。
    * @param pageHandle page处理
    * @param url url
     */
    public void gotoPage(int pageHandle, String url) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("url", url);
        /**
        * click。
        * @param handle 处理
        * @param selector selector
        * @param value 值
         */
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
/**
* 评估。
* @param handle 处理
* @param expression expression
 */

    public void evaluate(int handle, String expression) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("expression", expression);
        add("evaluate", handle, p);
    }

    public void close(int handle) { add("close", handle, null); }

    /**
    * raw。
    * @param action 动作
    * @param handle 处理
    * @param params 参数
     */
    public void raw(String action, Integer handle, Map<String, Object> params) {
        add(action, handle, params);
    }

    /**
    * 逐条执行所有命令。
    * 当 {@code stopOnError=true} 时，遇到异常立即停止并抛出。
    * @param action 动作
     /**
      * 执行。
      * @return 执行的结果
      */
     * @param handle 处理
     * @param params 参数
     * @return 添加的结果
      * @param action 动作
     /**
     * 执行。
     * @return 执行的结果
      */
     */
    public List<Object> execute() {
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            Map<String, Object> cmd = commands.get(i);
            String action = (String) cmd.get("action");
            Number handleNum = (Number) cmd.get("handle");
            long handle = handleNum != null ? handleNum.longValue() : 0;
            Map<String, Object> params = (Map<String, Object>) cmd.get("params");
            try {
                Object result = dispatch(action, handle, params);
                results.add(result);
            } catch (Exception e) {
                if (stopOnError) {
                    throw new RuntimeException("batch command [" + i + "] " + action + " failed", e);
                }
                results.add(Collections.singletonMap("error", e.getMessage()));
            }
        }
        return results;
    }

    private Object dispatch(String action, long handle, Map<String, Object> params) {
        switch (action) {
            case "launch":
                boolean headless = params != null && Boolean.TRUE.equals(params.get("headless"));
                String execPath = params != null ? (String) params.get("executablePath") : null;
                @SuppressWarnings("unchecked")
                List<String> args = params != null ? (List<String>) params.get("args") : null;
                long h = engine.launch(headless, execPath, args);
                return Collections.singletonMap("handle", h);
            case "newPage":
                long ph = engine.newPage(handle);
                return Collections.singletonMap("handle", ph);
            case "goto":
                String url = params != null ? (String) params.get("url") : null;
                Engine.ResponseData rd = engine.gotoPage(handle, url, params);
                return rd != null ? Map.of("status", rd.status, "url", rd.url) : Map.of();
            case "click":
                engine.click(handle, (String) params.get("selector"), params);
                return Map.of();
            case "fill":
                engine.fill(handle, (String) params.get("selector"), (String) params.get("value"));
                return Map.of();
            case "screenshot":
                return engine.screenshot(handle, params);
            case "evaluate":
                return engine.evaluate(handle, (String) params.get("expression"), params != null ? params.get("arg") : null);
            case "close":
                engine.close(handle);
                return Map.of();
            default:
                throw new UnsupportedOperationException("unsupported batch action: " + action);
        }
    }

    private int add(String action, Integer handle, Map<String, Object> params) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action", action);
        cmd.put("handle", handle != null ? handle : 0);
        if (params != null) {
            cmd.put("params", params);
        }
        commands.add(cmd);
        names.add(action);
        return commands.size() - 1;
    }
}
