package com.chua.playwright.support;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;

/**
 * 本地 Chrome 截图冒烟测试（main 方法入口，非 JUnit 用例）。
 */
public class GiteeScreenshotTest {

    private static final String CHROME =
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Gitee Screenshot Test ===");
        System.out.println("Chrome path: " + CHROME);

        Playwright pw = Playwright.create();
        System.out.println("Mode: " + (Playwright.isNative() ? "NATIVE" : "JAVA"));

        // 使用本地 Chrome（缺省 headless=true）
        Browser browser = pw.chromium().launch(Map.of("executablePath", CHROME));
        System.out.println("Browser launched");

        Page page = browser.newPage();
        System.out.println("Page created");

        // 访问本地 HTML
        String html = "<html><body><h1>Test</h1></body></html>";
        String dataUri = "data:text/html;base64," + Base64.getEncoder().encodeToString(html.getBytes());
        page.gotoPage(dataUri);
        System.out.println("Navigated to HTML");

        byte[] png = page.screenshot();
        System.out.println("Screenshot: " + png.length + " bytes");

        Files.write(Paths.get("test.png"), png);
        System.out.println("Saved to test.png");

        page.close();
        browser.close();
        System.out.println("=== Test completed ===");
    }
}
