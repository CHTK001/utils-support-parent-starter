package com.chua.jvppeteer.support.doubao;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.jvppeteer.support.JvppeteerSupport;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.entities.CookieParam;
import com.ruiyun.jvppeteer.cdp.entities.CookieSameSite;
import com.ruiyun.jvppeteer.cdp.entities.Viewport;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 豆包逆向浏览器会话（Jvppeteer 实现）。
 *
 * <p>与 {@code DoubaoBrowserSession} 对等：启动 Chromium，把 Cookie 注入
 * {@code .doubao.com} / {@code .bytedance.com} 域，加载豆包首页触发站点
 * fetch hook 注入 {@code a_bogus} / {@code msToken} 签名，然后在页面内用
 * 原生 {@code fetch} 发起聊天请求，从 SSE 流中抽取回答与思考链。</p>
 *
 * <p>Java 端无需复刻 {@code a_bogus} 算法，签名由站点前端 JS 自动完成。</p>
 *
 * @author CH
 * @since 2026/08/11
 */
@Slf4j
public class DoubaoBrowserSession implements AutoCloseable {

    /**
     * 浏览器启动超时
     */
    private static final Duration LAUNCH_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 页面加载超时
     */
    private static final Duration NAV_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 默认浏览器数据目录（jvppeteer 使用临时用户目录，此常量保留兼容）
     */
    private static final String DEFAULT_USER_DATA_DIR = "./.doubao_browser_data";

    /**
     * 默认 UA
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /**
     * 反检测启动参数
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
     * 聊天请求脚本（页面内执行，自动触发签名 hook）
     */
    private static final String CHAT_SCRIPT = """
            async (args) => {
                const extractMsg = (obj) => { try { return obj.content?.text || obj.text || JSON.stringify(obj); } catch(e) { return String(obj); } };
                const csrfToken = document.cookie.split('; ').find(c => c.startsWith('passport_csrf_token='))?.split('=')[1] || '';
                const headers = { 'Content-Type': 'application/json' };
                if (csrfToken) { headers['a-csrf-token'] = csrfToken; }
                const resp = await fetch(args.url, {
                    method: 'POST',
                    headers: headers,
                    body: args.body,
                    credentials: 'include'
                });
                if (!resp.ok) {
                    return { done: true, error: 'HTTP ' + resp.status + ' ' + resp.statusText, rawEvents: [] };
                }
                const reader = resp.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                let text = '';
                let thinking = '';
                let conversationId = '';
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
                        let obj;
                        try { obj = JSON.parse(line); } catch (e) { continue; }
                        rawEvents.push(obj);
                        const evt = obj.event_type || obj.type;
                        if (evt === 2005) {
                            error = obj.event_data || obj.error_msg || extractMsg(obj);
                            break;
                        }
                        if (evt === 2003) {
                            finished = true;
                            break;
                        }
                        if (evt === 2001 || evt === undefined) {
                            let data = obj.event_data;
                            if (typeof data === 'string') { try { data = JSON.parse(data); } catch(e) {} }
                            const msg = data?.message || obj;
                            const ct = msg.content_type || obj.content_type || obj.content?.content_type;
                            if (ct === 10040) { continue; }
                            if (ct === 2010 || ct === 2021) { continue; }
                            let contentRaw = msg.content || obj.content;
                            if (typeof contentRaw === 'string') { try { contentRaw = JSON.parse(contentRaw); } catch(e) {} }
                            if (!contentRaw || contentRaw === '{}') { 
                                if (msg?.is_finish) { finished = true; }
                                continue; 
                            }
                            const t = extractMsg(contentRaw);
                            if (!t || t === '{}') { 
                                if (msg?.is_finish) { finished = true; }
                                continue; 
                            }
                            if (ct === 2008) {
                                thinking += t;
                                push('thinking', t);
                            }
                            else if (ct === 10000) { thinking += t; push('thinking', t); }
                            else if (ct === 103) { 
                                if (contentRaw.think) {
                                    thinking += contentRaw.think;
                                    push('thinking', contentRaw.think);
                                }
                            }
                            else if (ct === 2002) { /* suggestions, skip */ }
                            else { text += t; push('text', t); }
                            if (msg?.conversation_id && msg.conversation_id !== '0') { conversationId = msg.conversation_id; }
                            if (msg?.is_finish || msg?.is_finish === true) { finished = true; }
                        }
                    }
                    if (finished || error) { break; }
                }
                return { done: true, text: text, thinking: thinking, conversationId: conversationId, error: error, events: events, rawEvents: rawEvents };
            }
            """;

    /**
     * 浏览器实例
     */
    private final Browser browser;

    /**
     * 用户数据目录
     */
    private final String userDataDir;

    /**
     * 会话 Cookie
     */
    private final Map<String, String> cookies;

    /**
     * 常驻页面（加载首页、维持签名 hook）
     */
    private Page page;

