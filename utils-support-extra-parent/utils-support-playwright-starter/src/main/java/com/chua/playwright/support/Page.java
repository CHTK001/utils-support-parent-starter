package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

import java.util.Map;

/**
 * 双模式 {@code Page}。
 */
public class Page {

    private final Engine engine;
    private final long handle;

    Page(Engine engine, long handle) { this.engine = engine; this.handle = handle; }

    public long handle() { return handle; }

    // ==================== 导航 ====================

    public Response gotoPage(String url) { return gotoPage(url, null); }

    public Response gotoPage(String url, Map<String, Object> options) {
        Engine.ResponseData d = engine.gotoPage(handle, url, options);
        return new Response(d);
    }

    public void reload() { engine.reload(handle); }

    public Response goBack() {
        Engine.ResponseData d = engine.goBack(handle);
        return d == null ? null : new Response(d);
    }

    public Response goForward() {
        Engine.ResponseData d = engine.goForward(handle);
        return d == null ? null : new Response(d);
    }

    // ==================== 信息 ====================

    public String title() { return engine.title(handle); }

    public String url() { return engine.url(handle); }

    // ==================== 视口 ====================

    public void setViewportSize(int w, int h) { engine.setViewportSize(handle, w, h); }

    // ==================== 交互 ====================

    public void click(String selector) { engine.click(handle, selector, null); }
    public void click(String selector, Map<String, Object> options) { engine.click(handle, selector, options); }
    public void dblclick(String selector) { engine.dblclick(handle, selector); }
    public void fill(String selector, String value) { engine.fill(handle, selector, value); }
    public void type(String selector, String text) { engine.type(handle, selector, text); }
    public void press(String selector, String key) { engine.press(handle, selector, key); }
    public void check(String selector) { engine.check(handle, selector, true); }
    public void uncheck(String selector) { engine.check(handle, selector, false); }
    public void hover(String selector) { engine.hover(handle, selector); }

    // ==================== 内容 ====================

    public String textContent(String selector) { return engine.textContent(handle, selector); }
    public String innerText(String selector) { return engine.innerText(handle, selector); }
    public String innerHTML(String selector) { return engine.innerHTML(handle, selector); }
    public String getAttribute(String selector, String name) { return engine.getAttribute(handle, selector, name); }
    public String inputValue(String selector) { return engine.inputValue(handle, selector); }

    // ==================== 截图 ====================

    public byte[] screenshot() { return engine.screenshot(handle, null); }
    public byte[] screenshot(Map<String, Object> options) { return engine.screenshot(handle, options); }

    // ==================== 脚本 ====================

    public Object evaluate(String expression) { return engine.evaluate(handle, expression, null); }
    public Object evaluate(String expression, Object arg) { return engine.evaluate(handle, expression, arg); }

    // ==================== 元素查询 ====================

    public ElementHandle querySelector(String selector) {
        long h = engine.querySelector(handle, selector);
        if (h < 0) return null;
        return new ElementHandle(engine, h);
    }

    public java.util.List<ElementHandle> querySelectorAll(String selector) {
        java.util.List<Long> ids = engine.querySelectorAll(handle, selector);
        java.util.List<ElementHandle> result = new java.util.ArrayList<>();
        for (long id : ids) result.add(new ElementHandle(engine, id));
        return result;
    }

    public void waitForSelector(String selector) { engine.waitForSelector(handle, selector, null); }
    public void waitForSelector(String selector, long timeoutMs) { engine.waitForSelector(handle, selector, timeoutMs); }

    public java.util.List<String> selectOption(String selector, String... values) {
        return engine.selectOption(handle, selector, values);
    }

    // ==================== 关闭 ====================

    public void close() { engine.close(handle); }
}