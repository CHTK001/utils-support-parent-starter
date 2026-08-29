package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

/**
 * 双模式 {@code ElementHandle}。
 */
public class ElementHandle {

    private final Engine engine;
    private final long handle;

    ElementHandle(Engine engine, long handle) { this.engine = engine; this.handle = handle; }

    public long handle() { return handle; }

    public void click() { engine.click(handle, null, null); }
    public void dblclick() { engine.dblclick(handle, null); }
    public void fill(String value) { engine.fill(handle, null, value); }
    public String textContent() { return engine.textContent(handle, null); }
    public String innerText() { return engine.innerText(handle, null); }
    public String innerHTML() { return engine.innerHTML(handle, null); }
    public String getAttribute(String name) { return engine.getAttribute(handle, null, name); }
    public void hover() { engine.hover(handle, null); }
    public byte[] screenshot() { return engine.screenshot(handle, null); }
    public Object evaluate(String expression) { return engine.evaluate(handle, expression, null); }
    public void dispose() { engine.close(handle); }
}