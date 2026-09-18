package com.chua.wechat.support.bot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.utils.StringUtils;

/**
 * 个人微信自动回复的风控守卫。
 *
 * <p>hook 个人微信的封号风险主要来自"回复得像机器"，所以拦截规则全部放在这一层：
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

    /** 一分钟毫秒数 */
    private static final long MINUTE_MILLIS = 60_000L;

    /** 一天毫秒数 */
    private static final long DAY_MILLIS = 24 * 60 * 60_000L;

    /** 白名单，为空表示不限制对象 */
    private final Set<String> allowList = new LinkedHashSet<>();

    /** 黑名单，优先级高于白名单 */
    private final Set<String> blockList = new LinkedHashSet<>();

    /** 触发关键词，为空表示任何消息都触发 */
    private final Set<String> keywords = new LinkedHashSet<>();

    /** 单对象每分钟命中时间 */
    private final Map<String, Deque<Long>> userHits = new ConcurrentHashMap<>();

    /** 当日全局回复时间 */
    private final Deque<Long> dailyHits = new ArrayDeque<>();

    /** 人工接管开关，置位后一律不回 */
    private final AtomicBoolean paused = new AtomicBoolean(false);

    /** 抖动随机源 */
    private final Random random = new Random();

    /** 是否允许在群聊回复 */
    private boolean allowGroups;

    /** 单对象每分钟回复上限 */
    private int maxPerUserPerMinute = 5;

    /** 当日回复总量上限 */
    private int maxPerDay = 200;

    /** 同一对端两次回复的最小间隔（毫秒）*/
    private long minIntervalMillis = 3_000L;

    /** 追加到最小间隔上的随机延迟上限（毫秒）*/
    private long jitterMillis = 2_000L;

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

    public WechatReplyPolicy allowGroups(boolean allowGroups) {
        this.allowGroups = allowGroups;
        return this;
    }

    public WechatReplyPolicy maxPerUserPerMinute(int maxPerUserPerMinute) {
        this.maxPerUserPerMinute = maxPerUserPerMinute;
        return this;
    }

    public WechatReplyPolicy maxPerDay(int maxPerDay) {
        this.maxPerDay = maxPerDay;
        return this;
    }

    public WechatReplyPolicy minIntervalMillis(long minIntervalMillis) {
        this.minIntervalMillis = minIntervalMillis;
        return this;
    }

    public WechatReplyPolicy jitterMillis(long jitterMillis) {
        this.jitterMillis = jitterMillis;
        return this;
    }

    /** 进入人工接管，停止一切自动回复 */
    public WechatReplyPolicy pause() {
        paused.set(true);
        return this;
    }

    /** 交还自动回复 */
    public WechatReplyPolicy resume() {
        paused.set(false);
        return this;
    }

    /** 是否处于人工接管 */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * 判定这条入站消息能否自动回复。
     *
     * @param message 入站消息
     * @return 放行返回 {@code null}，否则返回拒绝原因
     */
    public String denyReason(BotInboundMessage message) {
        if (paused.get()) {
            return "paused";
        }
        if (message == null) {
            return "no-message";
        }
        if (StringUtils.isBlank(message.getContent())) {
            return "empty-content";
        }
        String fromUser = message.getFromUser();
        String conversation = StringUtils.isNotBlank(message.getChatId())
                ? message.getChatId() : fromUser;
        if (message.isFromGroup() && !allowGroups) {
            return "group-disabled";
        }
        if (blockList.contains(fromUser) || blockList.contains(conversation)) {
            return "blocked";
        }
        if (!allowList.isEmpty()
                && !allowList.contains(fromUser)
                && !allowList.contains(conversation)) {
            return "not-allowed";
        }
        if (!keywords.isEmpty() && !containsKeyword(message.getContent())) {
            return "no-keyword";
        }
        if (maxPerDay <= 0) {
            return "daily-disabled";
        }
        long now = System.currentTimeMillis();
        if (countDaily(now) >= maxPerDay) {
            return "daily-limit";
        }
        if (countUser(conversation, now) >= maxPerUserPerMinute) {
            return "user-rate-limit";
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
        if (jitterMillis <= 0) {
            return Math.max(minIntervalMillis, 0L);
        }
        return Math.max(minIntervalMillis, 0L) + random.nextInt((int) jitterMillis + 1);
    }

    private boolean containsKeyword(String content) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

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

    private int countDaily(long now) {
        synchronized (dailyHits) {
            prune(dailyHits, now - DAY_MILLIS);
            return dailyHits.size();
        }
    }

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