    /**
     * 构造豆包浏览器会话
     *
     * @param cookieString Cookie 串，形如 {@code sessionid=...; ttwid=...; passport_csrf_token=...}
     * @param userDataDir  浏览器用户数据目录，可空
     */
    public DoubaoBrowserSession(String cookieString, String userDataDir) {
        this.cookies = parseCookies(cookieString);
        this.userDataDir = userDataDir == null || userDataDir.isEmpty() ? DEFAULT_USER_DATA_DIR : userDataDir;
        this.browser = JvppeteerSupport.launch(true, null, List.of(STEALTH_ARGS), null);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    /**
     * 启动会话：注入 Cookie 并加载豆包首页以触发签名 hook
     */
    public void init() {
        page = browser.newPage();
        configure(page);
        injectCookies(page);
        navigate(page, DoubaoConstants.HOME_URL);
        sleep(3000L);
        log.info("豆包首页加载完成，签名 hook 已就绪");
    }

    /**
     * 发送一次聊天请求并返回解析结果
     *
     * @param url            聊天端点完整 URL
     * @param body           JSON 请求体字符串
     * @param conversationId 会话标识，可为空表示新会话
     * @param listener       流式事件监听器，可为空
     * @return 解析后的聊天结果
     */
    public DoubaoChatResult chat(String url, String body, String conversationId,
                                 BiConsumer<String, String> listener) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("url", url);
        args.put("body", body);
        args.put("conversation_id", conversationId == null ? "" : conversationId);

        Page chatPage = null;
        try {
            chatPage = browser.newPage();
            configure(chatPage);
            navigate(chatPage, DoubaoConstants.HOME_URL);
            Object result = chatPage.evaluate(CHAT_SCRIPT, args);
            if (result instanceof Map<?, ?> raw) {
                String text = getString(raw, "text");
                String thinking = getString(raw, "thinking");
                String cid = getString(raw, "conversationId");
                String error = getString(raw, "error");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawEvents = (List<Map<String, Object>>) raw.get("rawEvents");
                if (listener != null) {
                    Object events = raw.get("events");
                    List<?> source = (events instanceof List<?> list && !list.isEmpty()) ? list : rawEvents;
                    if (source != null) {
                        for (Object o : source) {
                            if (o instanceof Map<?, ?> m && m.get("type") != null) {
                                listener.accept(String.valueOf(m.get("type")), String.valueOf(m.get("content")));
                            }
                        }
                    }
                }
                if (error != null && !error.isEmpty()) {
                    return DoubaoChatResult.error(error);
                }
                return DoubaoChatResult.ok(text == null ? "" : text,
                        thinking == null ? "" : thinking,
                        cid == null ? "" : cid,
                        rawEvents);
            }
            return DoubaoChatResult.error("浏览器脚本未返回有效结果");
        } catch (Exception e) {
            log.warn("豆包聊天请求失败: {}", e.getMessage());
            return DoubaoChatResult.error(e.getMessage());
        } finally {
            closePage(chatPage);
        }
    }

    /**
     * 删除指定会话（清理豆包侧边栏）
     *
     * @param conversationId 会话标识
     * @return true 表示删除成功
     */
    public boolean deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isEmpty() || "0".equals(conversationId)) {
            return false;
        }
        Page target = null;
        try {
            target = browser.newPage();
            configure(target);
            navigate(target, DoubaoConstants.HOME_URL);
            String csrf = cookies.getOrDefault("passport_csrf_token", "");
            String script = "async (id) => {"
                    + " try { const r = await fetch('https://www.doubao.com/samantha/thread/delete', {"
                    + "   method: 'POST', headers: { 'Content-Type': 'application/json', 'a-csrf-token': '" + csrf + "' },"
                    + "   credentials: 'include', body: JSON.stringify({ thread_id: id }) });"
                    + " const d = await r.json(); return r.ok && d.code === 0; }"
                    + " catch(e) { return false; } }";
            Object result = target.evaluate(script, conversationId);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            log.warn("删除会话失败: {}", e.getMessage());
            return false;
        } finally {
            closePage(target);
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
        target.setDefaultNavigationTimeout((int) NAV_TIMEOUT.toMillis());
        target.setDefaultTimeout((int) NAV_TIMEOUT.toMillis());
    }

    /**
     * 注入认证 Cookie 到豆包主域与字节跳动域
     *
     * @param target 页面
     */
    private void injectCookies(Page target) {
        if (cookies.isEmpty()) {
            return;
        }
        List<CookieParam> params = new ArrayList<>(cookies.size() * 2);
        String[] domains = {DoubaoConstants.DOUBAO_DOMAIN, DoubaoConstants.BYTEDANCE_DOMAIN};
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            for (String domain : domains) {
                CookieParam param = new CookieParam();
                param.setName(entry.getKey());
                param.setValue(entry.getValue());
                param.setDomain(domain);
                param.setPath("/");
                param.setSameSite(CookieSameSite.Lax);
                params.add(param);
            }
        }
        target.setCookie(params.toArray(new CookieParam[0]));
    }

    /**
     * 解析 Cookie 串
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
     * 从映射中安全获取字符串
     *
     * @param map 映射
     * @param key 键
     * @return 字符串值
     */
    private static String getString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : val != null ? String.valueOf(val) : null;
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
            log.debug("关闭豆包页面失败: {}", e.getMessage());
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
