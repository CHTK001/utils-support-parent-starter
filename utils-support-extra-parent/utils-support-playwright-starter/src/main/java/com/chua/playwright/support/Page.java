package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

import java.util.Map;

/**
* 双模式 {@code Page}。
* @author CH
* @since 4.0.0
 */
public class Page {

    private final Engine engine; // engine
    private final long handle; // 处理

    Page(Engine engine, long handle) { this.engine = engine; this.handle = handle; }

    /**
    * 处理。
    * @return 处理的结果
     */
    public long handle() { return handle; }

    // ==================== 导航 ====================

    /**
    * gotopage。
    * @param url url
    * @return gotoPage的结果
     */
    public Response gotoPage(String url) { return gotoPage(url, null); }

    /**
    * gotopage。
    * @param url url
    * @param options 期权
    * @return gotoPage的结果
     */
    public Response gotoPage(String url, Map<String, Object> options) {
        Engine.ResponseData d = engine.gotoPage(handle, url, options);
        return new Response(d);
    }

    /**
    * reload。
     */
    public void reload() { engine.reload(handle); }

    /**
    * goback。
    * @return goBack的结果
     */
    public Response goBack() {
        Engine.ResponseData d = engine.goBack(handle);
        return d == null ? null : new Response(d);
    }

    /**
    * go远期。
    * @return go远期的结果
     */
    public Response goForward() {
        Engine.ResponseData d = engine.goForward(handle);
        return d == null ? null : new Response(d);
    }

    // ==================== 信息 ====================

    /**
    * title。
    * @return title的结果
     */
    public String title() { return engine.title(handle); }

    /**
    * url。
    * @return url的结果
     */
    public String url() { return engine.url(handle); }

    // ==================== 视口 ====================

    /**
    * 设置viewport大小。
    * @param w w
    * @param h h
     */
    public void setViewportSize(int w, int h) { engine.setViewportSize(handle, w, h); }

    // ==================== 交互 ====================

    /**
    * click。
    * @param selector selector
     */
    public void click(String selector) { engine.click(handle, selector, null); }
    /**
    * click。
    * @param selector selector
    * @param options 期权
     */
    public void click(String selector, Map<String, Object> options) { engine.click(handle, selector, options); }
    /**
    * dblclick。
    * @param selector selector
     */
    public void dblclick(String selector) { engine.dblclick(handle, selector); }
    /**
    * fill。
    * @param selector selector
    * @param value 值
     */
    public void fill(String selector, String value) { engine.fill(handle, selector, value); }
    /**
    * 类型。
    * @param selector selector
    * @param text 文本
     */
    public void type(String selector, String text) { engine.type(handle, selector, text); }
    /**
    * press。
    * @param selector selector
    * @param key 键
     */
    public void press(String selector, String key) { engine.press(handle, selector, key); }
    /**
    * 检查。
    * @param selector selector
     */
    public void check(String selector) { engine.check(handle, selector, true); }
    /**
    * uncheck。
    * @param selector selector
     */
    public void uncheck(String selector) { engine.check(handle, selector, false); }
    /**
    * hover。
    * @param selector selector
     */
    public void hover(String selector) { engine.hover(handle, selector); }

    // ==================== 内容 ====================

    /**
    * 文本内容。
    * @param selector selector
    * @return 文本内容的结果
     */
    public String textContent(String selector) { return engine.textContent(handle, selector); }
    /**
    * 内部文本。
    * @param selector selector
    * @return 内部文本的结果
     */
    public String innerText(String selector) { return engine.innerText(handle, selector); }
    /**
    * 内部html。
    * @param selector selector
    * @return 内部html的结果
     */
    public String innerHTML(String selector) { return engine.innerHTML(handle, selector); }
    /**
    * 获取attribute。
    * @param selector selector
    * @param name 名称
    * @return 获取attribute的结果
     */
    public String getAttribute(String selector, String name) { return engine.getAttribute(handle, selector, name); }
    /**
    * 输入值。
    * @param selector selector
    * @return 输入值的结果
     */
    public String inputValue(String selector) { return engine.inputValue(handle, selector); }

    // ==================== 截图 ====================

    /**
    * screenshot。
    * @return screenshot的结果
     */
    public byte[] screenshot() { return engine.screenshot(handle, null); }
    /**
    * screenshot。
    * @param options 期权
    * @return screenshot的结果
     */
    public byte[] screenshot(Map<String, Object> options) { return engine.screenshot(handle, options); }

    // ==================== 脚本 ====================

    /**
    * 评估。
    * @param expression expression
    * @return 评估的结果
     */
    public Object evaluate(String expression) { return engine.evaluate(handle, expression, null); }
    /**
    * 评估。
    * @param expression expression
    * @param arg 参数
    * @return 评估的结果
     */
    public Object evaluate(String expression, Object arg) { return engine.evaluate(handle, expression, arg); }

    // ==================== 元素查询 ====================

    /**
    * 查询selector。
    * @param selector selector
    * @return 查询selector的结果
     */
    public ElementHandle querySelector(String selector) {
        long h = engine.querySelector(handle, selector);
        if (h < 0) {
            return null;
        }
        return new ElementHandle(engine, h);
    }

    /**
    * 查询selector全部。
    * @param selector selector
    * @return 查询selector全部的结果
     */
    public java.util.List<ElementHandle> querySelectorAll(String selector) {
        java.util.List<Long> ids = engine.querySelectorAll(handle, selector);
        java.util.List<ElementHandle> result = new java.util.ArrayList<>();
        for (long id : ids) {
            result.add(new ElementHandle(engine, id));
        }
        return result;
    }

    /**
    * waitforselector。
    * @param selector selector
     */
    public void waitForSelector(String selector) { engine.waitForSelector(handle, selector, null); }
    /**
    * waitforselector。
    * @param selector selector
    * @param timeoutMs 超时ms
     */
    public void waitForSelector(String selector, long timeoutMs) { engine.waitForSelector(handle, selector, timeoutMs); }

    /**
    * 选择期权。
    * @param selector selector
    * @param values 值
    * @return 选择期权的结果
     */
    public java.util.List<String> selectOption(String selector, String... values) {
        return engine.selectOption(handle, selector, values);
    }

    // ==================== 关闭 ====================

    /**
    * 关闭。
     */
    public void close() { engine.close(handle); }
}