package com.chua.playwright.support;

import com.chua.common.support.browser.BrowserEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Playwright 引擎的 SPI 注册测试。
 *
 * <p>验证 {@code BrowserEngine.of("playwright")} 能解析到本模块实现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class BrowserEngineSpiTest {

    @Test
    void shouldResolvePlaywrightEngine() {
        BrowserEngine engine = BrowserEngine.of("playwright");
        assertNotNull(engine);
        engine.close();
    }
}
