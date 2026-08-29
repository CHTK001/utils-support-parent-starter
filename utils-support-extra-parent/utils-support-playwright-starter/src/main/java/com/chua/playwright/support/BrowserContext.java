package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

/**
 * 双模式 {@code BrowserContext}。
 */
public class BrowserContext {

    final Engine engine;
    final long handle;

    BrowserContext(Engine engine, long handle) { this.engine = engine; this.handle = handle; }

    public long handle() { return handle; }

    public Page newPage() {
        long h = engine.newPage(handle);
        return new Page(engine, h);
    }

    public void close() { engine.close(handle); }
}