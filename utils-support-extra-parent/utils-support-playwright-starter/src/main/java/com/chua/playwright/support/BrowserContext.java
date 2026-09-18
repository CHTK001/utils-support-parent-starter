package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

/**
* 双模式 {@code BrowserContext}。
* @author CH
* @since 4.0.0
 */
public class BrowserContext {

    final Engine engine; // engine
    final long handle; // 处理

    /**
     * 创建浏览器上下文句柄包装。
     *
     * @param engine 驱动引擎实例，不允许为 null
     * @param handle 引擎侧的上下文句柄标识
     */
    BrowserContext(Engine engine, long handle) {
        this.engine = engine;
        this.handle = handle;
    }

    /**
    * 处理。
    * @return 处理的结果
    */
    public long handle() { return handle; }

    /**
    * 新page。
    * @return 新page的结果
    */
    public Page newPage() {
        long h = engine.newPage(handle);
        return new Page(engine, h);
    }

    public void close() { engine.close(handle); }
}
