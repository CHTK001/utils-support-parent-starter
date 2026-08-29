package com.chua.playwright.support;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;

/**
 * 验证 native 库能否加载并执行一个完整的 batch 流程。
 * <pre>
 * 假设系统已安装 Chrome / Chromium 且 rust_playwright.dll 已编译。
 * 如需指定 Chrome 路径，在 launch 时传 executablePath。
 * </pre>
 */
public class PlaywrightBatchTest {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Playwright Batch Test =====");
        System.out.println("tempRoot: " + NativeUtils.tempRoot());

        // 触发加载
        Playwright pw = Playwright.create();
        boolean isNative = Playwright.isNative();
        System.out.println("mode: " + (isNative ? "NATIVE (headless_chrome)" : "JAVA (playwright-java)"));
        System.out.println("version: " + Playwright.version());

        int testCount = 0;
        int passCount = 0;

        // 1. launch
        Browser browser = pw.chromium().launch();
        System.out.println("PASS browser launched, handle=" + browser.handle());
        testCount++; passCount++;

        // 2. newPage + goto + title
        Page page = browser.newPage();
        System.out.println("PASS page created, handle=" + page.handle());
        testCount++; passCount++;

        Response resp = page.gotoPage("https://www.baidu.com");
        System.out.println("goto -> status=" + resp.status() + " url=" + resp.url());
        testCount++; passCount++;

        String title = page.title();
        System.out.println("title: " + title);
        testCount++; passCount++;

        // 3. screenshot
        byte[] png = page.screenshot();
        System.out.println("screenshot: " + png.length + " bytes");
        testCount++; passCount++;

        // 4. evaluate
        String url = (String) page.evaluate("window.location.href");
        System.out.println("evaluate url: " + url);
        testCount++; passCount++;

        // 5. querySelector + textContent
        Object body = page.evaluate("document.body ? document.body.innerText.substring(0,50) : 'no-body'");
        System.out.println("page body preview: " + body);
        testCount++; passCount++;

        // 6. close
        page.close();
        browser.close();
        System.out.println("PASS closed");
        testCount++; passCount++;

        // 结果
        System.out.println("===== " + passCount + "/" + testCount + " passed =====");
    }
}