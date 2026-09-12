package com.chua.playwright.support.qwen;

import com.chua.common.support.lang.json.JsonObject;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
* 通义千问浏览器会话。
*
* <p>基于 Playwright 注入 Cookie，保持持久页面，在同一会话中连续对话，
* 让 通义千问 云端自动维护上下文历史。
*
* @author CH
* @since 2026/08/12
 */
@Slf4j
public class QwenBrowserSession implements AutoCloseable {

    /**
    * 页面 加载 超时时间 毫秒
     */
    private static final long PAGE_LOAD_TIMEOUT_MS = 60000;
    /**
    * 响应 超时时间 毫秒
     */
    private static final long RESPONSE_TIMEOUT_MS = 120000;
    /**
    * 隐身 参数
     */
    private static final String[] STEALTH_ARGS = {
            "--disable-blink-features=AutomationControlled", "--no-sandbox",
            "--disable-web-security", "--disable-features=IsolateOrigins,site-per-process",
            "--disable-site-isolation-trials", "--disable-setuid-sandbox",
            "--no-first-run", "--no-default-browser-check", "--disable-extensions"
    };

    /**
    * Playwright 实例
     */
    private final Playwright playwright;
    /**
    * 浏览器实例
     */
    private final Browser browser;
    /**
    * 浏览器上下文
     */
    private final BrowserContext context;
    /**
    * 页面实例
     */
    private Page page;
    /**
    * 页面是否就绪
     */
    private boolean pageReady;

    /**
    * 创建 通义千问browser会话 实例
    * @param cookieString Cookie字符串
    * @param cookieString 字符串
    * @param userDataDir 用户数据dir
     */
    public QwenBrowserSession(String cookieString, String userDataDir) {
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true).setArgs(List.of(STEALTH_ARGS)).setTimeout(PAGE_LOAD_TIMEOUT_MS));
        this.context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 720)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"));
        this.context.setDefaultNavigationTimeout(PAGE_LOAD_TIMEOUT_MS);
        injectCookies(cookieString);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    /**
    * 初始化页面并加载通义千问。
     */
    public void init() {
        page = context.newPage();
        page.navigate("https://chat.qwen.ai");
        page.waitForLoadState();
        page.waitForTimeout(10000);
        pageReady = true;
        log.info("通义千问页面已就绪");
    }

    /**
    * 开始新会话：导航到千问首页，创建新会话。
     */
    public void newChat() {
        if (page != null) {
            try {
                page.navigate("https://chat.qwen.ai");
                page.waitForLoadState();
                page.waitForTimeout(5000);
            } catch (Exception e) {
                log.warn("新建会话失败: {}", e.getMessage());
            }
        }
    }

    /**
    * 发送聊天消息并等待回答。
    * 在持久页面中连续输入，保持 通义千问 云端会话上下文。
    *
    * @param body     JSON 请求体字符串
    * @param model    模型名称
    * @param listener 流式事件监听器，可为空
    * @return 解析后的聊天结果
     */
    public QwenChatResult chat(String body, String model, BiConsumer<String, String> listener) {
        if (!pageReady || page == null) {
            return QwenChatResult.error("页面未初始化");
        }

        String prompt = extractPrompt(body);
        if (prompt == null) {
            return QwenChatResult.error("无法从请求体解析用户输入");
        }

        try {
            // 查找输入框并输入
            ElementHandle textarea = page.querySelector("textarea.message-input-textarea");
            if (textarea == null) {
                return QwenChatResult.error("未找到聊天输入框");
            }
            textarea.click();
            page.keyboard().type(prompt);
            page.waitForTimeout(300);

            // 发送并等待响应
            Response resp = page.waitForResponse(
                    r -> r.url().contains("/api/v2/chat/completions"),
                    new Page.WaitForResponseOptions().setTimeout(RESPONSE_TIMEOUT_MS),
                    () -> page.keyboard().press("Enter"));

            // 读取 SSE 响应体
            String sse = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            List<Map<String, Object>> rawEvents = new ArrayList<>();
            for (String line : sse.split("\n")) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]") || data.isEmpty()) {
                        continue;
                    }
                    try {
                        Map<String, Object> obj = com.chua.common.support.lang.json.Json.fromJson(data, Map.class);
                        if (obj == null) {
                            continue;
                        }
                        rawEvents.add(obj);
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) obj.get("choices");
                        if (choices == null || choices.isEmpty()) {
                            continue;
                        }
                        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                        if (delta == null) {
                            continue;
                        }
                        String phase = (String) delta.get("phase");
                        String content = (String) delta.get("content");
                        if (content == null || content.isEmpty()) {
                            continue;
                        }
                        if ("think".equals(phase) || "thinking".equals(phase)) {
                            thinking.append(content);
                        } else {
                            text.append(content);
                        }
                        if (listener != null) {
                            listener.accept("think".equals(phase) || "thinking".equals(phase) ? "thinking" : "content", content);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return QwenChatResult.ok(text.toString(), thinking.toString(), "", rawEvents);
        } catch (Exception e) {
            log.warn("通义千问聊天请求失败: {}", e.getMessage());
            return QwenChatResult.error(e.getMessage());
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        Exception ex = null;
        try { if (page != null) page.close(); } catch (Exception e) { ex = e; }
        try { context.close(); } catch (Exception e) { if (ex == null) ex = e; }
        try { browser.close(); } catch (Exception e) { if (ex == null) ex = e; }
        try { playwright.close(); } catch (Exception e) { if (ex == null) ex = e; }
        if (ex != null) {
            log.warn("关闭通义千问浏览器会话异常: {}", ex.getMessage());
        }
    }

    /**
    * injectCookie
    *
    * @param cookieString Cookie字符串
     */
    private void injectCookies(String cookieString) {
        Map<String, String> cookies = parseCookies(cookieString);
        List<com.microsoft.playwright.options.Cookie> cookieList = new ArrayList<>(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            cookieList.add(new com.microsoft.playwright.options.Cookie(entry.getKey(), entry.getValue())
                    .setDomain(".qwen.ai").setPath("/"));
        }
        context.addCookies(cookieList);
    }

    /**
    * 解析Cookie
    *
    * @param cookieString Cookie字符串
    * @return 解析Cookie的结果
     */
    private static Map<String, String> parseCookies(String cookieString) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cookieString == null || cookieString.isBlank()) {
            return map;
        }
        for (String pair : cookieString.split(";")) {
            String trim = pair.trim();
            int idx = trim.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            map.put(trim.substring(0, idx).trim(), trim.substring(idx + 1).trim());
        }
        return map;
    }

    /**
    * extract提示符
    *
    * @param body 主体
    * @return extract提示符的结果
     */
    private static String extractPrompt(String body) {
        try {
            JsonObject obj = JsonObject.parse(body);
            Object msgs = obj.get("messages");
            if (msgs instanceof List<?> list && !list.isEmpty()) {
                Object last = list.get(list.size() - 1);
                if (last instanceof Map<?, ?> m) {
                    Object content = m.get("content");
                    if (content instanceof String s) {
                        return s;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}