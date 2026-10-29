package com.chua.playwright.support;

import com.chua.common.support.browser.BrowserEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BrowserEngineSpiTest {

    @Test
    void shouldResolvePlaywrightEngine() {
        BrowserEngine engine = BrowserEngine.of("playwright");
        assertNotNull(engine);
        engine.close();
    }
}
