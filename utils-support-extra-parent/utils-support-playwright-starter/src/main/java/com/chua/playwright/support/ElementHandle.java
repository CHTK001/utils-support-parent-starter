package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

/**
* 双模式 {@code ElementHandle}。
* @author CH
* @since 4.0.0
 */
public class ElementHandle {

    private final Engine engine; // engine
    private final long handle; // 处理

    ElementHandle(Engine engine, long handle) { this.engine = engine; this.handle = handle; }

    /**
    * 处理。
    * @return 处理的结果
    */
    public long handle() { return handle; }

    /**
    * click。
    */
    public void click() { engine.click(handle, null, null); }
    /**
    * dblclick。
    */
    public void dblclick() { engine.dblclick(handle, null); }
    /**
    * fill。
    * @param value 值
    */
    public void fill(String value) { engine.fill(handle, null, value); }
    /**
    * 文本内容。
    * @return 文本内容的结果
    */
    public String textContent() { return engine.textContent(handle, null); }
    /**
    * 内部文本。
    * @return 内部文本的结果
    */
    public String innerText() { return engine.innerText(handle, null); }
    /**
    * 内部html。
    * @return 内部html的结果
    */
    public String innerHTML() { return engine.innerHTML(handle, null); }
    /**
    * 获取attribute。
    * @param name 名称
    * @return 获取attribute的结果
    */
    public String getAttribute(String name) { return engine.getAttribute(handle, null, name); }
    /**
    * hover。
    */
    public void hover() { engine.hover(handle, null); }
    /**
    * screenshot。
    * @return screenshot的结果
    */
    public byte[] screenshot() { return engine.screenshot(handle, null); }
    /**
    * 评估。
    * @param expression expression
    * @return 评估的结果
    */
    public Object evaluate(String expression) { return engine.evaluate(handle, expression, null); }
    /**
    * dispose。
    */
    public void dispose() { engine.close(handle); }
}
