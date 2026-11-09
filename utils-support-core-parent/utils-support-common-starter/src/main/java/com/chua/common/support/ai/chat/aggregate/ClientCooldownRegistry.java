package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.aggregate.strategy.RouterStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端冷却登记表 —— 调失败的 Key 先排除一段时间，到期后自动恢复参与路由。
 *
 * <p><b>为什么需要</b>：{@link FailoverTemplate} 只在单次请求内做故障转移，请求结束后
 * 不留任何记忆。某个 Key 被上游限流（429）或余额耗尽后，下一个请求仍会先撞它一次，
 * 再切到可用的 Key —— 这既浪费一次往返，也会持续放大上游的限流计数。</p>
 *
 * <p><b>排除多久</b>：首次失败冷却 {@value #DEFAULT_COOLDOWN_MS}ms，同一客户端连续失败
 * 按 2 的幂次翻倍（60s → 2m → 4m → 8m），封顶 {@value #DEFAULT_MAX_COOLDOWN_MS}ms。
 * 一次成功立即清除冷却，恢复参与路由 —— 这里只做「时间到了就放行」的弱熔断，
 * 不做半开探测：放行后若仍失败会再次进入冷却并继续翻倍。</p>
 *
 * <p><b>为什么是进程级共享</b>：冷却记录的是「这个上游 Key 现在不能用」，与该 Key
 * 被哪个聚合实例持有无关；{@code ModelClientResolver} 每 5 分钟重建一次客户端，
 * 若把状态挂在实例上，冷却会随重建一起丢失。登记表按
 * {@code provider|model|apiKey 摘要} 取值，重建后仍能命中同一行记录。</p>
 *
 * <p><b>不冷却的情况</b>：400 / 参数错误这类「换个 Key 也一样」的请求级错误，
 * 排除客户端只会误伤健康 Key，因此记下失败但不进入冷却。</p>
 *
 * <p><b>容量</b>：登记表最多保留 {@value #MAX_ENTRIES} 条，超过时先清理已到期记录，
 * 防止长期运行无限增长。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Slf4j
public final class ClientCooldownRegistry {

    /**
     * 默认基础冷却时长（毫秒）：首次失败排除 60 秒
     */
    public static final long DEFAULT_COOLDOWN_MS = 60_000L;

    /**
     * 默认最长冷却时长（毫秒）：连续失败封顶 10 分钟
     */
    public static final long DEFAULT_MAX_COOLDOWN_MS = 600_000L;

    /**
     * 连续失败翻倍次数上限：60s → 2m → 4m → 8m，再乘就到顶了
     */
    private static final int MAX_BACKOFF_SHIFT = 4;

    /**
     * 登记表容量上限
     */
    private static final int MAX_ENTRIES = 4096;

    /**
     * 冷却键分隔符
     */
    private static final String KEY_SEPARATOR = "|";

    /**
     * FNV-1a 64 位偏移量
     */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

    /**
     * FNV-1a 64 位质数
     */
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * 请求级错误（换 Key 也无法恢复）的识别片段
     */
    private static final String[] REQUEST_ERROR_HINTS = {
            "bad request", "invalid request", "invalid parameter", "invalid_parameter",
            "unsupported", "context length", "context_length", " 400 ", "http 400"
    };

    /**
     * 基础冷却时长（毫秒）
     */
    private static volatile long baseCooldownMs = DEFAULT_COOLDOWN_MS;

    /**
     * 最长冷却时长（毫秒）
     */
    private static volatile long maxCooldownMs = DEFAULT_MAX_COOLDOWN_MS;

    /**
     * 冷却状态：键为 provider|model|key 摘要
     */
    private static final Map<String, CooldownState> STATES = new ConcurrentHashMap<>();

    private ClientCooldownRegistry() {
    }

    /**
     * 覆盖冷却参数，供部署侧或测试调整。
     *
     * <p>非正数表示使用默认值；{@code maxMillis} 小于 {@code baseMillis} 时按基础值封顶。</p>
     *
     * @param baseMillis 首次失败冷却时长（毫秒）
     * @param maxMillis  连续失败冷却上限（毫秒）
     */
    public static void configure(long baseMillis, long maxMillis) {
        baseCooldownMs = baseMillis > 0 ? baseMillis : DEFAULT_COOLDOWN_MS;
        maxCooldownMs = maxMillis > 0 ? maxMillis : DEFAULT_MAX_COOLDOWN_MS;
    }

    /**
     * 清空全部冷却记录并恢复默认参数，主要供测试使用。
     */
    public static void reset() {
        STATES.clear();
        baseCooldownMs = DEFAULT_COOLDOWN_MS;
        maxCooldownMs = DEFAULT_MAX_COOLDOWN_MS;
    }

    /**
     * 从候选中挑出当前不在冷却期的客户端。
     *
     * <p>若所有候选都在冷却中，直接放行全部（半开）：宁可再撞一次限流，
     * 也不能让整体服务不可用 —— 这正是「排除一段时间、过后自动恢复」与
     * 「熔断到底」的分界。放行之后仍失败会再次进入冷却并继续翻倍。</p>
     *
     * @param clients 候选客户端列表
     * @return 可参与本次调用的客户端列表
     */
    public static List<RouterStrategy.WeightedClient> available(
            List<RouterStrategy.WeightedClient> clients) {
        if (clients == null || clients.isEmpty()) {
            return clients;
        }
        List<RouterStrategy.WeightedClient> healthy = new ArrayList<>(clients.size());
        for (RouterStrategy.WeightedClient client : clients) {
            if (!isCoolingDown(client)) {
                healthy.add(client);
            }
        }
        if (healthy.isEmpty()) {
            log.warn("[Cooldown] 全部 {} 个候选客户端均在冷却中，本次降级为半开放行", clients.size());
            return clients;
        }
        if (healthy.size() < clients.size()) {
            log.info("[Cooldown] 本次跳过 {} 个冷却中的客户端，剩余 {}",
                    clients.size() - healthy.size(), healthy.size());
        }
        return healthy;
    }

    /**
     * 该客户端当前是否处于冷却期。
     *
     * @param client 候选客户端
     * @return true 表示应跳过该客户端
     */
    public static boolean isCoolingDown(RouterStrategy.WeightedClient client) {
        if (client == null) {
            return false;
        }
        String key = keyOf(client);
        CooldownState state = STATES.get(key);
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state.until <= now) {
            // 到期即恢复：清理记录，下次失败重新从基础时长开始
            STATES.remove(key, state);
            log.info("[Cooldown] {}:{} 冷却结束，恢复参与路由", client.provider(), client.model());
            return false;
        }
        return true;
    }

    /**
     * 记录一次失败，并按失败次数计算冷却时长。
     *
     * @param client 失败的客户端
     * @param error  失败原因，可为 null
     */
    public static void recordFailure(RouterStrategy.WeightedClient client, Throwable error) {
        if (client == null || !shouldCooldown(error)) {
            return;
        }
        String key = keyOf(client);
        long now = System.currentTimeMillis();
        CooldownState state = STATES.compute(key, (k, old) -> {
            CooldownState next = old == null ? new CooldownState() : old;
            next.failures++;
            next.until = now + cooldownMillis(next.failures);
            next.reason = describe(error);
            return next;
        });
        log.warn("[Cooldown] {}:{} 第 {} 次失败，排除 {}s（原因: {}）",
                client.provider(), client.model(), state.failures,
                (state.until - now) / 1000, state.reason);
        trimIfNecessary();
    }

    /**
     * 记录一次成功：立即解除该客户端的冷却。
     *
     * @param client 成功的客户端
     */
    public static void recordSuccess(RouterStrategy.WeightedClient client) {
        if (client == null) {
            return;
        }
        if (STATES.remove(keyOf(client)) != null) {
            log.info("[Cooldown] {}:{} 调用成功，冷却已解除", client.provider(), client.model());
        }
    }

    /**
     * 该客户端剩余冷却时长（毫秒），未冷却返回 0。
     *
     * @param client 候选客户端
     * @return 剩余冷却毫秒数
     */
    public static long remainingMillis(RouterStrategy.WeightedClient client) {
        if (client == null) {
            return 0L;
        }
        CooldownState state = STATES.get(keyOf(client));
        if (state == null) {
            return 0L;
        }
        return Math.max(0L, state.until - System.currentTimeMillis());
    }

    /**
     * 当前登记条目数，供监控/测试断言。
     *
     * @return 条目数
     */
    public static int size() {
        return STATES.size();
    }

    /**
     * 计算第 N 次连续失败应排除的时长。
     *
     * @param failures 连续失败次数（从 1 开始）
     * @return 冷却毫秒数
     */
    private static long cooldownMillis(int failures) {
        int shift = Math.min(Math.max(failures - 1, 0), MAX_BACKOFF_SHIFT);
        long duration = (long) baseCooldownMs << shift;
        long cap = Math.max(baseCooldownMs, maxCooldownMs);
        return Math.min(duration, cap);
    }

    /**
     * 判断该异常是否值得把客户端排除一段时间。
     *
     * @param error 异常
     * @return true 表示应进入冷却
     */
    private static boolean shouldCooldown(Throwable error) {
        if (error == null) {
            return true;
        }
        String message = describe(error).toLowerCase(Locale.ROOT);
        for (String hint : REQUEST_ERROR_HINTS) {
            if (message.contains(hint)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取异常的根因描述。
     *
     * @param error 异常
     * @return 描述，无消息时取异常类名
     */
    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    /**
     * 生成冷却键：provider|model|apiKey 摘要。
     *
     * <p>API Key 只以摘要形式入键，避免在进程内长期持有明文；无 Key（免费渠道聚合）
     * 时退化为客户端实例标识，重建后冷却自然失效，不会误伤其它账号。</p>
     *
     * @param client 候选客户端
     * @return 冷却键
     */
    private static String keyOf(RouterStrategy.WeightedClient client) {
        ChatClient raw = client.client();
        String apiKey = null;
        try {
            apiKey = raw == null ? null : raw.getApiKey();
        } catch (Exception e) {
            log.debug("[Cooldown] 读取客户端 API Key 失败，按实例标识记录: {}", e.getMessage());
        }
        String identity;
        if (apiKey == null || apiKey.isBlank()) {
            identity = "instance-" + System.identityHashCode(raw);
        } else {
            identity = "key-" + Long.toHexString(fnv1a(apiKey));
        }
        return client.provider() + KEY_SEPARATOR + client.model() + KEY_SEPARATOR + identity;
    }

    /**
     * FNV-1a 64 位哈希。
     *
     * @param value 原始字符串
     * @return 64 位哈希值
     */
    private static long fnv1a(String value) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /**
     * 超过容量上限时清理已到期记录。
     */
    private static void trimIfNecessary() {
        if (STATES.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        STATES.entrySet().removeIf(entry -> entry.getValue().until <= now);
        if (STATES.size() > MAX_ENTRIES) {
            log.warn("[Cooldown] 冷却登记表条目超过上限 {}，已清理到期记录后仍超限: {}",
                    MAX_ENTRIES, STATES.size());
        }
    }

    /**
     * 单个客户端的冷却状态。
     */
    private static final class CooldownState {

        /**
         * 连续失败次数
         */
        private int failures;

        /**
         * 冷却截止时间戳（毫秒）
         */
        private long until;

        /**
         * 最近一次失败原因
         */
        private String reason;
    }
}
