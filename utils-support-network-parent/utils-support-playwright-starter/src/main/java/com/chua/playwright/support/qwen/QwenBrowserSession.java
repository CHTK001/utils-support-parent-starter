package com.chua.playwright.support.qwen;

import com.chua.common.support.lang.json.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 通义千问浏览器会话。
 *
 * <p>基于 Playwright 启动 Chromium，将 Cookie 注入 {@code chat.qwen.ai} 域，
 * 加载通义千问首页以触发前端指纹 JS 自动管理 {@code ssxmod_itna},
 * {@code ssxmod_itna2} 反爬 Cookie，然后通过页面内 {@code fetch} 发起
 * 聊天请求，并从 SSE 流中抽取回答文本与思考链。
 *
 * <p>核心思路（参考 Qwen2API）：
 * 直接在页面上下文内用原生 {@code fetch} 调用聊天端点，阿里云前端 JS 会自动
 * 注入 {@code ssxmod_itna} 指纹，Java 端无需复刻 LZW 指纹算法。
 *
 * @author CH
 * @since 2026/08/12
 */
@Slf4j
public class QwenBrowserSession implements AutoCloseable {

    /**
     * Chromium 启动超时。
     */
    private static final long LAUNCH_TIMEOUT_MS = 60000;

    /**
     * 页面加载超时。
     */
    private static final long NAV_TIMEOUT_MS = 60000;

    /**
     * 默认浏览器数据目录。
     */
    private static final String DEFAULT_USER_DATA_DIR = "./.qwen_browser_data";

    /**
     * 反检测启动参数（降低无头浏览器指纹被识别概率）。
     */
    private static final String[] STEALTH_ARGS = {
            "--disable-blink-features=AutomationControlled",
            "--no-sandbox",
            "--disable-web-security",
            "--disable-features=IsolateOrigins,site-per-process",
            "--disable-site-isolation-trials",
            "--disable-setuid-sandbox",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions"
    };

    /**
     * 聊天请求 JS 脚本（页面内执行，自动触发指纹 JS 注入 ssxmod_itna）。
     *
     * <p>流程：
     * <ol>
     *   <li>从 Cookie 中提取 {@code token} JWT</li>
     *   <li>先创建新会话（{@code /api/v2/chats/new}）获取 chat_id</li>
     *   <li>再发送聊天消息到 {@code /api/v2/chat/completions?chat_id=xxx}</li>
     *   <li>解析 SSE 流，根据 {@code phase} 区分思考（think）与回答（answer）</li>
     * </ol>
     */
    private static final String CHAT_SCRIPT = """
            async (args) => {
                const extractContent = (obj) => { try { return obj.content?.text || obj.text || JSON.stringify(obj); } catch(e) { return String(obj); } };
                const baseUrl = 'https://chat.qwen.ai';
                const headers = {
                    'Content-Type': 'application/json',
                    'source': 'web',
                    'version': '0.2.81',
                    'x-request-id': self.crypto.randomUUID(),
                    'referer': baseUrl + '/',
                    'origin': baseUrl
                };
                // 1. 创建新会话获取 chat_id
                const newResp = await fetch(baseUrl + '/api/v2/chats/new', {
                    method: 'POST',
                    headers: headers,
                    credentials: 'include',
                    body: JSON.stringify({ chatId: '', models: [args.model || 'qwen-plus'], project_id: '', timestamp: Date.now(), chat_type: 't2t', chat_mode: 'normal' })
                });
                if (!newResp.ok) { return { error: '创建会话失败: ' + newResp.status, rawEvents: [] }; }
                const newData = await newResp.json();
                const chatId = newData?.data?.id || '';
                if (!chatId) { return { error: '未获取到 chat_id', rawEvents: [] }; }
                // 2. 发送聊天消息
                const body = JSON.parse(args.body);
                body.chat_id = chatId;
                body.chatId = chatId;
                const url = baseUrl + '/api/v2/chat/completions?chat_id=' + chatId;
                const resp = await fetch(url, {
                    method: 'POST',
                    headers: headers,
                    credentials: 'include',
                    body: JSON.stringify(body)
                });
                if (!resp.ok) {
                    return { error: 'HTTP ' + resp.status + ' ' + resp.statusText, rawEvents: [] };
                }
                // 3. 解析 SSE 流
                const reader = resp.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                let text = '';
                let thinking = '';
                let error = '';
                let finished = false;
                const events = [];
                const rawEvents = [];
                const push = (type, content) => { if (content && content !== '{}') { events.push({ type: type, content: content }); } };
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) { break; }
                    buffer += decoder.decode(value, { stream: true });
                    let idx;
                    while ((idx = buffer.indexOf('\\n')) >= 0) {
                        let line = buffer.slice(0, idx).trim();
                        buffer = buffer.slice(idx + 1);
                        if (!line) { continue; }
                        if (line.startsWith('data:')) { line = line.substring(5).trim(); }
                        if (line === '[DONE]') { finished = true; break; }
                        let obj;
                        try { obj = JSON.parse(line); } catch (e) { continue; }
                        rawEvents.push(obj);
                        // 解析 usage 行
                        if (obj.choices && obj.choices.length === 0 && obj.usage) {
                            if (obj.choices && obj.choices.length === 0) { continue; }
                        }
                        // 处理 choices
                        const choices = obj.choices || [];
                        if (choices.length === 0) { continue; }
                        const delta = choices[0].delta || {};
                        const phase = delta.phase || '';
                        const content = delta.content || '';
                        if (delta.extra && delta.extra.image_list) { continue; }
                        if (phase === 'think' || phase === 'thinking' || phase === 'thinking_summary') {
                            if (content) { thinking += content; push('thinking', content); }
                        } else if (phase === 'answer' || phase === 'final' || phase === 'final_answer' || phase === 'response') {
                            if (content) { text += content; push('text', content); }
                        } else if (!phase && content) {
                            text += content; push('text', content);
                        }
                        const fr = choices[0].finish_reason;
                        if (fr === 'stop' || fr === 'end_turn' || fr === 'tool_calls') { finished = true; }
                    }
                    if (finished || error) { break; }
                }
                return { done: true, text: text, thinking: thinking, chatId: chatId, error: error, events: events, rawEvents: rawEvents };
            }
            """;

