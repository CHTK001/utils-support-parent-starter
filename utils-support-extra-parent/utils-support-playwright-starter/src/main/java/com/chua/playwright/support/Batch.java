package com.chua.playwright.support;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.playwright.support.engine.Engine;

import java.util.*;

/**
 * 批量命令构建器。逐条命令通过 {@link Engine} 顺序执行，模拟一次批量调用的语义。
 *
 * @author CH
 * @since 4.0.0
 */
public class Batch {

    /**
     * 底层驱动引擎
     */
    private final Engine engine;

    /**
     * 待执行的命令队列，每项包含 action / handle / params
     */
    private final List<Map<String, Object>> commands = Wraps.list();

    /**
     * 命令名队列，与 {@link #commands} 一一对应
     */
    private final List<String> names = Wraps.list();

    /**
     * 遇到错误是否立即中止执行
     */
    private boolean stopOnError = true;

    /**
     * 创建批次构建器，绑定当前线程的驱动引擎。
     */
    public Batch() {
        this.engine = Playwright.getEngine();
    }

    /**
     * 设置批次执行遇到错误时是否中止。
     *
     * @param stop {@code true} 表示首个命令失败后即停止执行；{@code false} 表示忽略错误继续执行
     * @return 当前批次实例，便于链式调用
     */
    public Batch stopOnError(boolean stop) {
        this.stopOnError = stop;
        return this;
    }

    /**
     * 获取已入队的命令数量。
     *
     * @return 命令数量
     */
    public int size() {
        return commands.size();
    }

    /**
     * 追加启动浏览器命令。
     *
     * @param headless 是否无头模式
     * @return 当前命令在队列中的下标
     */
    public int launch(boolean headless) {
        Map<String, Object> p = Wraps.linkedMap();
        p.put("headless", headless);
        return add("launch", null, p);
    }

    /**
     * 追加新建页面命令。
     *
     * @param targetHandle 浏览器句柄
     * @return 当前命令在队列中的下标
     */
    public int newPage(int targetHandle) {
        return add("newPage", targetHandle, null);
    }

    /**
     * 追加页面跳转命令。
     *
     * @param pageHandle 页面句柄
     * @param url        目标地址
     */
    public void gotoPage(int pageHandle, String url) {
        Map<String, Object> p = Wraps.linkedMap();
        p.put("url", url);
        add("goto", pageHandle, p);
    }

    /**
     * 追加元素点击命令。
     *
     * @param handle   页面句柄
     * @param selector 元素选择器
     */
    public void click(int handle, String selector) {
        Map<String, Object> p = Wraps.linkedMap();
        p.put("selector", selector);
        add("click", handle, p);
    }

    /**
     * 追加元素填充命令。
     *
     * @param handle   页面句柄
     * @param selector 元素选择器
     * @param value    填充值
     */
    public void fill(int handle, String selector, String value) {
        Map<String, Object> p = Wraps.linkedMap();
        p.put("selector", selector);
        p.put("value", value);
        add("fill", handle, p);
    }

    /**
     * 追加页面截图命令。
     *
     * @param handle 页面句柄
     */
    public void screenshot(int handle) {
        add("screenshot", handle, null);
    }

    /**
     * 追加页面脚本求值命令。
     *
     * @param handle     页面句柄
     * @param expression JS 表达式
     */
    public void evaluate(int handle, String expression) {
        Map<String, Object> p = Wraps.linkedMap();
        p.put("expression", expression);
        add("evaluate", handle, p);
    }

    /**
     * 追加页面关闭命令。
     *
     * @param handle 页面句柄
     */
    public void close(int handle) {
        add("close", handle, null);
    }

    /**
     * 追加自定义原始命令，由调用方自行约定 action 语义。
     *
     * @param action 动作名
     * @param handle 句柄，可为 null
     * @param params 参数，可为 null
     */
    public void raw(String action, Integer handle, Map<String, Object> params) {
        add(action, handle, params);
    }

    /**
     * 逐条执行所有命令。
     *
     * <p>当 {@code stopOnError=true} 时，遇到异常立即停止并抛出；
     * 否则将异常以 {@code error} 键的形式记录到对应位置后继续执行。</p>
     *
     * @return 结果列表，无数据时为空列表
     */
    public List<Object> execute() {
        List<Object> results = Wraps.list();
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

    /**
     * 按动作名分发到引擎的具体调用。
     *
     * @param action 动作名
     * @param handle 句柄
     * @param params 参数，可为 null
     * @return 引擎返回结果的包装
     */
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

    /**
     * 将命令加入队列并返回其下标。
     *
     * @param action 动作名
     * @param handle 句柄，可为 null
     * @param params 参数，可为 null
     * @return 当前命令在队列中的下标
     */
    private int add(String action, Integer handle, Map<String, Object> params) {
        Map<String, Object> cmd = Wraps.linkedMap();
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
