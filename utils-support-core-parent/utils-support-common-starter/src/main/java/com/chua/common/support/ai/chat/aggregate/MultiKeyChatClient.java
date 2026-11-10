// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.chat.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多 Key 对话客户端：同一个供应商、同一个模型、多个 API Key，Key 的维护全在本类内部。
 *
 * <p><b>为什么要它</b>：调用方（{@code ModelClientResolver}）现在自己收集 Key、
 * 自己判断有没有 Key、自己决定「开关关了就用第一个」、自己组装聚合。这些都属于
 * 「渠道怎么用它的 Key」，是客户端该管的事。本类把这段逻辑收进来：</p>
 *
 * <ul>
 *   <li>{@link #multiKey}：静态工厂，Key 列表为空时返回 {@code null}（唯一「没得用」的信号）；</li>
 *   <li>{@link Strategy}：{@code failover}（按顺序）、{@code round_robin}（轮换）、
 *       {@code least_used}（最少使用）；</li>
 *   <li>失败即换 Key：401/403/429/余额/网络错误把该 Key 记入
 *       {@link ClientCooldownRegistry} 冷却（60 秒起、连续失败翻倍、封顶 10 分钟），
 *       成功即解除；全部冷却时按半开放行，避免整组不可用；</li>
 *   <li>配置（model / history / tools / thinkingEffort …）广播给每个 Key 的客户端，
 *       调用方拿到的行为与单 Key 客户端完全一致。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class MultiKeyChatClient extends SwitchingChatClient {

    /**
     * 选 Key 的策略
     */
    public enum Strategy {
        /**
         * 按顺序：总是先试第一个，失败才换下一个（同账号优先，计费口径最稳定）
         */
        FAILOVER("failover"),
        /**
         * 轮换：每个请求换一个 Key，把配额摊平
         */
        ROUND_ROBIN("round_robin"),
        /**
         * 最少使用：优先给用得最少的 Key，失败重试也按此排序
         */
        LEAST_USED("least_used");

        /**
         * 配置里的写法
         */
        private final String code;

        Strategy(String code) {
            this.code = code;
        }

        /**
         * 配置字符串 → 枚举；未知值回落到 {@link #FAILOVER}。
         *
         * @param code 配置值（failover / round_robin / least_used）
         * @return 策略
         */
        public static Strategy of(String code) {
            if (code == null || code.isBlank()) {
                return FAILOVER;
            }
            String value = code.trim().toLowerCase();
            for (Strategy s : values()) {
                if (s.code.equals(value) || s.name().equalsIgnoreCase(value)) {
                    return s;
                }
            }
            return FAILOVER;
        }
    }

    /**
     * 是否允许在多 Key 之间自动切换；false 时只用第一个 Key
     */
    private final boolean autoSwitch;

    /**
     * 选 Key 策略
     */
    private final Strategy strategy;

    /**
     * 轮换游标
     */
    private final AtomicInteger cursor = new AtomicInteger();

    /**
     * 冷却键前缀：provider|model|key
     */
    private final String cooldownPrefix;

    /**
     * 每个 Key 的最近成功时间（毫秒）
     */
    private final Map<String, AtomicLong> lastSuccess = new ConcurrentHashMap<>();

    /**
     * 构造（请优先用 {@link #multiKey}）。
     *
     * @param cooldownPrefix 冷却键前缀（{@code provider|model}）
     * @param strategy       选 Key 策略
     * @param autoSwitch     是否允许自动切换
     */
    protected MultiKeyChatClient(String cooldownPrefix, Strategy strategy, boolean autoSwitch) {
        this.cooldownPrefix = cooldownPrefix == null ? "" : cooldownPrefix;
        this.strategy = strategy == null ? Strategy.FAILOVER : strategy;
        this.autoSwitch = autoSwitch;
    }

    /**
     * 创建一个多 Key 客户端。
     *
     * <p>调用方只管「把每个 Key 对应的客户端给我」——Key 的收集、去重、
     * 开关与冷却都由本类处理，因此 Key 列表为空时按「没得用」返回 null。</p>
     *
     * @param provider       供应商标识（日志与冷却键）
     * @param model          裸模型名（冷却键；可空）
     * @param strategy       选 Key 策略（可空，默认按顺序）
     * @param autoSwitch     是否允许自动切换（供应商的「自动限流」开关）
     * @param keyedClients   每个 Key → 已绑定模型的客户端（可空元素：免费渠道允许无 Key）
     * @return 客户端；没有任何可用 Key 时返回 {@code null}
     */
    public static MultiKeyChatClient multiKey(String provider, String model, Strategy strategy,
                                              boolean autoSwitch, Map<String, ChatClient> keyedClients) {
        if (keyedClients == null || keyedClients.isEmpty()) {
            return null;
        }
        String prefix = (provider == null ? "" : provider) + "|" + (model == null ? "" : model);
        MultiKeyChatClient client = new MultiKeyChatClient(prefix, strategy, autoSwitch);
        for (Map.Entry<String, ChatClient> entry : keyedClients.entrySet()) {
            client.addMember(maskKey(entry.getKey()), 1, entry.getValue());
        }
        if (client.isEmpty()) {
            return null;
        }
        return client;
    }

    /**
     * Key 在日志与冷却键里的写法：只留末 4 位，避免整串 Key 落进日志。
     *
     * @param key 原始 Key
     * @return 掩码后的标签
     */
    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "keyless";
        }
        String trimmed = key.trim();
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * 冷却键：provider|model|key 摘要
     *
     * @param member 成员
     * @return 冷却键
     */
    private String cooldownKey(SwitchingMember member) {
        return cooldownPrefix + "|" + member.label();
    }

    @Override
    protected String kind() {
        return "MultiKey";
    }

    @Override
    protected List<SwitchingMember> available(List<SwitchingMember> all) {
        List<SwitchingMember> result = super.available(all);
        if (!autoSwitch && result.size() > 1) {
            // 开关关闭：固定第一个 Key，不横向切换账号
            return new ArrayList<>(result.subList(0, 1));
        }
        if (result.size() <= 1) {
            return result;
        }
        // 冷却中的 Key 先避开；全都冷却时按半开放行（返回全部，交给调用顺序重试）
        List<SwitchingMember> healthy = new ArrayList<>(result.size());
        for (SwitchingMember m : result) {
            if (!ClientCooldownRegistry.isCooling(cooldownKey(m))) {
                healthy.add(m);
            }
        }
        return healthy.isEmpty() ? result : healthy;
    }

    @Override
    protected SwitchingMember select(List<SwitchingMember> candidates, String prompt, int attempt) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (strategy == Strategy.ROUND_ROBIN && attempt == 0) {
            return candidates.get(Math.floorMod(cursor.getAndIncrement(), candidates.size()));
        }
        if (strategy == Strategy.LEAST_USED) {
            SwitchingMember best = null;
            for (SwitchingMember m : candidates) {
                if (best == null
                        || m.used().get() < best.used().get()
                        || (m.used().get() == best.used().get() && recent(best) < recent(m))) {
                    best = m;
                }
            }
            return best;
        }
        // 按顺序（failover）：选剩下里的第一个；重试时依次往后
        return candidates.get(0);
    }

    /**
     * 成员的最近成功时间
     *
     * @param member 成员
     * @return 毫秒时间戳；从未成功为 0
     */
    private long recent(SwitchingMember member) {
        AtomicLong stamp = lastSuccess.get(member.label());
        return stamp == null ? 0L : stamp.get();
    }

    @Override
    protected void onSuccess(SwitchingMember member, String prompt, long costMs) {
        lastSuccess.computeIfAbsent(member.label(), k -> new AtomicLong()).set(System.currentTimeMillis());
        ClientCooldownRegistry.success(cooldownKey(member));
    }

    @Override
    protected void onFailure(SwitchingMember member, String prompt, Throwable error) {
        // 传 error：400/参数错误与「连不上」不应被当成限流事件（见 ClientCooldownRegistry）
        ClientCooldownRegistry.failure(cooldownKey(member), error);
    }
}
