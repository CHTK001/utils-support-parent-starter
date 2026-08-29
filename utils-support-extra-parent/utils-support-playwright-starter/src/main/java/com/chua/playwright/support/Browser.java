package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

import java.util.Map;

/**
 * 双模式 {@code Browser}。
 */
public class Browser {

    final Engine engine;
    final long handle;

    Browser(Engine engine, long handle) { this.engine = engine; this.handle = handle; }

    public long handle() { return handle; }

    public BrowserContext newContext() { return newContext(null); }

    public BrowserContext newContext(Map<String, Object> options) {
        long h = engine.newContext(handle, options);
        return new BrowserContext(engine, h);
    }

    public Page newPage() {
        long h = engine.newPage(handle);
        return new Page(engine, h);
    }

    public void close() { engine.close(handle); }
}