    /**
     * Playwright 实例。
     */
    private final Playwright playwright;

    /**
     * 浏览器实例。
     */
    private final Browser browser;

    /**
     * 浏览器上下文。
     */
    private final BrowserContext context;

    /**
     * 用户数据目录。
     */
    private final String userDataDir;

    /**
     * 构造通义千问浏览器会话。
     *
     * @param cookieString Cookie 串，形如 "token=xxx; ssxmod_itna=xxx"
     * @param userDataDir  浏览器用户数据目录，可空使用默认目录
     */
    public QwenBrowserSession(String cookieString, String userDataDir) {
        this.userDataDir = userDataDir == null || userDataDir.isEmpty() ? DEFAULT_USER_DATA_DIR : userDataDir;
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of(STEALTH_ARGS))
                .setTimeout(LAUNCH_TIMEOUT_MS));
        this.context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 720)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"));
        this.context.setDefaultNavigationTimeout(NAV_TIMEOUT_MS);
        injectCookies(cookieString);
    }

    /**
     * 启动会话：加载通义千问首页以触发指纹 JS。
     */
    public void init() {
        try (var page = context.newPage()) {
            page.navigate(QwenConstants.HOME_URL);
            log.info("通义千问首页加载完成，指纹 JS 已就绪");
        }
    }

    /**
     * 发送一次聊天请求并返回解析结果。
     *
     * @param body   请求体 JSON 字符串（不含 chat_id/chatId）
     * @param model  模型名称（如 "qwen-plus", "qwen3-coder-plus"）
     * @param listener 流式事件监听器，可为空
     * @return 解析后的聊天结果
     */
    public QwenChatResult chat(String body, String model,
                               BiConsumer<String, String> listener) {
        JsonObject args = JsonObject.create()
                .fluent("body", body)
                .fluent("model", model == null ? "qwen-plus" : model);

        try (var page = context.newPage()) {
            page.navigate(QwenConstants.HOME_URL);
            Object result = page.evaluate(CHAT_SCRIPT, args);
            if (result instanceof Map<?, ?> raw) {
                String text = getString(raw, "text");
                String thinking = getString(raw, "thinking");
                String chatId = getString(raw, "chatId");
                String error = getString(raw, "error");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawEvents = (List<Map<String, Object>>) raw.get("rawEvents");
                if (rawEvents == null) rawEvents = List.of();
                if (listener != null) {
                    Object events = raw.get("events");
                    if (events instanceof List<?> list) {
                        for (Object o : list) {
                            if (o instanceof Map<?, ?> m) {
                                listener.accept(String.valueOf(m.get("type")), String.valueOf(m.get("content")));
                            }
                        }
                    }
                }
                if (error != null && !error.isEmpty()) {
                    return QwenChatResult.error(error);
                }
                return QwenChatResult.ok(text == null ? "" : text,
                        thinking == null ? "" : thinking,
                        chatId == null ? "" : chatId,
                        rawEvents);
            }
            return QwenChatResult.error("浏览器脚本未返回有效结果");
        } catch (Exception e) {
            log.warn("通义千问聊天请求失败: {}", e.getMessage());
            return QwenChatResult.error(e.getMessage());
        }
    }

    @Override
    public void close() {
        Exception ex = null;
        try {
            context.close();
        } catch (Exception e) {
            ex = e;
        }
        try {
            browser.close();
        } catch (Exception e) {
            if (ex == null) ex = e;
        }
        try {
            playwright.close();
        } catch (Exception e) {
            if (ex == null) ex = e;
        }
        if (ex != null) {
            log.warn("释放通义千问浏览器会话失败", ex);
        }
    }

    /**
     * 注入 Cookie 到 chat.qwen.ai 域。
     *
     * @param cookieString Cookie 字符串
     */
    private void injectCookies(String cookieString) {
        Map<String, String> cookies = parseCookies(cookieString);
        List<com.microsoft.playwright.options.Cookie> cookieList = new ArrayList<>(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            cookieList.add(new com.microsoft.playwright.options.Cookie(entry.getKey(), entry.getValue())
                    .setDomain(".chat.qwen.ai")
                    .setPath("/"));
        }
        context.addCookies(cookieList);
    }

    /**
     * 解析 Cookie 串。
     *
     * @param cookieString Cookie 字符串
     * @return 键值对映射
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
     * 从 map 中安全获取字符串值。
     */
    private static String getString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : val != null ? String.valueOf(val) : null;
    }
}