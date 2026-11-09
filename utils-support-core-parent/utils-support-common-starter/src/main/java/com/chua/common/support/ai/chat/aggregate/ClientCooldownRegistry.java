package com.chua.common.support.ai.chat.aggregate;

// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.aggregate.strategy.RouterStrategy;
import com.chua.common.support.task.cooldown.CooldownGate;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

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
 * <p><b>状态落在哪</b>：本类只保留「AI 路由」这一层的语义（哪些异常该冷却、冷却键怎么拼），
 * 真正的等待与恢复交给 {@link CooldownGate} 门面；渠道侧若也要一套「限流换号 + 慢慢恢复」，
 * 直接 {@code CooldownGate.of("channel:xx")} 即可，不必再写一份状态机。</p>
 *
 * <p><b>为什么是进程级共享</b>：冷却记录的是「这个上游 Key 现在不能用」，与该 Key
 * 由哪个客户端实例持有无关，故按键而非按实例记录。</p>
 *
 * <p><b>不冷却的情况</b>：400 / 参数错误这类「换个 Key 也一样」的请求级错误，
 * 排除客户端只会误伤健康 Key，因此记下失败但不进入冷却。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Slf4j
public final class ClientCooldownRegistry {

    /**
     * 默认基础冷却时长（毫秒）：首次失败排除 60 秒
     */
    public static final long DEFAULT_COOLDOWN_MS = CooldownGate.DEFAULT_BASE_MS;

    /**
     * 默认最长冷却时长（毫秒）：连续失败封顶 10 分钟
     */
    public static final long DEFAULT_MAX_COOLDOWN_MS = CooldownGate.DEFAULT_MAX_MS;

    /**
     * AI 路由层的冷却作用域
     */
    private static final CooldownGate GATE = CooldownGate.of("ai-chat");

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
     * 连不上的识别片段（地址写错 / 上游没起 / DNS 失败）。
     *
     * <p>这类失败与配额无关：按「限流」处理会把一个地址填错的成员误判成「被限流」，
     * 进而触发「排到最后」之类的限流策略。它们仍会短时冷却，但不会被当作限流事件。</p>
     */
    private static final String[] UNREACHABLE_HINTS = {
            "connect failed", "failed to connect", "connection refused", "connectexception",
            "unknownhost", "nodename nor servname", "no route to host", "network is unreachable",
            "stream reset", "connection reset", "premature end of content-length"
    };

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
        GATE.configure(baseMillis, maxMillis);
    }

    /**
     * 清空全部冷却记录并恢复默认参数，主要供测试使用。
     */
    public static void reset() {
        GATE.reset();
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
        return GATE.filterOpen(clients, ClientCooldownRegistry::keyOf);
    }

    /**
     * 该客户端当前是否处于冷却期。
     *
     * @param client 候选客户端
     * @return true 表示应跳过该客户端
     */
    public static boolean isCoolingDown(RouterStrategy.WeightedClient client) {
        return client != null && GATE.blocked(keyOf(client));
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
        GATE.fail(keyOf(client), describe(error), !isUnreachable(error));
    }

    /**
     * 记录一次成功：立即解除该客户端的冷却。
     *
     * @param client 成功的客户端
     */
    public static void recordSuccess(RouterStrategy.WeightedClient client) {
        if (client != null) {
            GATE.pass(keyOf(client));
        }
    }

    /**
     * 该客户端剩余冷却时长（毫秒），未冷却返回 0。
     *
     * @param client 候选客户端
     * @return 剩余冷却毫秒数
     */
    public static long remainingMillis(RouterStrategy.WeightedClient client) {
        return client == null ? 0L : GATE.remaining(keyOf(client));
    }

    /**
     * 当前登记条目数，供监控/测试断言。
     *
     * @return 条目数
     */
    public static int size() {
        return GATE.size();
    }

    /**
     * 按键判断是否处于冷却期（不依赖 {@link RouterStrategy.WeightedClient}）。
     *
     * <p>{@link SwitchingChatClient} 的实现（多 Key / 路由组）自己拼键：成员可能是
     * 同一供应商的多个 Key，也可能是不同供应商的模型，没法从客户端实例反推身份。</p>
     *
     * @param key 冷却键（如 {@code provider|model|key-xxxx}）
     * @return true 表示应跳过
     */
    public static boolean isCooling(String key) {
        return GATE.blocked(key);
    }

    /**
     * 按键记一次失败（冷却时长按连续失败次数翻倍）。
     *
     * @param key 冷却键
     */
    public static void failure(String key) {
        failure(key, null);
    }

    /**
     * 按键记一次失败；请求级错误（换 Key 也一样）不进入冷却。
     *
     * @param key   冷却键
     * @param error 失败原因
     */
    public static void failure(String key, Throwable error) {
        if (key == null || !shouldCooldown(error)) {
            return;
        }
        // 只有「限流 / 配额」类的失败才算限流事件；连不上、报文错等不触发限流策略
        GATE.fail(key, describe(error), !isUnreachable(error));
    }

    /**
     * 订阅某个前缀下的失败事件（如 {@code provider|model|}）。
     *
     * @param prefix  订阅前缀（冷却键以它开头即命中）
     * @param onLimit 回调，入参为完整冷却键；为 null 表示退订
     */
    public static void subscribe(String prefix, Consumer<String> onLimit) {
        GATE.subscribe(prefix, onLimit);
    }

    /**
     * 按键记一次成功：立即解除冷却。
     *
     * @param key 冷却键
     */
    public static void success(String key) {
        GATE.pass(key);
    }

    /**
     * 是否为「连不上上游」类失败。
     *
     * @param error 异常
     * @return true 表示地址不可达
     */
    private static boolean isUnreachable(Throwable error) {
        String message = describe(error).toLowerCase(Locale.ROOT);
        String type = rootType(error).toLowerCase(Locale.ROOT);
        for (String hint : UNREACHABLE_HINTS) {
            if (message.contains(hint) || type.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取根因异常的类名。
     *
     * @param error 异常
     * @return 类名；无异常返回空串
     */
    private static String rootType(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
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
}
