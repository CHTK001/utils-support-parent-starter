package com.chua.playwright.support;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;

/**
 * 本地 Chrome 截图冒烟测试（main 方法入口，非 JUnit 用例）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GiteeScreenshotTest {

    private static final String CHROME =
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Gitee Screenshot Test ==="); // [P3C E1 豁免]
        System.out.println("Chrome path: " + CHROME); // [P3C E1 豁免]

        Playwright pw = Playwright.create();
        System.out.println("Mode: " + (Playwright.isNative() ? "NATIVE" : "JAVA")); // [P3C E1 豁免]

        // 使用本地 Chrome（缺省 headless=true）
        Browser browser = pw.chromium().launch(Map.of("executablePath", CHROME));
        System.out.println("Browser launched"); // [P3C E1 豁免]

        Page page = browser.newPage();
        System.out.println("Page created"); // [P3C E1 豁免]

        // 访问本地 HTML
        String html = "<html><body><h1>Test</h1></body></html>";
        String dataUri = "data:text/html;base64," + Base64.getEncoder().encodeToString(html.getBytes());
        page.gotoPage(dataUri);
        System.out.println("Navigated to HTML"); // [P3C E1 豁免]

        byte[] png = page.screenshot();
        System.out.println("Screenshot: " + png.length + " bytes"); // [P3C E1 豁免]

        Files.write(Paths.get("test.png"), png);
        System.out.println("Saved to test.png"); // [P3C E1 豁免]

        page.close();
        browser.close();
        System.out.println("=== Test completed ==="); // [P3C E1 豁免]
    }
}
