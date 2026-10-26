package com.chua.common.support.datasearch.sms.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.sms.model.SmsMessageInfo;
import com.chua.common.support.datasearch.sms.model.SmsPhoneInfo;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 playwright 的「接码号」浏览器会话。
 *
 * <p>短信详情页受 Cloudflare Turnstile 人机验证保护，普通 HTTP 客户端无法拿到短信原文。
 * 本类持有共享的 playwright + 浏览器实例（懒启动、会话级复用），完整模拟浏览器操作：</p>
 * <ol>
 *   <li>{@link #listPhones()} — 用 playwright 打开号码列表页并提取可用虚拟号码；</li>
 *   <li>{@link #fetchMessages(SmsPhoneInfo, int)} — 用 playwright 打开 {@code sms/?phone=XX}，
 *       等待 Turnstile 验证通过（managed 自动通过；交互模式点击复选框），点击「查看短信」，
 *       提取短信列表（{@code .direct-chat-msg}）并解析验证码。</li>
 * </ol>
 *
 * <p>该会话被 {@link JieMaHaoSmsCodeProvider} 的 {@code PolledDirectory} 监听链路复用：
 * 轮询时用同一浏览器打开页面，避免每次启动浏览器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class JieMaHaoPlaywrightFetcher implements AutoCloseable {

    /**
     * Turnstile 响应隐藏 input（验证通过后写入 token）
     */
    private static final String TURNSTILE_TOKEN_SELECTOR = "input[name=cf-turnstile-response]";

    /**
     * 「查看短信」提交按钮选择器
     */
    private static final String SUBMIT_SELECTOR = "button.sms-submit, #submit, button[name=submit]";

    /**
     * 短信条目选择器（站点模板的 direct-chat 结构）
     */
    private static final String SMS_MSG_SELECTOR = ".direct-chat-msg";

    /**
     * 短信详情地址中的槽位 ID 提取模式（如 {@code ?phone=98}）
     */
    private static final Pattern PHONE_ID_PATTERN = Pattern.compile("phone=(\\d+)");

    /**
     * 国旗资源名提取模式（如 {@code region/us.svg}）
     */
    private static final Pattern REGION_PATTERN = Pattern.compile("region/([a-z]{2})\\.svg");

    /**
     * 中文验证码模式：验证码：[4-8位数字]
     */
    private static final Pattern CN_CODE_PATTERN = Pattern.compile("验证码[：:\\s]*([0-9]{4,8})", Pattern.CASE_INSENSITIVE);

    /**
     * 英文验证码模式：code/code is/verification code + 数字
     */
    private static final Pattern EN_CODE_PATTERN = Pattern.compile("(?:code|otp)[^0-9]{0,12}([0-9]{4,8})", Pattern.CASE_INSENSITIVE);

    /**
     * 纯数字验证码模式（正文尾部独立数字段）
     */
    private static final Pattern NUM_CODE_PATTERN = Pattern.compile("\\b([0-9]{4,8})\\b");

    /**
     * 等待 Turnstile token 的最大秒数（手动调用）
     */
    private static final int TOKEN_WAIT_SECONDS = 30;

    /**
     * 等待短信列表的最大秒数
     */
    private static final int SMS_WAIT_SECONDS = 18;

    /**
     * playwright 实例（懒启动）
     */
    private volatile Playwright playwright;

    /**
     * 浏览器实例（懒启动，会话级复用）
     */
    private volatile Browser browser;

    /**
     * 已抓取短信缓存：槽位 ID -> 短信列表（监听事件复用）
     */
    private final Map<String, List<SmsMessageInfo>> messageCache = new ConcurrentHashMap<>();

    /**
     * 会话是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 构造浏览器会话。
     */
    public JieMaHaoPlaywrightFetcher() {
    }

    /**
     * 获取共享浏览器实例（懒启动）。
     *
     * @return 浏览器实例；会话已关闭返回 null
     */
    synchronized Browser browser() {
        if (closed) {
            return null;
        }
        if (browser == null) {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(defaultLaunchOptions());
            log.info("[jiemahao] playwright 浏览器会话已启动");
        }
        return browser;
    }

    /**
     * 抓取号码列表（playwright 打开列表页）。
     *
     * @param listUrl 号码列表页地址
     * @return 号码列表；失败返回空列表
     */
    public synchronized List<SmsPhoneInfo> listPhones(String listUrl) {
        if (listUrl == null || listUrl.isBlank()) {
            return Collections.emptyList();
        }
        try (BrowserContext context = newContext()) {
            Page page = context.newPage();
            page.navigate(listUrl, new Page.NavigateOptions().setTimeout(45000)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return extractPhoneList(page, listUrl);
        } catch (Throwable e) {
            log.warn("[jiemahao] playwright 抓取号码列表失败: {}", String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * 抓取指定号码收到的短信（完整等待，适合手动调用）。
     *
     * @param phone 虚拟号码
     * @return 短信列表；Turnstile 未通过 / 页面无短信 / 浏览器不可用 时返回空列表
     */
    public List<SmsMessageInfo> fetchMessages(SmsPhoneInfo phone) {
        int wait = configuredTokenWaitSeconds();
        return fetchMessages(phone, wait);
    }

    /**
     * 读取 Turnstile 等待秒数（系统属性 {@code jiemahao.turnstile.wait}，默认 30）。
     *
     * @return 等待秒数
     */
    private static int configuredTokenWaitSeconds() {
        try {
            String v = System.getProperty("jiemahao.turnstile.wait", "");
            return v.isBlank() ? TOKEN_WAIT_SECONDS : Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return TOKEN_WAIT_SECONDS;
        }
    }

    /**
     * 抓取指定号码收到的短信。
     *
     * @param phone            虚拟号码
     * @param tokenWaitSeconds 等待 Turnstile token 的最大秒数（监听场景传入较小值以免阻塞轮询）
     * @return 短信列表；Turnstile 未通过 / 页面无短信 / 浏览器不可用 时返回空列表
     */
    public synchronized List<SmsMessageInfo> fetchMessages(SmsPhoneInfo phone, int tokenWaitSeconds) {
        if (phone == null || phone.getSmsUrl() == null || phone.getSmsUrl().isBlank()) {
            return Collections.emptyList();
        }
        try (BrowserContext context = newContext()) {
            Page page = context.newPage();
            page.navigate(phone.getSmsUrl(), new Page.NavigateOptions().setTimeout(45000)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));

            // 1. 等待 Turnstile 自动验证通过（managed 模式自动写入 token）
            boolean verified = waitForTurnstile(page, tokenWaitSeconds);
            if (!verified) {
                // 2. 交互模式：尝试在 Turnstile 内链 iframe 中点击复选框
                verified = tryClickTurnstileCheckbox(page) && waitForTurnstile(page, Math.min(tokenWaitSeconds, 10));
            }
            if (!verified) {
                log.warn("[jiemahao] Turnstile 人机验证未通过（数据中心 IP 或自动化环境可能被 Cloudflare 拦截）: {}",
                        phone.getPhone());
                return Collections.emptyList();
            }

            // 3. 点击「查看短信」
            try {
                page.locator(SUBMIT_SELECTOR).first()
                        .click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                log.info("[jiemahao] 已点击「查看短信」: {}", phone.getPhone());
            } catch (Exception e) {
                page.evaluate("() => { const f = document.querySelector('form.navbar-form'); "
                        + "if (f) f.submit(); }");
            }

            // 4. 等待短信列表渲染并提取
            List<SmsMessageInfo> messages = waitAndExtractSms(page, phone);
            messageCache.put(phone.getId(), messages);
            return messages;
        } catch (Throwable e) {
            log.warn("[jiemahao] playwright 抓取短信失败: {} -> {}", phone.getPhone(), String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * 获取指定号码最近一次抓取的短信缓存。
     *
     * @param phoneId 号码槽位 ID
     * @return 缓存短信列表；无缓存返回空列表
     */
    public List<SmsMessageInfo> cachedMessages(String phoneId) {
        List<SmsMessageInfo> cached = phoneId == null ? null : messageCache.get(phoneId);
        return cached == null ? Collections.emptyList() : cached;
    }

    /**
     * 构建默认浏览器启动参数。
     *
     * @return 启动参数
     */
    private static BrowserType.LaunchOptions defaultLaunchOptions() {
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(java.util.List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--disable-dev-shm-usage"));
        String exe = System.getProperty("jiemahao.browser.executable", "");
        if (!exe.isBlank() && !"null".equals(exe)) {
            launch.setExecutablePath(java.nio.file.Paths.get(exe));
        }
        return launch;
    }

    /**
     * 创建浏览器上下文。
     *
     * @return 上下文
     */
    private BrowserContext newContext() {
        Browser b = browser();
        if (b == null) {
            throw new IllegalStateException("playwright 浏览器会话已关闭");
        }
        return b.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 900)
                .setLocale("zh-CN")
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"));
    }

    /**
     * 从列表页提取号码。
     *
     * @param page    页面
     * @param baseUrl 列表页地址（作为相对链接解析基准）
     * @return 号码列表
     */
    private static List<SmsPhoneInfo> extractPhoneList(Page page, String baseUrl) {
        Object raw = page.evaluate(
                "() => Array.from(document.querySelectorAll('div.tagslist ul li')).map(li => { "
                        + "const link = li.querySelector('a.article-title[href]'); "
                        + "const flag = li.querySelector('small img[alt]'); "
                        + "const timeEl = li.querySelector('p span.tit'); "
                        + "const remarkEl = li.querySelector('p small'); "
                        + "return { phone: link ? link.innerText.trim() : '', href: link ? link.href : '', "
                        + "country: flag ? flag.getAttribute('alt') : '', flagUrl: flag ? flag.src : '', "
                        + "time: timeEl ? timeEl.innerText.trim() : '', remark: remarkEl ? remarkEl.innerText.trim() : '' }; })");
        if (!(raw instanceof java.util.List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<SmsPhoneInfo> result = Wraps.list();
        for (Object obj : list) {
            if (!(obj instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            String phone = str(map.get("phone"));
            String href = str(map.get("href"));
            if (phone.isEmpty() || href.isEmpty()) {
                continue;
            }
            String countryName = str(map.get("country"));
            String flagUrl = str(map.get("flagUrl"));
            result.add(SmsPhoneInfo.builder()
                    .id(extractPhoneId(href, phone))
                    .phone(phone)
                    .countryName(countryName)
                    .countryCode(extractCountryCode(flagUrl))
                    .flagUrl(flagUrl)
                    .updateTime(str(map.get("time")))
                    .remark(str(map.get("remark")))
                    .smsUrl(href)
                    .build());
        }
        return result;
    }

    /**
     * 从短信详情地址中提取号码槽位 ID。
     *
     * @param href  详情地址
     * @param phone 号码展示文本（兜底）
     * @return 槽位 ID
     */
    private static String extractPhoneId(String href, String phone) {
        if (href != null) {
            Matcher matcher = PHONE_ID_PATTERN.matcher(href);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return phone == null ? "" : phone;
    }

    /**
     * 从国旗资源地址中提取国家二字码（如 {@code us.svg -> US}）。
     *
     * @param flagUrl 国旗地址
     * @return 国家二字码，无法识别时为空
     */
    private static String extractCountryCode(String flagUrl) {
        if (flagUrl == null || flagUrl.isEmpty()) {
            return "";
        }
        Matcher matcher = REGION_PATTERN.matcher(flagUrl);
        return matcher.find() ? matcher.group(1).toUpperCase() : "";
    }

    /**
     * 轮询等待 Turnstile token 写入。
     *
     * @param page      页面
     * @param maxSeconds 最大等待秒数
     * @return token 就绪返回 true
     */
    private static boolean waitForTurnstile(Page page, int maxSeconds) {
        try {
            for (int i = 0; i < maxSeconds; i++) {
                Object token = page.evaluate("() => { const el = document.querySelector('"
                        + TURNSTILE_TOKEN_SELECTOR + "'); return el ? (el.value || '') : ''; }");
                if (token != null && token.toString().length() > 20) {
                    return true;
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * 在 Turnstile 内链 iframe 中尝试点击验证复选框。
     *
     * <p>Turnstile 交互模式下验证控件位于 {@code challenges.cloudflare.com} 的 iframe 内。</p>
     *
     * @param page 页面
     * @return 是否完成一次点击尝试
     */
    private static boolean tryClickTurnstileCheckbox(Page page) {
        for (Frame frame : page.frames()) {
            if (!frame.url().contains("challenges.cloudflare.com")) {
                continue;
            }
            for (String selector : new String[]{
                    "input[type=checkbox]",
                    "#challenge-stage input[type=checkbox]",
                    ".turnstile-checkbox",
                    "label[for]"}) {
                try {
                    if (frame.locator(selector).count() > 0) {
                        frame.locator(selector).first()
                                .click(new Locator.ClickOptions().setTimeout(3000));
                        log.info("[jiemahao] 已点击 Turnstile 复选框: {}", selector);
                        return true;
                    }
                } catch (Exception ignored) {
                    // 该选择器不存在或不可点，尝试下一个
                }
            }
        }
        return false;
    }

    /**
     * 等待短信列表出现并解析。
     *
     * @param page  页面
     * @param phone 虚拟号码
     * @return 短信列表
     */
    private static List<SmsMessageInfo> waitAndExtractSms(Page page, SmsPhoneInfo phone) {
        try {
            for (int i = 0; i < SMS_WAIT_SECONDS; i++) {
                Object count = page.evaluate("() => document.querySelectorAll('" + SMS_MSG_SELECTOR + "').length");
                int n = count instanceof Double d ? d.intValue()
                        : (count == null ? 0 : Integer.parseInt(count.toString()));
                if (n > 0) {
                    return extractSms(page, phone);
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Collections.emptyList();
    }

    /**
     * 解析页面上的短信条目。
     *
     * @param page  页面
     * @param phone 虚拟号码
     * @return 短信列表
     */
    private static List<SmsMessageInfo> extractSms(Page page, SmsPhoneInfo phone) {
        Object raw = page.evaluate(
                "() => Array.from(document.querySelectorAll('.direct-chat-msg')).map(el => { "
                        + "const info = el.querySelector('.direct-chat-info'); "
                        + "const text = el.querySelector('.direct-chat-text'); "
                        + "return { info: info ? info.innerText : '', text: text ? text.innerText : '' }; })");
        if (!(raw instanceof java.util.List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<SmsMessageInfo> result = Wraps.list();
        for (Object obj : list) {
            if (!(obj instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            String info = str(map.get("info"));
            String content = str(map.get("text")).trim();
            if (content.isEmpty()) {
                continue;
            }
            String from = "";
            String time = "";
            if (!info.isEmpty()) {
                int split = info.indexOf(' ');
                if (split > 0) {
                    from = info.substring(0, split).trim();
                    time = info.substring(split).trim();
                } else {
                    from = info.trim();
                }
            }
            result.add(SmsMessageInfo.builder()
                    .phone(phone.getPhone())
                    .from(from)
                    .time(time)
                    .content(content)
                    .verifyCode(extractVerifyCode(content))
                    .build());
        }
        return result;
    }

    /**
     * 从短信内容中提取验证码。
     *
     * @param content 短信内容
     * @return 验证码；未匹配到返回空
     */
    private static String extractVerifyCode(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        Matcher cn = CN_CODE_PATTERN.matcher(content);
        if (cn.find()) {
            return cn.group(1);
        }
        Matcher en = EN_CODE_PATTERN.matcher(content);
        if (en.find()) {
            return en.group(1);
        }
        Matcher num = NUM_CODE_PATTERN.matcher(content);
        return num.find() ? num.group(1) : "";
    }

    /**
     * 空安全取值。
     *
     * @param value 值
     * @return 字符串
     */
    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        messageCache.clear();
        Exception ex = null;
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception e) {
                ex = e;
            }
            browser = null;
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception e) {
                if (ex == null) {
                    ex = e;
                }
            }
            playwright = null;
        }
        if (ex != null) {
            log.warn("[jiemahao] 关闭 playwright 会话失败: {}", ex.getMessage());
        } else {
            log.info("[jiemahao] playwright 浏览器会话已关闭");
        }
    }
}