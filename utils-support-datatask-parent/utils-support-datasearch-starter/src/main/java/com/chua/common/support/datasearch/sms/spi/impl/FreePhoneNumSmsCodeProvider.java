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
 * 「FreePhoneNum」平台虚拟号码接码实现。
 *
 * <p>数据源：<a href="https://freephonenum.com/receive-sms">FreePhoneNum — Receive SMS Online</a>。
 * 与「接码号」（jiemahao.com）不同，该平台的号码列表与短信详情页均无 Cloudflare Turnstile
 * 人机验证，可经纯 HTTP 直连获取，适合在数据中心 / 受限网络环境下直接读取真实短信。</p>
 *
 * <h2>功能</h2>
 * <ul>
 *   <li>{@link #listPhones()} — HTTP 直连解析号码列表页，获取全部可用虚拟号码（国家 / 号码 / 短信地址）</li>
 *   <li>{@link #randomPhone()} — 随机获取一个可用虚拟号码</li>
 *   <li>{@link #fetchMessages(SmsPhoneInfo)} — HTTP 直连打开号码短信页，解析每条短信的发送方与内容，提取验证码</li>
 *   <li>集成 {@link PolledDirectory} — 轮询号码列表对比快照，号码变更（{@link WatcherEvent#MODIFY} / 新增 / 下架）
 *       立即分发事件；事件 {@code source} 为 {@link SmsPhoneMessage}（号码 + 真实短信列表），
 *       便于监听器直接核对短信内容。因列表页不含号码更新时间文本，另内置短信抽查：
 *       每轮随机抽样少量号码对比短信条数，收到新短信即以带真实短信的 {@link WatcherEvent#MODIFY} 事件分发。</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * SmsCodeProvider provider = ServiceProvider.of(SmsCodeProvider.class).getExtension("freephonenum");
 *
 * // 1. 获取全部号码
 * List<SmsPhoneInfo> all = provider.listPhones();
 *
 * // 2. 随机获取一个号码
 * SmsPhoneInfo one = provider.randomPhone();
 *
 * // 3. 手动获取该号码收到的真实短信（HTTP 直连，无需人机验证）
 * List<SmsMessageInfo> messages = provider.fetchMessages(one);
 *
 * // 4. PolledDirectory 回调监听（号码变更时事件携带号码 + 真实短信）
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
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("freephonenum")
public class FreePhoneNumSmsCodeProvider implements SmsCodeProvider, PolledDirectory {

    /**
     * 号码列表页地址
     */
    private static final String LIST_URL = "https://freephonenum.com/receive-sms";

    /**
     * 模拟浏览器的 User-Agent
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 号码条目标签选择器（列表页中的号码链接）
     */
    private static final String NUMBER_LINK_SELECTOR = "a[href*=\"/receive-sms/\"]";

    /**
     * 号码短信页中每条短信的容器
     */
    private static final String MESSAGE_SELECTOR = "div.msg";

    /**
     * 短信内容元素
     */
    private static final String MESSAGE_TEXT_SELECTOR = "div.js-msgtext";

    /**
     * 号码短信地址中的国家 / 号码模式（如 {@code /be/receive-sms/460210041}）
     */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("/receive-sms/(\\d+)");

    /**
     * 国家分组标题模式（如 {@code Belgium (40 numbers)}）
     */
    private static final Pattern COUNTRY_GROUP_PATTERN = Pattern.compile("receive-sms/([a-z]{2})/");

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
     * 事件监听器与运行状态的公共支撑件
     */
    private final PolledDirectorySupport support = new PolledDirectorySupport(getClass().getSimpleName());

    /**
     * 最近一次快照：号码 -> 变更标识（用于轮询对比）
     */
    private final Map<String, Long> snapshot = new ConcurrentHashMap<>();

    /**
     * 号码 -> 最近一次抓取到的短信条数（短信抽查对比，用于在号码列表不含时间信息时感知短信更新）
     */
    private final Map<String, Integer> lastMessageCount = new ConcurrentHashMap<>();

    /**
     * 随机抽查器（短信抽查选号）
     */
    private final java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();

    /**
     * 每轮短信抽查的号码数量上限
     */
    private static final int SMS_SPOT_CHECK_SIZE = 3;

    /**
     * 轮询计数，仅用于日志
     */
    private final AtomicLong pollCount = new AtomicLong();

    /**
     * 构造 FreePhoneNum 虚拟号码接码提供者。
     */
    public FreePhoneNumSmsCodeProvider() {
    }

    // ==================== SmsCodeProvider ====================

    @Override
    public String name() {
        return "freephonenum";
    }

    @Override
    public List<SmsPhoneInfo> listPhones() {
        return fetchPhones();
    }

    @Override
    public List<SmsMessageInfo> fetchMessages(SmsPhoneInfo phone) {
        if (phone == null) {
            return Collections.emptyList();
        }
        String url = phone.getSmsUrl();
        if (url == null || url.isBlank()) {
            url = buildNumberUrl(phone);
        }
        if (url == null || url.isBlank()) {
            log.warn("[freephonenum] 号码 {} 缺少短信地址", phone.getPhone());
            return Collections.emptyList();
        }
        try {
            String html = HttpClientFactory.of(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", LIST_URL)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .getBodyString();
            if (html == null || html.isBlank()) {
                log.warn("[freephonenum] 短信页响应为空: {}", url);
                return Collections.emptyList();
            }
            return parseMessages(html, phone);
        } catch (Exception e) {
            log.warn("[freephonenum] 抓取短信失败: {} -> {}", phone.getPhone(), String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    // ==================== PolledDirectory ====================

    @Override
    public boolean isDelegatedOperatingSystem() {
        return false;
    }

    @Override
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        if (!support.markRunning()) {
            log.warn("[freephonenum] 已处于轮询运行状态，忽略重复启动");
            return;
        }
        log.info("[freephonenum] 号码轮询启动: interval={} {}", environment.getPollingInterval(), environment.getTimeUnit());
        // 初始化基线快照，避免把历史号码误报为新增
        List<SmsPhoneInfo> current = fetchPhones();
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
            current = fetchPhones();
        } catch (Exception e) {
            log.warn("[freephonenum] 第{}轮轮询抓取失败: {}", round, e.getMessage());
            support.fireError(LIST_URL, null, e);
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
                // 新号码：立即分发事件（短信同步抓取，来源无验证码，直连即可）
                fire(WatcherEvent.CREATE, info, fetchMessages(info));
            } else if (!token.equals(prev)) {
                snapshot.put(info.getId(), token);
                // 号码状态变更（可能收到新短信）：立即分发事件（带真实短信）
                fire(WatcherEvent.MODIFY, info, fetchMessages(info));
            }
        }

        // 快照中消失的号码视为下架
        for (String id : Wraps.list(snapshot.keySet())) {
            if (!curMap.containsKey(id)) {
                snapshot.remove(id);
                lastMessageCount.remove(id);
                fire(WatcherEvent.DELETE, id);
            }
        }

        // 短信抽查：列表页不含更新时间文本，号码保持在线不会触发变更；
        // 每轮随机抽样少量号码对比短信条数，条数变化视为收到新短信，分发带真实短信的 MODIFY 事件。
        spotCheckMessages(curMap);

        if (log.isDebugEnabled()) {
            log.debug("[freephonenum] 第{}轮轮询完成，当前 {} 个号码", round, snapshot.size());
        }
    }

    /**
     * 短信抽查：随机抽样少量号码抓取短信，与上次条数对比，变化则分发携带真实短信的 MODIFY 事件。
     *
     * @param phones 当前号码列表（号码 ID -> 号码信息）
     */
    private void spotCheckMessages(Map<String, SmsPhoneInfo> phones) {
        if (phones == null || phones.isEmpty()) {
            return;
        }
        List<String> ids = Wraps.list(phones.keySet());
        for (int i = 0; i < SMS_SPOT_CHECK_SIZE && !ids.isEmpty(); i++) {
            String id = ids.get(random.nextInt(ids.size()));
            SmsPhoneInfo info = phones.get(id);
            if (info == null) {
                continue;
            }
            List<SmsMessageInfo> messages;
            try {
                messages = fetchMessages(info);
            } catch (Exception e) {
                log.debug("[freephonenum] 抽查 {} 失败: {}", info.getPhone(), e.getMessage());
                continue;
            }
            if (messages == null || messages.isEmpty()) {
                continue;
            }
            Integer prev = lastMessageCount.get(id);
            if (prev == null) {
                // 首次抽查到该号码：视为基线，但立即分发一次带真实短信的事件，
                // 便于验收/监听器首次即能收到「号码已有短信」的通知
                lastMessageCount.put(id, messages.size());
                log.info("[freephonenum] 号码 {} 基线短信 {} 条，分发首条事件", info.getPhone(), messages.size());
                fire(WatcherEvent.MODIFY, info, messages);
            } else if (messages.size() != prev) {
                // 短信条数变化 = 收到新短信
                lastMessageCount.put(id, messages.size());
                log.info("[freephonenum] 号码 {} 短信由 {} 条变为 {} 条，触发事件", info.getPhone(), prev, messages.size());
                fire(WatcherEvent.MODIFY, info, messages);
            } else {
                lastMessageCount.put(id, messages.size());
            }
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
        support.clearListeners();
        log.info("[freephonenum] 号码轮询已停止");
    }

    // ==================== 内部方法 ====================

    /**
     * HTTP 直连抓取号码列表页并解析全部号码。
     *
     * @return 号码列表；抓取失败返回空列表
     */
    private List<SmsPhoneInfo> fetchPhones() {
        try {
            String html = HttpClientFactory.of(LIST_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://freephonenum.com/")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .getBodyString();
            if (html == null || html.isBlank()) {
                log.warn("[freephonenum] 号码列表页响应为空");
                return Collections.emptyList();
            }
            return parsePhoneList(html);
        } catch (Exception e) {
            log.warn("[freephonenum] 抓取号码列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析号码列表页 HTML。
     *
     * @param html 页面 HTML
     * @return 号码列表
     */
    private List<SmsPhoneInfo> parsePhoneList(String html) {
        Document document = Jsoup.parse(html, LIST_URL);
        Elements links = document.select(NUMBER_LINK_SELECTOR);
        Map<String, SmsPhoneInfo> seen = Wraps.map();
        for (Element link : links) {
            String href = link.absUrl("href");
            if (href.isEmpty()) {
                continue;
            }
            String phone = link.text().trim();
            if (phone.isEmpty()) {
                continue;
            }
            // 号码详情地址形如 https://freephonenum.com/be/receive-sms/460210041
            String countryCode = extractCountryCode(href);
            String id = extractNumberId(href, phone);
            if (id == null || id.isEmpty() || seen.containsKey(id)) {
                continue;
            }
            seen.put(id, SmsPhoneInfo.builder()
                    .id(id)
                    .phone(phone)
                    .countryCode(countryCode)
                    .countryName(countryName(countryCode))
                    .smsUrl(href)
                    .build());
        }
        List<SmsPhoneInfo> result = Wraps.list(seen.values());
        if (log.isDebugEnabled()) {
            log.debug("[freephonenum] 号码列表解析完成: {} 条", result.size());
        }
        return result;
    }

    /**
     * 解析号码短信页 HTML。
     *
     * @param html  页面 HTML
     * @param phone 号码
     * @return 短信列表
     */
    private List<SmsMessageInfo> parseMessages(String html, SmsPhoneInfo phone) {
        Document document = Jsoup.parse(html, phone.getSmsUrl());
        Elements messages = document.select(MESSAGE_SELECTOR);
        List<SmsMessageInfo> result = Wraps.list();
        for (Element message : messages) {
            Element textEl = message.selectFirst(MESSAGE_TEXT_SELECTOR);
            if (textEl == null) {
                continue;
            }
            String content = textEl.text().trim();
            if (content.isEmpty()) {
                continue;
            }
            // 发送方 / 业务名：msg 容器内第一个文本标签（服务名或 Sender）
            String from = extractSender(message);
            result.add(SmsMessageInfo.builder()
                    .phone(phone.getPhone())
                    .from(from)
                    .content(content)
                    .verifyCode(extractVerifyCode(content))
                    .build());
        }
        if (log.isDebugEnabled()) {
            log.debug("[freephonenum] 号码 {} 短信解析完成: {} 条", phone.getPhone(), result.size());
        }
        return result;
    }

    /**
     * 从短信容器中提取发送方标识（业务名或 Sender 后的号码文本）。
     *
     * @param message 短信容器元素
     * @return 发送方标识，无法识别时为空
     */
    private static String extractSender(Element message) {
        Element name = message.selectFirst("span.font-bold");
        if (name == null) {
            return "";
        }
        String label = name.text().trim();
        Element senderNum = message.selectFirst("a[href*=\"receive-sms-from\"]");
        if (senderNum != null && !senderNum.text().trim().isEmpty()) {
            return label + " " + senderNum.text().trim();
        }
        return label;
    }

    /**
     * 从号码详情地址中提取号码 ID（如 {@code /be/receive-sms/460210041 -> 460210041}）。
     *
     * @param href  号码详情地址
     * @param phone 号码展示文本（兜底）
     * @return 号码 ID
     */
    private static String extractNumberId(String href, String phone) {
        if (href != null) {
            Matcher matcher = NUMBER_PATTERN.matcher(href);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return phone == null ? "" : phone;
    }

    /**
     * 从号码详情地址中提取国家二字码（如 {@code /be/receive-sms/460210041 -> BE}）。
     *
     * @param href 号码详情地址
     * @return 国家二字码，无法识别时为空
     */
    private static String extractCountryCode(String href) {
        if (href == null || href.isEmpty()) {
            return "";
        }
        Matcher matcher = COUNTRY_GROUP_PATTERN.matcher(href);
        return matcher.find() ? matcher.group(1).toUpperCase() : "";
    }

    /**
     * 将国家二字码转换为常见中文名称。
     *
     * @param code 国家二字码
     * @return 国家名称；未知返回空
     */
    private static String countryName(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        switch (code) {
            case "US":
                return "美国";
            case "CA":
                return "加拿大";
            case "GB":
                return "英国";
            case "DE":
                return "德国";
            case "BE":
                return "比利时";
            case "NL":
                return "荷兰";
            case "FR":
                return "法国";
            case "ES":
                return "西班牙";
            case "IT":
                return "意大利";
            case "AU":
                return "澳大利亚";
            case "RO":
                return "罗马尼亚";
            case "IN":
                return "印度";
            default:
                return code;
        }
    }

    /**
     * 按号码短信地址模式拼接号码页地址（兜底，正常情况下 listPhones 已写入 smsUrl）。
     *
     * @param info 号码
     * @return 号码短信地址
     */
    private static String buildNumberUrl(SmsPhoneInfo info) {
        if (info == null || info.getId() == null || info.getId().isEmpty()) {
            return "";
        }
        String cc = info.getCountryCode() == null ? "" : info.getCountryCode().toLowerCase();
        return cc.isEmpty()
                ? "https://freephonenum.com/receive-sms/" + info.getId()
                : "https://freephonenum.com/" + cc + "/receive-sms/" + info.getId();
    }

    /**
     * 计算号码的变更标识。
     *
     * <p>以 {@code 号码|国家} 的哈希作为变更标识；列表页不提供更新时间文本，
     * 号码保持在线即视为不变。实际短信变化由短信页内容体现，事件分发时携带实时抓取的短信。</p>
     *
     * @param info 号码
     * @return 变更标识
     */
    private static long changeToken(SmsPhoneInfo info) {
        String token = (info == null ? "" : info.getId()) + "|"
                + (info == null ? "" : info.getCountryCode());
        return token.hashCode();
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
     * 向全部监听器分发号码事件（携带实时抓取的真实短信）。
     *
     * <p>事件的 {@code source} 为 {@link SmsPhoneMessage}（号码 + 短信列表），
     * 便于监听器在回调中直接核对号码与其收到的短信。</p>
     *
     * @param event    事件类型
     * @param info     号码
     * @param messages 该号码抓取到的短信列表
     */
    private void fire(WatcherEvent event, SmsPhoneInfo info, List<SmsMessageInfo> messages) {
        SmsPhoneMessage payload = SmsPhoneMessage.builder()
                .phone(info)
                .messages(messages)
                .build();
        EventObserver observer = EventObserver.builder()
                .currentPath(LIST_URL)
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
     * @param id    已下架号码
     */
    private void fire(WatcherEvent event, String id) {
        EventObserver observer = EventObserver.builder()
                .currentPath(LIST_URL)
                .triggerFile(id)
                .eventType(event)
                .build();
        support.fire(observer);
    }
}
