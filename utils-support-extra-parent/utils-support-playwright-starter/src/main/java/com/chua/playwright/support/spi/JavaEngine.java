package com.chua.playwright.support.spi;

import com.chua.playwright.support.PlaywrightException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * playwright-java 回退引擎。<br>
 * 当 {@link PlaywrightNative} 无法加载 native 库时自动启用。
 * 内部维护自己的句柄→对象注册表，方法直接委托给官方 playwright-java API。
 * <p>
 * TODO: 更多方法的 playwright-java 映射
 */
public class JavaEngine implements Engine {

    private com.microsoft.playwright.Playwright pw;
    final Map<Long, Object> registry = new ConcurrentHashMap<>();
    private long nextHandle = 1;

    private synchronized long alloc() { return nextHandle++; }

    synchronized com.microsoft.playwright.Playwright playwright() {
        if (pw == null) pw = com.microsoft.playwright.Playwright.create();
        return pw;
    }

    @SuppressWarnings("unchecked")
    private <T> T get(long handle, Class<T> type) {
        Object o = registry.get(handle);
        if (!type.isInstance(o)) throw new PlaywrightException("handle " + handle + " 不是 " + type.getSimpleName());
        return (T) o;
    }

    // ==================== core path ====================

    @Override
    public long launch(boolean headless, String executablePath, List<String> args) {
        com.microsoft.playwright.BrowserType.LaunchOptions opts = new com.microsoft.playwright.BrowserType.LaunchOptions();
        opts.setHeadless(headless);
        if (executablePath != null) opts.setExecutablePath(java.nio.file.Paths.get(executablePath));
        com.microsoft.playwright.Browser browser = playwright().chromium().launch(opts);
        long h = alloc();
        registry.put(h, browser);
        return h;
    }

    @Override
    public long newContext(long browserHandle, Map<String, Object> options) {
        com.microsoft.playwright.Browser browser = get(browserHandle, com.microsoft.playwright.Browser.class);
        com.microsoft.playwright.BrowserContext ctx = browser.newContext();
        long h = alloc();
        registry.put(h, ctx);
        return h;
    }

    @Override
    public long newPage(long targetHandle) {
        Object target = registry.get(targetHandle);
        com.microsoft.playwright.Page page;
        if (target instanceof com.microsoft.playwright.BrowserContext) {
            page = ((com.microsoft.playwright.BrowserContext) target).newPage();
        } else if (target instanceof com.microsoft.playwright.Browser) {
            page = ((com.microsoft.playwright.Browser) target).newPage();
        } else {
            com.microsoft.playwright.BrowserContext ctx = ((com.microsoft.playwright.Browser) get(targetHandle, com.microsoft.playwright.Browser.class)).newContext();
            page = ctx.newPage();
            registry.put(targetHandle, ctx);
        }
        long h = alloc();
        registry.put(h, page);
        return h;
    }

    @Override
    public ResponseData gotoPage(long pageHandle, String url, Map<String, Object> options) {
        com.microsoft.playwright.Page page = get(pageHandle, com.microsoft.playwright.Page.class);
        com.microsoft.playwright.Page.NavigateOptions opts = null;
        if (options != null) {
            opts = new com.microsoft.playwright.Page.NavigateOptions();
            if (options.containsKey("timeout")) opts.setTimeout((Integer) options.get("timeout"));
        }
        com.microsoft.playwright.Response resp = page.navigate(url, opts);
        return new ResponseData(resp.status(), resp.url());
    }

    @Override
    public void click(long handle, String selector, Map<String, Object> options) {
        Object target = registry.get(handle);
        if (target instanceof com.microsoft.playwright.ElementHandle) {
            ((com.microsoft.playwright.ElementHandle) target).click();
        } else if (target instanceof com.microsoft.playwright.Page) {
            ((com.microsoft.playwright.Page) target).click(selector);
        } else {
            get(handle, com.microsoft.playwright.Page.class).click(selector);
        }
    }

    @Override
    public void fill(long handle, String selector, String value) {
        get(handle, com.microsoft.playwright.Page.class).fill(selector, value);
    }

    @Override
    public String title(long handle) {
        return get(handle, com.microsoft.playwright.Page.class).title();
    }

    @Override
    public String url(long handle) {
        return get(handle, com.microsoft.playwright.Page.class).url();
    }

    @Override
    public byte[] screenshot(long handle, Map<String, Object> options) {
        com.microsoft.playwright.Page page = get(handle, com.microsoft.playwright.Page.class);
        com.microsoft.playwright.Page.ScreenshotOptions opts = new com.microsoft.playwright.Page.ScreenshotOptions();
        return page.screenshot(opts);
    }

