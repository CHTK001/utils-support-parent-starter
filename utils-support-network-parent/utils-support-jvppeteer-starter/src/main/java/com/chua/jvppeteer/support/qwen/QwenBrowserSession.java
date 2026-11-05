// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.jvppeteer.support.qwen;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.jvppeteer.support.JvppeteerSupport;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.ElementHandle;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.api.core.Response;
import com.ruiyun.jvppeteer.cdp.entities.CookieParam;
import com.ruiyun.jvppeteer.cdp.entities.CookieSameSite;
import com.ruiyun.jvppeteer.cdp.entities.Viewport;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 通义千问浏览器会话（Jvppeteer 实现）。
 *
 * <p>与 {@code QwenBrowserSession} 对等：注入 Cookie 后保持持久页面，
 * 在同一会话中连续对话，让通义千问云端自动维护上下文历史。</p>
 *
 * <p>关键差异：Playwright 的 {@code waitForResponse(predicate, options, action)}
 * 在 jvppeteer 中没有等价重载，这里改为「后台线程等待响应 + 主线程回车发送」，
 * 语义一致。</p>
 *
 * @author CH
 * @since 2026/08/12
 */
@Slf4j
public class QwenBrowserSession implements AutoCloseable {

    /**
     * 页面加载超时（毫秒）
     */
    private static final long PAGE_LOAD_TIMEOUT_MS = 60000;

    /**
     * 响应超时（毫秒）
     */
    private static final long RESPONSE_TIMEOUT_MS = 120000;

    /**
     * 隐身参数
     */
    private static final String[] STEALTH_ARGS = {
            "--disable-blink-features=AutomationControlled", "--no-sandbox",
            "--disable-web-security", "--disable-features=IsolateOrigins,site-per-process",
            "--disable-site-isolation-trials", "--disable-setuid-sandbox",
            "--no-first-run", "--no-default-browser-check", "--disable-extensions"
    };

    /**
     * 默认 UA
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /**
     * 千问首页
     */
    private static final String HOME_URL = "https://chat.qwen.ai";

    /**
     * 浏览器实例
     */
    private final Browser browser;

    /**
     * Cookie 键值对
     */
    private final Map<String, String> cookies;

    /**
     * 页面实例
     */
    private Page page;

    /**
     * 页面是否就绪
     */
    private boolean pageReady;

