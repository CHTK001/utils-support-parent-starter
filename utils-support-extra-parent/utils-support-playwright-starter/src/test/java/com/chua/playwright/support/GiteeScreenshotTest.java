package com.chua.playwright.support;

import com.chua.common.support.utils.NativeUtils;
import java.nio.file.*;

public class GiteeScreenshotTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Gitee Screenshot Test ===");
        System.out.println("Chrome path: C:\Program Files\Google\Chrome\Application\chrome.exe");
        
        Playwright pw = Playwright.create();
        System.out.println("Mode: " + (Playwright.isNative() ? "NATIVE" : "JAVA"));
        
        // 使用本地 Chrome
        Browser browser = pw.chromium().launch(null, "C:\Program Files\Google\Chrome\Application\chrome.exe", null);
        System.out.println("Browser launched");
        
        Page page = browser.newPage();
        System.out.println("Page created");
        
        // 访问本地 HTML
        String html = "<html><body><h1>Test</h1></body></html>";
        String dataUri = "data:text/html;base64," + java.util.Base64.getEncoder().encodeToString(html.getBytes());
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
