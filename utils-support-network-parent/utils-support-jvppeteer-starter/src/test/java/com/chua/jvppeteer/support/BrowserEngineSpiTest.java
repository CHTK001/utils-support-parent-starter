package com.chua.jvppeteer.support;

import com.chua.common.support.browser.BrowserEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BrowserEngineSpiTest {

    @Test
    void shouldResolvePupperAliases() {
        BrowserEngine engine = BrowserEngine.of("pupper");
        assertNotNull(engine);
        engine.close();
    }
}
