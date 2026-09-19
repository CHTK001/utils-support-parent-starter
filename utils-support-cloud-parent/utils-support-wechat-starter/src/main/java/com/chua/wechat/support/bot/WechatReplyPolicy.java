package com.chua.wechat.support.bot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.utils.StringUtils;

/**
 * 个人微信自动回复的风控守卫。
 *
 * <p>注入个人微信进程的封号风险主要来自"回复得像机器"，所以拦截规则全部放在这一层：
 * 白名单 / 黑名单、群聊总开关、关键词触发、单对象每分钟上限、当日总量上限、
 * 两次回复间的最小间隔与随机抖动、人工接管开关。</p>
 *
 * <p>判定入口是 {@link #denyReason(BotInboundMessage)}，返回 {@code null} 表示放行；
 * 放行后必须调用 {@link #markReplied(String)} 记账，否则频控不生效。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WechatReplyPolicy {

    /**
     * 拒绝自动回复的原因。
     */
    public enum DenyReason {

        /**
         * 处于人工接管状态
        */
        PAUSED,

        /**
         * 消息对象为空
        */
        NO_MESSAGE,

        /**
         * 消息正文为空，无内容可回复
        */
        EMPTY_CONTENT,

        /**
         * 消息来自群聊，而群聊回复开关未打开
        */
        GROUP_DISABLED,

        /**
         * 命中黑名单
        */
        BLOCKED,

        /**
         * 白名单非空且未命中
        */
        NOT_ALLOWED,

        /**
         * 配置了触发关键词但正文未包含
        */
        NO_KEYWORD,

        /**
         * 当日回复总量已达上限
        */
        DAILY_LIMIT,

        /**
         * 单个会话每分钟回复次数已达上限
        */
        USER_RATE_LIMIT
    }

    /**
     * 一分钟毫秒数
    */
    private static final long MINUTE_MILLIS = 60_000L;

    /**
     * 一天毫秒数
    */
    private static final long DAY_MILLIS = 24 * 60 * 60_000L;

    /**
     * 频控记账的会话数上限，超出后清理整日无记录的会话
    */
    private static final int MAX_TRACKED_CONVERSATIONS = 1024;

    /**
     * 默认单对象每分钟回复上限
    */
    private static final int DEFAULT_MAX_PER_USER_PER_MINUTE = 5;

    /**
     * 默认当日回复总量上限
    */
    private static final int DEFAULT_MAX_PER_DAY = 200;

    /** 默认同一对端两次回复的最小间隔（毫秒）*/
    private static final long DEFAULT_MIN_INTERVAL_MILLIS = 3_000L;

    /** 默认随机延迟上限（毫秒）*/
    private static final long DEFAULT_JITTER_MILLIS = 2_000L;

    /**
     * 白名单，为空表示不限制对象
    */
    private final Set<String> allowList = new LinkedHashSet<>();

    /**
     * 黑名单，优先级高于白名单
    */
    private final Set<String> blockList = new LinkedHashSet<>();

    /**
     * 触发关键词，为空表示任何消息都触发
    */
    private final Set<String> keywords = new LinkedHashSet<>();

    /**
     * 单对象每分钟命中时间
    */
    private final Map<String, Deque<Long>> userHits = new ConcurrentHashMap<>();

    /**
     * 当日全局回复时间
    */
    private final Deque<Long> dailyHits = new ArrayDeque<>();

    /**
     * 人工接管开关，置位后一律不回
    */
    private final AtomicBoolean paused = new AtomicBoolean(false);

    /**
     * 是否允许在群聊回复
    */
    private boolean allowGroups;

    /**
     * 单对象每分钟回复上限
    */
    private int maxPerUserPerMinute = DEFAULT_MAX_PER_USER_PER_MINUTE;

    /**
     * 当日回复总量上限
    */
    private int maxPerDay = DEFAULT_MAX_PER_DAY;

    /** 同一对端两次回复的最小间隔（毫秒）*/
    private long minIntervalMillis = DEFAULT_MIN_INTERVAL_MILLIS;

    /** 追加到最小间隔上的随机延迟上限（毫秒）*/
    private long jitterMillis = DEFAULT_JITTER_MILLIS;

    /**
     * 加入白名单。
     *
     * @param wxid 用户或群 wxid
     * @return this
     */
    public WechatReplyPolicy allow(String wxid) {
        if (StringUtils.isNotBlank(wxid)) {
            allowList.add(wxid);
        }
        return this;
    }

    /**
     * 加入黑名单。
     *
     * @param wxid 用户或群 wxid
     * @return this
     */
    public WechatReplyPolicy block(String wxid) {
        if (StringUtils.isNotBlank(wxid)) {
            blockList.add(wxid);
        }
        return this;
    }

    /**
     * 加入触发关键词。
     *
     * @param keyword 关键词
     * @return this
     */
    public WechatReplyPolicy needKeyword(String keyword) {
        if (StringUtils.isNotBlank(keyword)) {
            keywords.add(keyword);
        }
        return this;
    }

    /**
     * 设置群聊回复开关。
     *
     * @param allowGroups 是否允许在群聊回复
     * @return this
     */
    public WechatReplyPolicy allowGroups(boolean allowGroups) {
        this.allowGroups = allowGroups;
        return this;
    }

    /**
     * 设置单对象每分钟回复上限。
     *
     * @param maxPerUserPerMinute 上限次数
     * @return this
     */
    public WechatReplyPolicy maxPerUserPerMinute(int maxPerUserPerMinute) {
        this.maxPerUserPerMinute = maxPerUserPerMinute;
        return this;
    }

    /**
     * 设置当日回复总量上限。
     *
     * @param maxPerDay 上限次数
     * @return this
     */
    public WechatReplyPolicy maxPerDay(int maxPerDay) {
        this.maxPerDay = maxPerDay;
        return this;
    }

    /**
     * 设置同一对端两次回复的最小间隔。
     *
     * @param minIntervalMillis 间隔毫秒数
     * @return this
     */
    public WechatReplyPolicy minIntervalMillis(long minIntervalMillis) {
        this.minIntervalMillis = minIntervalMillis;
        return this;
    }

    /**
     * 设置随机延迟上限。
     *
     * @param jitterMillis 抖动毫秒数上限
     * @return this
     */
    public WechatReplyPolicy jitterMillis(long jitterMillis) {
        this.jitterMillis = jitterMillis;
        return this;
    }

    /**
     * 进入人工接管，停止一切自动回复。
     *
     * @return this
     */
    public WechatReplyPolicy pause() {
        paused.set(true);
        return this;
    }

    /**
     * 交还自动回复。
     *
     * @return this
     */
    public WechatReplyPolicy resume() {
        paused.set(false);
        return this;
    }

    /**
     * 是否处于人工接管。
     *
     * @return true 表示已暂停自动回复
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * 判定这条入站消息能否自动回复。
     *
     * @param message 入站消息
     * @return 放行返回 {@code null}，否则返回拒绝原因
     */
    public DenyReason denyReason(BotInboundMessage message) {
        if (paused.get()) {
            return DenyReason.PAUSED;
        }
        if (message == null) {
            return DenyReason.NO_MESSAGE;
        }
        if (StringUtils.isBlank(message.getContent())) {
            return DenyReason.EMPTY_CONTENT;
        }
        String fromUser = message.getFromUser();
        String conversation = StringUtils.isNotBlank(message.getChatId())
                ? message.getChatId() : fromUser;
        if (message.isFromGroup() && !allowGroups) {
            return DenyReason.GROUP_DISABLED;
        }
        if (blockList.contains(fromUser) || blockList.contains(conversation)) {
            return DenyReason.BLOCKED;
        }
        if (!allowList.isEmpty()
                && !allowList.contains(fromUser)
                && !allowList.contains(conversation)) {
            return DenyReason.NOT_ALLOWED;
        }
        if (!keywords.isEmpty() && !containsKeyword(message.getContent())) {
            return DenyReason.NO_KEYWORD;
        }
        long now = System.currentTimeMillis();
        if (countDaily(now) >= maxPerDay) {
            return DenyReason.DAILY_LIMIT;
        }
        if (countUser(conversation, now) >= maxPerUserPerMinute) {
            return DenyReason.USER_RATE_LIMIT;
        }
        return null;
    }

    /**
     * 记一次已发出的回复，供频控统计。
     *
     * @param conversation 会话 wxid（群聊用群 wxid，单聊用对方 wxid）
     */
    public void markReplied(String conversation) {
        long now = System.currentTimeMillis();
        if (StringUtils.isNotBlank(conversation)) {
            Deque<Long> hits = userHits.computeIfAbsent(conversation, key -> new ArrayDeque<>());
            synchronized (hits) {
                hits.addLast(now);
            }
            trimExpiredHits();
        }
        synchronized (dailyHits) {
            dailyHits.addLast(now);
        }
    }

    /**
     * 本次回复前应等待的毫秒数，用于把发送节奏拉得像人。
     *
     * @return 最小间隔加上随机抖动
     */
    public long nextDelayMillis() {
        long interval = Math.max(minIntervalMillis, 0L);
        if (jitterMillis <= 0) {
            return interval;
        }
        return interval + ThreadLocalRandom.current().nextLong(jitterMillis + 1);
    }

    /**
     * 正文是否命中任一触发关键词。
     *
     * @param content 消息正文，不允许为 null
     * @return true 表示命中关键词
     */
    private boolean containsKeyword(String content) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统计单个会话最近一分钟内的回复次数。
     *
     * @param conversation 会话 wxid，可为空白
     * @param now 当前时间戳（毫秒）
     * @return 一分钟内的回复次数
     */
    private int countUser(String conversation, long now) {
        if (StringUtils.isBlank(conversation)) {
            return 0;
        }
        Deque<Long> hits = userHits.get(conversation);
        if (hits == null) {
            return 0;
        }
        synchronized (hits) {
            prune(hits, now - MINUTE_MILLIS);
            return hits.size();
        }
    }

    /**
     * 统计全局最近一天内的回复次数。
     *
     * @param now 当前时间戳（毫秒）
     * @return 一天内的回复次数
     */
    private int countDaily(long now) {
        synchronized (dailyHits) {
            prune(dailyHits, now - DAY_MILLIS);
            return dailyHits.size();
        }
    }

    /**
     * 会话记账表只增不减会持续占内存，超过阈值时清掉整日无回复的会话。
     */
    private void trimExpiredHits() {
        if (userHits.size() <= MAX_TRACKED_CONVERSATIONS) {
            return;
        }
        long expiredBefore = System.currentTimeMillis() - DAY_MILLIS;
        Iterator<Map.Entry<String, Deque<Long>>> iterator = userHits.entrySet().iterator();
        while (iterator.hasNext()) {
            Deque<Long> hits = iterator.next().getValue();
            synchronized (hits) {
                prune(hits, expiredBefore);
                if (hits.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * 摘除队首已过期的记账时间，队列按时间递增，遇到未过期即停止。
     *
     * @param hits 记账队列，调用方需持有其监视器锁
     * @param expiredBefore 过期分界时间戳（毫秒），早于此值的记录被丢弃
     */
    private static void prune(Deque<Long> hits, long expiredBefore) {
        Iterator<Long> iterator = hits.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() < expiredBefore) {
                iterator.remove();
            } else {
                return;
            }
        }
    }
}
