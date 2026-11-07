package com.chua.jvppeteer.support;

import com.chua.common.support.browser.BrowserEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * jvppeteer 引擎的 SPI 注册测试。
 *
 * <p>验证 {@code BrowserEngine.of("pupper")} 能按别名解析到本模块实现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class BrowserEngineSpiTest {

    @Test
    void shouldResolvePupperAliases() {
        BrowserEngine engine = BrowserEngine.of("pupper");
        assertNotNull(engine);
        engine.close();
    }
}
