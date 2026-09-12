package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

import java.util.Map;

/**
 * 双模式 {@code Browser}。
 * @author CH
 * @since 4.0.0
 * @return 新page的结果
 */
public class Browser {

    final Engine engine; // engine
    final long handle; // 处理

    Browser(Engine engine, long handle) { this.engine = engine; this.handle = handle; }
/**
 * 处理。
 * @return 处理的结果
 */

    public long handle() { return handle; }
/**
 * 新上下文。
 * @return 新上下文的结果
 */

    public BrowserContext newContext() { return newContext(null); }
/**
 * 新上下文。
 * @param options 期权
 * @return 新上下文的结果
 */

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