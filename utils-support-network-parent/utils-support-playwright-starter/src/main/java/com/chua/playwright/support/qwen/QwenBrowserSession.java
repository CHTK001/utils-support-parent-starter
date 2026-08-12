package com.chua.playwright.support.qwen;

import com.chua.common.support.lang.json.JsonObject;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 通义千问浏览器会话。
 *
 * <p>基于 Playwright 注入 Cookie，通过 UI 交互触发发送（让 bx 自动加签名），
 * 再从网络层拦截 SSE 响应流，实现异步非阻塞。
 *
 * @author CH
 * @since 2026/08/12
 */
@Slf4j
public class QwenBrowserSession implements AutoCloseable {

    private static final long PAGE_LOAD_TIMEOUT_MS = 60000;
    private static final long RESPONSE_TIMEOUT_MS = 120000;
    private static final String[] STEALTH_ARGS = {
            "--disable-blink-features=AutomationControlled", "--no-sandbox",
            "--disable-web-security", "--disable-features=IsolateOrigins,site-per-process",
            "--disable-site-isolation-trials", "--disable-setuid-sandbox",
            "--no-first-run", "--no-default-browser-check", "--disable-extensions"
    };

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;

    public QwenBrowserSession(String cookieString, String userDataDir) {
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true).setArgs(List.of(STEALTH_ARGS)).setTimeout(PAGE_LOAD_TIMEOUT_MS));
        this.context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 720)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"));
        this.context.setDefaultNavigationTimeout(PAGE_LOAD_TIMEOUT_MS);
        injectCookies(cookieString);
    }

    public void init() {
        try (var page = context.newPage()) {
            page.navigate("https://chat.qwen.ai");
            log.info("通义千问首页加载完成");
        }
    }

    /**
     * 发送聊天消息。
     * UI 输入触发发送（让 bx 自动签名），然后拦截网络响应的 SSE 流。
     */
    public QwenChatResult chat(String body, String model, BiConsumer<String, String> listener) {
        String prompt = extractPrompt(body);
        if (prompt == null) prompt = "hello";

        try (var page = context.newPage()) {
            // 准备响应拦截
            CompletableFuture<QwenChatResult> future = new CompletableFuture<>();

            page.onResponse(resp -> {
                String url = resp.url();
                if (!url.contains("/api/v2/chat/completions")) return;
                // 异步读取 SSE 流
                CompletableFuture.runAsync(() -> {
                    try {
                        StringBuilder text = new StringBuilder();
                        StringBuilder thinking = new StringBuilder();
                        List<Map<String, Object>> rawEvents = new ArrayList<>();
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(
                                        new java.io.ByteArrayInputStream(resp.body()),
                                        java.nio.charset.StandardCharsets.UTF_8));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("data:")) line = line.substring(5).trim();
                            if (line.equals("[DONE]") || line.isEmpty()) continue;
                            try {
                                Map<String, Object> obj = com.chua.common.support.lang.json.Json.fromJson(line, Map.class);
                                if (obj == null) continue;
                                rawEvents.add(obj);
                                List<Map<String, Object>> choices = (List<Map<String, Object>>) obj.get("choices");
                                if (choices == null || choices.isEmpty()) continue;
                                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                if (delta == null) continue;
                                String phase = (String) delta.get("phase");
                                String content = (String) delta.get("content");
                                if (content == null || content.isEmpty()) continue;
                                if ("think".equals(phase) || "thinking".equals(phase)) {
                                    thinking.append(content);
                                    if (listener != null) listener.accept("thinking", content);
                                } else {
                                    text.append(content);
                                    if (listener != null) listener.accept("text", content);
                                }
                            } catch (Exception ignored) {}
                        }
                        future.complete(QwenChatResult.ok(text.toString(), thinking.toString(), "", rawEvents));
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            });

            // 加载页面
            page.navigate("https://chat.qwen.ai");
            page.waitForLoadState();
            page.waitForTimeout(10000);

            // UI 输入触发发送
            ElementHandle textarea = page.querySelector("textarea.message-input-textarea");
            if (textarea == null) {
                return QwenChatResult.error("未找到聊天输入框");
            }
            textarea.click();
            page.keyboard().type(prompt);
            page.waitForTimeout(300);
            page.keyboard().press("Enter");

            // 等待响应完成
            try {
                QwenChatResult result = future.get(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return result;
            } catch (java.util.concurrent.TimeoutException e) {
                return QwenChatResult.error("响应超时");
            }
        } catch (Exception e) {
            log.warn("通义千问聊天请求失败: {}", e.getMessage());
            return QwenChatResult.error(e.getMessage());
        }
    }

    @Override
    public void close() {
        Exception ex = null;
        try { context.close(); } catch (Exception e) { ex = e; }
        try { browser.close(); } catch (Exception e) { if (ex == null) ex = e; }
        try { playwright.close(); } catch (Exception e) { if (ex == null) ex = e; }
        if (ex != null) log.warn("释放通义千问浏览器会话失败", ex);
    }

    private void injectCookies(String cookieString) {
        Map<String, String> cookies = parseCookies(cookieString);
        List<com.microsoft.playwright.options.Cookie> cookieList = new ArrayList<>(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            cookieList.add(new com.microsoft.playwright.options.Cookie(entry.getKey(), entry.getValue())
                    .setDomain(".qwen.ai").setPath("/"));
        }
        context.addCookies(cookieList);
    }

    private static Map<String, String> parseCookies(String cookieString) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cookieString == null || cookieString.isBlank()) return map;
        for (String pair : cookieString.split(";")) {
            String trim = pair.trim();
            int idx = trim.indexOf('=');
            if (idx <= 0) continue;
            map.put(trim.substring(0, idx).trim(), trim.substring(idx + 1).trim());
        }
        return map;
    }

    private static String extractPrompt(String body) {
        try {
            JsonObject obj = JsonObject.parse(body);
            Object msgs = obj.get("messages");
            if (msgs instanceof List<?> list && !list.isEmpty()) {
                Object last = list.get(list.size() - 1);
                if (last instanceof Map<?, ?> m) {
                    Object content = m.get("content");
                    if (content instanceof String s) return s;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}