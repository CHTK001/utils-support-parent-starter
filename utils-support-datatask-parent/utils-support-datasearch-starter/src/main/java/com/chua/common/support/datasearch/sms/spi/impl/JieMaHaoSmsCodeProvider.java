package com.chua.common.support.datasearch.sms.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.sms.model.SmsMessageInfo;
import com.chua.common.support.datasearch.sms.model.SmsPhoneInfo;
import com.chua.common.support.datasearch.sms.model.SmsPhoneMessage;
import com.chua.common.support.datasearch.sms.spi.SmsCodeProvider;
import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledDirectorySupport;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.lang.directory.executor.VirtualThreadPollerExecutor;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「接码号」平台虚拟号码接码实现。
 *
 * <p>数据源：<a href="https://jiemahao.com/phone-numbers/">接码号 免费在线短信接收，虚拟电话号码</a>。</p>
 *
 * <h2>功能</h2>
 * <ul>
 *   <li>{@link #listPhones()} — 获取全部可用虚拟号码（playwright 打开列表页解析）</li>
 *   <li>{@link #randomPhone()} — 随机获取一个可用虚拟号码</li>
 *   <li>{@link #fetchMessages(SmsPhoneInfo)} — 基于 playwright 打开短信详情页、等待 Cloudflare Turnstile
 *       验证通过并点击「查看短信」，提取该号码收到的真实短信（含验证码）。</li>
 *   <li>集成 {@link PolledDirectory} — 整个监听链路由 playwright 驱动：{@link #upgrade()} 用共享浏览器会话
 *       打开号码列表页对比快照，号码变更（{@link WatcherEvent#MODIFY}）时对变化的号码打开短信页抓取短信，
 *       事件 {@code source} 为 {@link SmsPhoneMessage}（号码 + 短信列表），便于监听器直接核对短信内容。</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * SmsCodeProvider provider = ServiceProvider.of(SmsCodeProvider.class).getExtension("jiemahao");
 *
 * // 1. 获取全部号码
 * List<SmsPhoneInfo> all = provider.listPhones();
 *
 * // 2. 随机获取一个号码
 * SmsPhoneInfo one = provider.randomPhone();
 *
 * // 3. 手动获取该号码收到的短信（playwright 打开短信页，自动通过 Turnstile 后点「查看短信」）
 * List<SmsMessageInfo> messages = provider.fetchMessages(one);
 *
 * // 4. PolledDirectory 回调监听（playwright 驱动，号码变更时事件携带号码 + 短信）
 * PolledDirectory directory = (PolledDirectory) provider;
 * directory.addListener(new PolledListener() {
 *     public void onModify(WatcherEvent event, EventObserver observer) {
 *         if (observer.getSource() instanceof SmsPhoneMessage pm) {
 *             System.out.println(pm.getPhone().getPhone() + " -> " + pm.getMessages());
 *         }
 *     }
 * });
 * DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(
 *     Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE), 30, TimeUnit.SECONDS);
 * directory.start(env, null);
 * }</pre>
 *
 * <p>短信详情页受 Cloudflare Turnstile 人机验证保护，{@link #fetchMessages(SmsPhoneInfo)} 使用
 * playwright 真实浏览器自动完成验证（managed 模式通常会自动通过；交互模式则定位并点击复选框），
 * 验证通过后点击「查看短信」提取短信列表并解析验证码。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jiemahao")
public class JieMaHaoSmsCodeProvider implements SmsCodeProvider, PolledDirectory {

    /**
     * 号码列表页地址
     */
    private static final String BASE_URL = "https://jiemahao.com/phone-numbers/";

    /**
     * 模拟浏览器的 User-Agent
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 号码条目标签选择器
     */
    private static final String ITEM_SELECTOR = "div.tagslist ul li";

    /**
     * 短信详情地址中的槽位 ID 提取模式（如 {@code ?phone=98}）
     */
    private static final Pattern PHONE_ID_PATTERN = Pattern.compile("phone=(\\d+)");

    /**
     * 国旗资源名提取模式（如 {@code region/us.svg}）
     */
    private static final Pattern REGION_PATTERN = Pattern.compile("region/([a-z]{2})\\.svg");

    /**
     * 事件监听器与运行状态的公共支撑件
     */
    private final PolledDirectorySupport support = new PolledDirectorySupport(getClass().getSimpleName());

    /**
     * 最近一次快照：号码槽位 ID -> 变更标识（用于轮询对比）
     */
    private final Map<String, Long> snapshot = new ConcurrentHashMap<>();

    /**
     * 号码 -> 最近一次短信抓取时间（节流，避免每次状态变化都启动浏览器）
     */
    private final Map<String, Long> lastFetchTime = new ConcurrentHashMap<>();

    /**
     * 共享 playwright 浏览器会话（PolledDirectory 监听链路使用）
     */
    private final JieMaHaoPlaywrightFetcher browserSession = new JieMaHaoPlaywrightFetcher();

    /**
     * 短信异步抓取执行器（避免 playwright 抓短信阻塞轮询）
     */
    private final java.util.concurrent.ExecutorService smsExecutor = ThreadUtils.newVirtualThreadPerTaskExecutor("SmsFetch");

    /**
     * 轮询计数，仅用于日志
     */
    private final AtomicLong pollCount = new AtomicLong();

    /**
     * 构造「接码号」虚拟号码接码提供者。
     */
    public JieMaHaoSmsCodeProvider() {
    }

    // ==================== SmsCodeProvider ====================

    @Override
    public String name() {
        return "jiemahao";
    }

    @Override
    public List<SmsPhoneInfo> listPhones() {
        return fetchSnapshot();
    }

    @Override
    public List<SmsMessageInfo> fetchMessages(SmsPhoneInfo phone) {
        return browserSession.fetchMessages(phone);
    }

    // ==================== PolledDirectory ====================

    @Override
    public boolean isDelegatedOperatingSystem() {
        return false;
    }

    @Override
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        if (!support.markRunning()) {
            log.warn("[jiemahao] 已处于轮询运行状态，忽略重复启动");
            return;
        }
        log.info("[jiemahao] 号码轮询启动: interval={} {}", environment.getPollingInterval(), environment.getTimeUnit());
        // 初始化基线快照，避免把历史号码误报为新增
        List<SmsPhoneInfo> current = fetchSnapshot();
        if (current != null && !current.isEmpty()) {
            for (SmsPhoneInfo info : current) {
                snapshot.putIfAbsent(info.getId(), changeToken(info));
            }
        }
        if (executor == null) {
            executor = new VirtualThreadPollerExecutor(this, environment);
        }
        executor.start();
    }

    @Override
    public void upgrade() {
        long round = pollCount.incrementAndGet();
        List<SmsPhoneInfo> current;
        try {
            current = fetchSnapshot();
        } catch (Exception e) {
            log.warn("[jiemahao] 第{}轮轮询抓取失败: {}", round, e.getMessage());
            support.fireError(BASE_URL, null, e);
            return;
        }
        if (current == null || current.isEmpty()) {
            return;
        }

        Map<String, SmsPhoneInfo> curMap = Wraps.map();
        for (SmsPhoneInfo info : current) {
            curMap.put(info.getId(), info);
            Long prev = snapshot.get(info.getId());
            Long token = changeToken(info);
            if (prev == null) {
                snapshot.put(info.getId(), token);
                // 新号码：立即分发事件（携带缓存短信）
                fire(WatcherEvent.CREATE, info, browserSession.cachedMessages(info.getId()));
                // 后台异步抓取该号码短信，抓到后补发带短信的事件
                asyncFetchSms(info);
            } else if (!token.equals(prev)) {
                snapshot.put(info.getId(), token);
                // 号码状态变更（更新时间文本变化 = 可能收到新短信）：立即分发事件（不阻塞），短信后台异步抓取
                fire(WatcherEvent.MODIFY, info, browserSession.cachedMessages(info.getId()));
                asyncFetchSms(info);
            }
        }

        // 快照中消失的号码视为下架
        for (String id : Wraps.list(snapshot.keySet())) {
            if (!curMap.containsKey(id)) {
                snapshot.remove(id);
                fire(WatcherEvent.DELETE, id);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[jiemahao] 第{}轮轮询完成，当前 {} 个号码", round, snapshot.size());
        }
    }

    @Override
    public void addListener(PolledListener listener) {
        support.addListener(listener);
    }

    @Override
    public boolean isRunning() {
        return support.isRunning();
    }

    @Override
    public void close() {
        support.markStopped();
        snapshot.clear();
        lastFetchTime.clear();
        support.clearListeners();
        ThreadUtils.shutdownNow(smsExecutor);
        browserSession.close();
        log.info("[jiemahao] 号码轮询已停止");
    }

    // ==================== 内部方法 ====================

    /**
     * 抓取号码列表（playwright 优先，失败回退 HTTP 直连）。
     *
     * @return 号码列表；抓取失败返回空列表
     */
    private List<SmsPhoneInfo> fetchSnapshot() {
        List<SmsPhoneInfo> fromBrowser = browserSession.listPhones(BASE_URL);
        if (fromBrowser != null && !fromBrowser.isEmpty()) {
            return fromBrowser;
        }
        // 回退：列表页无 Turnstile，HTTP 直连亦可解析
        try {
            String html = HttpClientFactory.of(BASE_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://jiemahao.com/")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .getBodyString();
            if (html == null || html.isBlank()) {
                log.warn("[jiemahao] 号码列表页响应为空");
                return Collections.emptyList();
            }
            return parsePhoneList(html);
        } catch (Exception e) {
            log.warn("[jiemahao] 抓取号码列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 后台异步抓取号码短信，抓到后补发带短信的 {@link WatcherEvent#MODIFY} 事件。
     *
     * <p>同一号码在 30 秒节流窗口内只抓取一次，避免轮询期反复启动抓取拖慢监听。</p>
     *
     * @param info 号码
     */
    private void asyncFetchSms(SmsPhoneInfo info) {
        long now = System.currentTimeMillis();
        long last = lastFetchTime.getOrDefault(info.getId(), 0L);
        if (now - last < 30_000) {
            return;
        }
        lastFetchTime.put(info.getId(), now);
        try {
            smsExecutor.submit(() -> {
                try {
                    List<SmsMessageInfo> messages = browserSession.fetchMessages(info, 12);
                    if (messages != null && !messages.isEmpty()) {
                        fire(WatcherEvent.MODIFY, info, messages);
                    }
                } catch (Throwable e) {
                    log.warn("[jiemahao] 异步抓取短信失败: {} -> {}", info.getPhone(), String.valueOf(e.getMessage()));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 监听已关闭，忽略后续抓取任务
            log.debug("[jiemahao] 监听已关闭，跳过短信抓取: {}", info.getPhone());
        }
    }

    /**
     * 解析号码列表页 HTML（HTTP 回退路径）。
     *
     * @param html 页面 HTML
     * @return 号码列表
     */
    private List<SmsPhoneInfo> parsePhoneList(String html) {
        Document document = Jsoup.parse(html, BASE_URL);
        Elements items = document.select(ITEM_SELECTOR);
        List<SmsPhoneInfo> result = Wraps.list();
        for (Element item : items) {
            Element link = item.selectFirst("a.article-title[href]");
            if (link == null) {
                continue;
            }
            String phone = link.text().trim();
            String href = link.absUrl("href");
            if (phone.isEmpty() || href.isEmpty()) {
                continue;
            }
            Element flag = item.selectFirst("small img[alt]");
            String countryName = flag != null ? flag.attr("alt").trim() : "";
            String flagUrl = flag != null ? flag.absUrl("src") : "";
            Element time = item.selectFirst("p span.tit");
            String updateTime = time != null ? time.text().trim() : "";
            Element remarkEl = item.selectFirst("p small");
            String remark = remarkEl != null ? remarkEl.text().trim() : "";

            result.add(SmsPhoneInfo.builder()
                    .id(extractPhoneId(href, phone))
                    .phone(phone)
                    .countryName(countryName)
                    .countryCode(extractCountryCode(flagUrl))
                    .flagUrl(flagUrl)
                    .updateTime(updateTime)
                    .remark(remark)
                    .smsUrl(href)
                    .build());
        }
        if (log.isDebugEnabled()) {
            log.debug("[jiemahao] 号码列表解析完成: {} 条", result.size());
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
     * 计算号码的变更标识。
     *
     * <p>站点仅提供相对时间文本（如 {@code 2 小时前}），无法解析为绝对时间；
     * 以 {@code 更新时间|备注} 的哈希作为变更标识，文本变化即视为号码状态变更。</p>
     *
     * @param info 号码
     * @return 变更标识
     */
    private static long changeToken(SmsPhoneInfo info) {
        String token = (info == null ? "" : info.getUpdateTime()) + "|"
                + (info == null ? "" : info.getRemark());
        return token.hashCode();
    }

    /**
     * 向全部监听器分发号码事件（携带已抓取的短信）。
     *
     * <p>事件的 {@code source} 为 {@link SmsPhoneMessage}（号码 + 短信列表），
     * 便于监听器在回调中直接核对号码与其收到的短信。</p>
     *
     * @param event    事件类型
     * @param info     号码
     * @param messages 该号码已抓取的短信列表
     */
    private void fire(WatcherEvent event, SmsPhoneInfo info, List<SmsMessageInfo> messages) {
        SmsPhoneMessage payload = SmsPhoneMessage.builder()
                .phone(info)
                .messages(messages)
                .build();
        EventObserver observer = EventObserver.builder()
                .currentPath(BASE_URL)
                .triggerFile(info.getId())
                .source(payload)
                .eventType(event)
                .build();
        support.fire(observer);
    }

    /**
     * 向全部监听器分发号码下架事件。
     *
     * @param event 事件类型（固定 {@link WatcherEvent#DELETE}）
     * @param id    已下架号码的槽位 ID
     */
    private void fire(WatcherEvent event, String id) {
        EventObserver observer = EventObserver.builder()
                .currentPath(BASE_URL)
                .triggerFile(id)
                .eventType(event)
                .build();
        support.fire(observer);
    }
}