    @Override
    public Object evaluate(long handle, String expression, Object arg) {
        com.microsoft.playwright.Page page = get(handle, com.microsoft.playwright.Page.class);
        return page.evaluate(expression);
    }

    @Override
    public long querySelector(long handle, String selector) {
        com.microsoft.playwright.Page page = get(handle, com.microsoft.playwright.Page.class);
        com.microsoft.playwright.ElementHandle el = page.querySelector(selector);
        if (el == null) return -1;
        long h = alloc();
        registry.put(h, el);
        return h;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> querySelectorAll(long handle, String selector) {
        com.microsoft.playwright.Page page = get(handle, com.microsoft.playwright.Page.class);
        List<com.microsoft.playwright.ElementHandle> els = page.querySelectorAll(selector);
        List<Long> ids = new ArrayList<>();
        for (com.microsoft.playwright.ElementHandle el : els) {
            long h = alloc();
            registry.put(h, el);
            ids.add(h);
        }
        return ids;
    }

    @Override
    public String innerText(long handle, String selector) {
        return get(handle, com.microsoft.playwright.Page.class).innerText(selector);
    }

    @Override
    public String innerHTML(long handle, String selector) {
        return get(handle, com.microsoft.playwright.Page.class).innerHTML(selector);
    }

    @Override
    public void close(long handle) {
        Object obj = registry.remove(handle);
        if (obj instanceof com.microsoft.playwright.Browser) {
            ((com.microsoft.playwright.Browser) obj).close();
        } else if (obj instanceof com.microsoft.playwright.Page) {
            ((com.microsoft.playwright.Page) obj).close();
        } else if (obj instanceof com.microsoft.playwright.BrowserContext) {
            ((com.microsoft.playwright.BrowserContext) obj).close();
        }
    }

    // ==================== 以下暂用 NativeEngine 逻辑（reuse JSON path） ====================

    @Override public void dblclick(long handle, String selector) { throw unimplemented(); }
    @Override public void type(long handle, String selector, String text) { get(handle, com.microsoft.playwright.Page.class).type(selector, text); }
    @Override public void press(long handle, String selector, String key) { get(handle, com.microsoft.playwright.Page.class).press(selector, key); }
    @Override public void check(long handle, String selector, boolean checked) {
        if (checked) get(handle, com.microsoft.playwright.Page.class).check(selector);
        else get(handle, com.microsoft.playwright.Page.class).uncheck(selector);
    }
    @Override public void hover(long handle, String selector) { get(handle, com.microsoft.playwright.Page.class).hover(selector); }
    @Override public String textContent(long handle, String selector) { return get(handle, com.microsoft.playwright.Page.class).textContent(selector); }
    @Override public String getAttribute(long handle, String selector, String name) { return get(handle, com.microsoft.playwright.Page.class).getAttribute(selector, name); }
    @Override public String inputValue(long handle, String selector) { return get(handle, com.microsoft.playwright.Page.class).inputValue(selector); }
    @Override public void waitForSelector(long handle, String selector, Long timeoutMs) {
        com.microsoft.playwright.Page.WaitForSelectorOptions opts = null;
        if (timeoutMs != null) opts = new com.microsoft.playwright.Page.WaitForSelectorOptions().setTimeout(timeoutMs);
        get(handle, com.microsoft.playwright.Page.class).waitForSelector(selector, opts);
    }
    @Override public void setViewportSize(long handle, int width, int height) { get(handle, com.microsoft.playwright.Page.class).setViewportSize(width, height); }
    @Override public void reload(long handle) { get(handle, com.microsoft.playwright.Page.class).reload(); }
    @Override public ResponseData goBack(long handle) {
        com.microsoft.playwright.Response resp = get(handle, com.microsoft.playwright.Page.class).goBack();
        return resp == null ? null : new ResponseData(resp.status(), resp.url());
    }
    @Override public ResponseData goForward(long handle) {
        com.microsoft.playwright.Response resp = get(handle, com.microsoft.playwright.Page.class).goForward();
        return resp == null ? null : new ResponseData(resp.status(), resp.url());
    }
    @Override public List<String> selectOption(long handle, String selector, String[] values) {
        return get(handle, com.microsoft.playwright.Page.class).selectOption(selector, values);
    }
    @Override public long newAPIRequest() { throw unimplemented(); }
    @Override public ApiResponseData apiRequest(String action, String url, Object body) { throw unimplemented(); }
    @Override public List<Object> batch(List<Map<String, Object>> commands, boolean stopOnError) { throw unimplemented(); }
    @Override public String version() { return "1.48.0-java"; }

    private UnsupportedOperationException unimplemented() {
        return new UnsupportedOperationException("JavaEngine 暂未实现此方法，请安装 Rust native 库");
    }
}