    /**
     * 创建通义千问浏览器会话
     *
     * @param cookieString Cookie 字符串，形如 {@code token=xxx; ssxmod_itna=xxx}
     * @param userDataDir  用户数据目录（jvppeteer 使用临时目录，此参数保留兼容）
     */
    public QwenBrowserSession(String cookieString, String userDataDir) {
        this.cookies = parseCookies(cookieString);
        this.browser = JvppeteerSupport.launch(true, null, List.of(STEALTH_ARGS), null);
// [P3C C1 豁免] 关闭钩子必须由 JVM 创建独立线程
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    /**
     * 初始化页面并加载通义千问
     */
    public void init() {
        page = browser.newPage();
        configure(page);
        injectCookies(page);
        navigate(page, HOME_URL);
        sleep(10000L);
        pageReady = true;
        log.info("通义千问页面已就绪");
    }

    /**
     * 开始新会话：回到千问首页
     */
    public void newChat() {
        if (page == null) {
            return;
        }
        try {
            navigate(page, HOME_URL);
            sleep(5000L);
        } catch (Exception e) {
            log.warn("新建会话失败: {}", e.getMessage());
        }
    }

    /**
     * 发送聊天消息并等待回答
     *
     * @param body     JSON 请求体字符串
     * @param model    模型名称（页面内由站点决定，保留参数）
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
            ElementHandle textarea = page.$("textarea.message-input-textarea");
            if (textarea == null) {
                return QwenChatResult.error("未找到聊天输入框");
            }
            textarea.click();
            page.keyboard().type(prompt);
            sleep(300L);

            CompletableFuture<Response> future = CompletableFuture.supplyAsync(
                    () -> page.waitForResponse(r -> r.url().contains("/api/v2/chat/completions")));
            page.keyboard().press("Enter");
            Response response = future.get(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            String sse = new String(response.content(), StandardCharsets.UTF_8);
            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            List<Map<String, Object>> rawEvents = new ArrayList<>();
            for (String line : sse.split("\n")) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = com.chua.common.support.lang.json.Json.fromJson(data, Map.class);
                    if (obj == null) {
                        continue;
                    }
                    rawEvents.add(obj);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) obj.get("choices");
                    if (choices == null || choices.isEmpty()) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> delta = (Map<String, Object>) choices.getFirst().get("delta");
                    if (delta == null) {
                        continue;
                    }
                    String phase = (String) delta.get("phase");
                    String content = (String) delta.get("content");
                    if (content == null || content.isEmpty()) {
                        continue;
                    }
                    boolean isThinking = "think".equals(phase) || "thinking".equals(phase);
                    if (isThinking) {
                        thinking.append(content);
                    } else {
                        text.append(content);
                    }
                    if (listener != null) {
                        listener.accept(isThinking ? "thinking" : "content", content);
                    }
                } catch (Exception e) {
                    log.warn("解析千问 SSE 事件失败: {}", e.getMessage());
                }
            }
            return QwenChatResult.ok(text.toString(), thinking.toString(), "", rawEvents);
        } catch (Exception e) {
            log.warn("通义千问聊天请求失败: {}", e.getMessage());
            return QwenChatResult.error(e.getMessage());
        }
    }

    /**
     * 关闭会话
     */
    @Override
    public void close() {
        closePage(page);
        page = null;
        JvppeteerSupport.closeQuietly(browser);
    }

    /**
     * 配置页面：视口、UA、超时
     *
     * @param target 页面
     */
    private void configure(Page target) {
        Viewport viewport = new Viewport();
        viewport.setWidth(1280);
        viewport.setHeight(720);
        try {
            target.setViewport(viewport);
        } catch (Exception e) {
            log.warn("设置视口失败: {}", e.getMessage());
        }
        target.setUserAgent(USER_AGENT);
        target.setDefaultNavigationTimeout((int) PAGE_LOAD_TIMEOUT_MS);
        target.setDefaultTimeout((int) RESPONSE_TIMEOUT_MS);
    }

    /**
     * 注入 Cookie
     *
     * @param target 页面
     */
    private void injectCookies(Page target) {
        if (cookies.isEmpty()) {
            return;
        }
        List<CookieParam> params = new ArrayList<>(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            CookieParam param = new CookieParam();
            param.setName(entry.getKey());
            param.setValue(entry.getValue());
            param.setUrl(HOME_URL + "/");
            param.setSameSite(CookieSameSite.Lax);
            params.add(param);
        }
        target.setCookie(params.toArray(new CookieParam[0]));
    }

    /**
     * 解析 Cookie
     *
     * @param cookieString Cookie 字符串
     * @return 键值对
     */
    private static Map<String, String> parseCookies(String cookieString) {
        Map<String, String> map = Wraps.linkedMap();
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
     * 从请求体提取最后一条用户消息
     *
     * @param body 请求体
     * @return 用户输入，解析失败返回 null
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
        } catch (Exception e) {
            log.warn("提取千问响应内容失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 导航到目标地址，失败仅记录日志
     *
     * @param target 页面
     * @param url    地址
     */
    private static void navigate(Page target, String url) {
        try {
            target.goTo(url);
        } catch (Exception e) {
            log.warn("页面加载失败: {} - {}", url, e.getMessage());
        }
    }

    /**
     * 关闭页面
     *
     * @param target 页面
     */
    private static void closePage(Page target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (Exception e) {
            log.debug("关闭千问页面失败: {}", e.getMessage());
        }
    }

    /**
     * 休眠
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}