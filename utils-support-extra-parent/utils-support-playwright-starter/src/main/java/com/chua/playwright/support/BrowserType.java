package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

import java.util.List;
import java.util.Map;

/**
* 双模式 {@code BrowserType}。仅支持 铬。
* @author CH
* @since 4.0.0
 */
public class BrowserType {

    private final Engine engine; // engine

    BrowserType(Engine engine) { this.engine = engine; }

    /**
    * launch。
    * @return launch的结果
     */
    public Browser launch() { return launch(null); }

    /**
    * launch。
    * @param options 期权
    * @return launch的结果
     */
    public Browser launch(Map<String, Object> options) {
        boolean headless = true;
        String executablePath = null;
        List<String> args = null;
        if (options != null) {
            if (options.containsKey("headless")) {
                headless = (Boolean) options.get("headless");
            }
            if (options.containsKey("executablePath")) {
                executablePath = (String) options.get("executablePath");
            }
            if (options.containsKey("args")) {
                args = (List<String>) options.get("args");
            }
        }
        long h = engine.launch(headless, executablePath, args);
        return new Browser(engine, h);
    }
}