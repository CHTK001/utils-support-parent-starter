// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.aggregate.RouterGroupSetting.BusyPolicy;
import com.chua.common.support.ai.chat.aggregate.RouterGroupSetting.ContextPolicy;
import com.chua.common.support.ai.chat.aggregate.RouterGroupSetting.RateLimitPolicy;
import com.chua.common.support.ai.chat.aggregate.RouterGroupSetting.SlowPolicy;
import com.chua.common.support.ai.chat.aggregate.RouterGroupSetting.Strategy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 路由组对话客户端：一个「模型」背后是模型库里的一组成员模型，选谁由组内策略决定。
 *
 * <p><b>为什么要有它</b>：以前这些决定散落在调用方 —— 谁来收集成员、谁决定顺序、
 * 谁判断被限流、谁给每个成员套 effort、谁算上下文窗口。结果是每加一条策略就要改一次
 * 上游代码，而且不同入口各写一套，口径很快分叉。现在全部收进本类：</p>
 *
 * <ul>
 *   <li><b>成员</b>：来自「已加入的模型库」（{@link RouterGroupSetting.Member} 记录供应商 + 模型 + 顺序 +
 *       权重 + effort），成员被删时由上层把成员表交回来重建（{@link #setMembers}）；</li>
 *   <li><b>策略</b>：智能 / 按顺序 / 用完再换 / 轮流 / 最少使用 / 到期前用完 / 免费优先 /
 *       速度优先 / 最便宜优先 / 免费额度先到先用 / 按权重 / 手动；</li>
 *   <li><b>兜底</b>：被限流时（默认 / 保持原位 / 排到最后）、延迟不出字（默认 / 一直等 /
 *       30 秒 / 1 分钟 / 2 分钟 / 自定义）、成员全都忙（默认 / 关闭 / 1/2/4 分钟）、
 *       上下文（最大模型的 / 最小模型的 / 自定义）；</li>
 *   <li><b>effort</b>：每个成员各自的思考强度，只作用于该成员的请求；会话层不支持 effort
 *       覆盖（{@link #thinkingEffort(String)} 是空操作），以免整组被同一档位绑死。</li>
 * </ul>
 *
 * <p>判「被限流」时不自己造口径：{@link ClientCooldownRegistry} 已经按
 * 请求级错误（400 类）与成员级错误（401/403/429/余额/网络）分好了类，这里只订阅它。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class RouterGroupChatClient extends SwitchingChatClient {

    /**
     * 智能策略中「很强」的模型智慧分阈值
     */
    private static final double SMART_HIGH_IQ = 60D;

    /**
     * 智能策略中「很便宜」的相对价格系数
     */
    private static final double SMART_CHEAP_RATIO = 0.35D;

    /**
     * 智能策略中「长提示词」的默认字符数
     */
    private static final int DEFAULT_LONG_PROMPT_CHARS = 4000;

    /**
     * 没有延迟数据时的默认 TTFT（毫秒）
     */
    private static final double DEFAULT_TTFT_MS = 1500D;

    /**
     * 组配置
     */
    private final RouterGroupSetting groupConfig;

    /**
     * 组标识（日志与冷却键前缀）
     */
    private final String groupId;

    /**
     * 冷却键前缀：{@code group:<groupId>|}
     */
    private final String cooldownPrefix;

    /**
     * 成员业务信息（键为标签）：effort / 免费 / 到期 / 上下文 / 价格 / 智慧分 / TTFT
     */
    private final Map<String, MemberFacts> facts = new HashMap<>();

    /**
     * 轮换游标
     */
    private final AtomicInteger cursor = new AtomicInteger();

    /**
     * 「用完再换」记录当前在用的成员
     */
    private final AtomicInteger currentIndex = new AtomicInteger();

    /**
     * 手动策略下用户选中的成员标签
     */
    private volatile String manualLabel;

    /**
     * 成员列表变更监听
     */
    private volatile Runnable memberListener;

    /**
     * 构造。
     *
     * @param groupId 组标识（供应商 ID）
     * @param groupConfig 组配置
     */
    public RouterGroupChatClient(String groupId, RouterGroupSetting groupConfig) {
        this.groupId = groupId == null ? "" : groupId;
        this.groupConfig = groupConfig == null ? new RouterGroupSetting() : groupConfig;
        this.cooldownPrefix = "group:" + this.groupId + "|";
        reloadMembers();
        ClientCooldownRegistry.subscribe(cooldownPrefix, this::onRateLimited);
    }

    /**
     * 创建并装载成员。
     *
     * <p>成员的客户端由调用方按「成员模型 → 真实供应商客户端」构造好后交进来，
     * 本类不关心 Key 从哪里来（那是 {@link MultiKeyChatClient} 的事）。</p>
     *
     * @param groupId 组标识
     * @param groupConfig 组配置
     * @param factory 成员 → 客户端；返回 null 的成员被忽略
     * @return 客户端
     */
    public static RouterGroupChatClient create(String groupId, RouterGroupSetting groupConfig,
                                               Function<RouterGroupSetting.Member, ChatClient> factory) {
        RouterGroupChatClient client = new RouterGroupChatClient(groupId, groupConfig);
        client.loadMembers(groupConfig == null ? List.of() : groupConfig.getMembers(), factory);
        return client;
    }

    /**
     * 装载成员（保留 {@link #setMembers} 的语义：从零重建）。
     *
     * @param members 成员（顺序即「按顺序」策略的优先顺序）
     * @param factory 成员 → 客户端
     * @return 当前实例，支持链式调用
     */
    public RouterGroupChatClient loadMembers(List<RouterGroupSetting.Member> members,
                                             Function<RouterGroupSetting.Member, ChatClient> factory) {
        List<RouterGroupSetting.Member> list = new ArrayList<>();
        Map<String, MemberFacts> nextFacts = new HashMap<>();
        if (members != null) {
            for (RouterGroupSetting.Member m : members) {
                if (m == null || m.providerId() == null || m.model() == null) {
                    continue;
                }
                list.add(m);
                nextFacts.put(m.label(), existedFacts(m.label()));
            }
        }
        this.groupConfig.setMembers(list);
        facts.clear();
        facts.putAll(nextFacts);
        reloadMembers(factory);
        return this;
    }

    /**
     * 替换成员表（模型库里成员被删、顺序被调整时调用，保留各自的运行时统计）。
     *
     * @param members 新成员表；null 视为清空
     * @param factory 成员 → 客户端
     */
    public void setMembers(List<RouterGroupSetting.Member> members,
                           Function<RouterGroupSetting.Member, ChatClient> factory) {
        loadMembers(members, factory);
    }

    /**
     * 只调整顺序，不重建客户端（前端拖拽排序后调用）。
     *
     * @param orderedLabels 新顺序（{@code providerId:model}）
     */
    public void reorder(List<String> orderedLabels) {
        if (orderedLabels == null || orderedLabels.isEmpty()) {
            return;
        }
        Map<String, RouterGroupSetting.Member> byLabel = new HashMap<>();
        for (RouterGroupSetting.Member m : groupConfig.getMembers()) {
            byLabel.put(m.label(), m);
        }
        List<RouterGroupSetting.Member> ordered = new ArrayList<>();
        for (String label : orderedLabels) {
            RouterGroupSetting.Member m = byLabel.remove(label);
            if (m != null) {
                ordered.add(m);
            }
        }
        // 未出现在新顺序里的成员保持原有相对顺序追加在后面，不丢
        for (RouterGroupSetting.Member m : groupConfig.getMembers()) {
            if (byLabel.containsKey(m.label())) {
                ordered.add(m);
            }
        }
        groupConfig.setMembers(ordered);
        reloadMembers(null);
        currentIndex.set(0);
        log.info("[RouterGroup] 调整顺序 group={} 成员={}", groupId, orderedLabels);
    }

    /**
     * 设置手动策略下选中的成员。
     *
     * @param label 成员标签（{@code providerId:model}）
     */
    public void selectManual(String label) {
        this.manualLabel = label;
        log.info("[RouterGroup] 手动指定成员 group={} member={}", groupId, label);
    }

    /**
     * 注册成员表变更监听（成员被限流移出时通知上层持久化）。
     *
     * @param listener 监听
     */
    public void onMembersChanged(Runnable listener) {
        this.memberListener = listener;
    }

    /**
     * 组配置。
     *
     * @return 配置
     */
    public RouterGroupSetting getGroupConfig() {
        return groupConfig;
    }

    /**
     * 组标识
     *
     * @return 供应商 ID
     */
    public String getGroupId() {
        return groupId;
    }

    // ======================== 上下文 ========================

    /**
     * 组上下文窗口：按策略取最大 / 最小 / 自定义。
     *
     * @return 上下文 token 数；全组都没有配置时返回 0
     */
    public int contextWindow() {
        ContextPolicy policy = groupConfig.getOnContext() == null ? ContextPolicy.MAX : groupConfig.getOnContext();
        if (policy == ContextPolicy.CUSTOM) {
            return groupConfig.getContextWindow() == null ? 0 : groupConfig.getContextWindow();
        }
        int result = 0;
        boolean first = true;
        for (SwitchingMember m : members()) {
            MemberFacts f = facts.get(m.label());
            int window = f == null ? 0 : f.contextWindow;
            if (window <= 0) {
                continue;
            }
            if (first) {
                result = window;
                first = false;
            } else {
                result = policy == ContextPolicy.MIN ? Math.min(result, window) : Math.max(result, window);
            }
        }
        return result;
    }

    // ======================== 策略 ========================

    @Override
    protected String kind() {
        return "RouterGroup";
    }

    @Override
    protected List<SwitchingMember> available(List<SwitchingMember> all) {
        List<SwitchingMember> healthy = super.available(all);
        List<SwitchingMember> result = new ArrayList<>(healthy.size());
        for (SwitchingMember m : healthy) {
            if (!ClientCooldownRegistry.isCooling(cooldownKey(m))) {
                result.add(m);
            }
        }
        if (result.isEmpty()) {
            // 全都冷却：默认策略下短暂等待（成员全都忙），等不到就半开放行，避免整组不可用
            long waitMs = busyWaitMillis();
            if (waitMs > 0 && !healthy.isEmpty()) {
                boolean recovered = awaitRecovery(healthy, waitMs);
                if (recovered) {
                    for (SwitchingMember m : healthy) {
                        if (!ClientCooldownRegistry.isCooling(cooldownKey(m))) {
                            result.add(m);
                        }
                    }
                }
            }
            if (result.isEmpty()) {
                log.warn("[RouterGroup] 全部成员冷却中，半开放行 group={} 成员数={}", groupId, healthy.size());
                result.addAll(healthy);
            }
        }
        if (groupConfig.getStrategy() == Strategy.MANUAL) {
            return manualFilter(result);
        }
        return result;
    }

    /**
     * 手动策略：只留选中的那一个。
     *
     * @param candidates 候选
     * @return 过滤后的候选
     */
    private List<SwitchingMember> manualFilter(List<SwitchingMember> candidates) {
        String label = manualLabel;
        if (label == null || label.isBlank()) {
            return candidates;
        }
        for (SwitchingMember m : candidates) {
            if (label.equals(m.label())) {
                return List.of(m);
            }
        }
        // 选中的成员在冷却：退回全部候选，由调用方决定是否等待
        return candidates;
    }

    /**
     * 「成员全都忙」的最长等待。
     *
     * @return 毫秒；0 表示不等待
     */
    private long busyWaitMillis() {
        BusyPolicy policy = groupConfig.getOnBusy() == null ? BusyPolicy.DEFAULT : groupConfig.getOnBusy();
        if (policy == BusyPolicy.DEFAULT || policy == BusyPolicy.OFF) {
            return 0L;
        }
        return policy.millis();
    }

    /**
     * 轮询等待有成员恢复可用。
     *
     * @param candidates 候选
     * @param maxMs      最长等待
     * @return true 表示至少有一个恢复
     */
    private boolean awaitRecovery(List<SwitchingMember> candidates, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            for (SwitchingMember m : candidates) {
                if (!ClientCooldownRegistry.isCooling(cooldownKey(m))) {
                    return true;
                }
            }
            try {
                Thread.sleep(MEMBER_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    protected SwitchingMember select(List<SwitchingMember> candidates, String prompt, int attempt) {
        if (candidates.isEmpty()) {
            return null;
        }
        Strategy strategy = groupConfig.getStrategy() == null ? Strategy.SMART : groupConfig.getStrategy();
        List<SwitchingMember> sorted = new ArrayList<>(candidates);
        switch (strategy) {
            case ORDER -> {
                return sorted.get(0);
            }
            case ROUND_ROBIN -> {
                if (attempt == 0) {
                    return sorted.get(Math.floorMod(cursor.getAndIncrement(), sorted.size()));
                }
                return sorted.get(0);
            }
            case UNTIL_EXHAUSTED -> {
                return untilExhausted(sorted);
            }
            case LEAST_USED -> sorted.sort(Comparator.comparingInt((SwitchingMember m) -> m.used().get())
                    .thenComparingLong(m -> -recentSuccess(m)));
            case EXPIRE_FIRST -> sorted.sort(Comparator.comparingLong(m -> expireAt(m)));
            case FREE_FIRST -> sorted.sort(Comparator.comparingLong((SwitchingMember m) -> isFree(m) ? 0 : 1)
                    .thenComparingLong(m -> expireAt(m)));
            case FREE_QUOTA_FIRST -> sorted.sort(Comparator.comparingLong((SwitchingMember m) -> {
                boolean free = isFree(m);
                long expire = expireAt(m);
                return free ? expire : Long.MAX_VALUE;
            }));
            case SPEED_FIRST -> sorted.sort(Comparator.comparingDouble(this::ttft));
            case CHEAPEST_FIRST -> sorted.sort(Comparator.comparingDouble(this::price));
            case WEIGHTED -> {
                return weighted(sorted);
            }
            case MANUAL -> {
                return sorted.get(0);
            }
            case SMART -> {
                return smart(sorted, prompt);
            }
            default -> {
                return sorted.get(0);
            }
        }
        return sorted.get(0);
    }

    /**
     * 用完再换：固定一个成员，直到它不可用。
     *
     * @param candidates 当前候选（已排除冷却中的）
     * @return 选中的成员
     */
    private SwitchingMember untilExhausted(List<SwitchingMember> candidates) {
        int index = currentIndex.get();
        SwitchingMember chosen = null;
        if (index >= 0 && index < candidates.size()) {
            chosen = candidates.get(index);
            if (!ClientCooldownRegistry.isCooling(cooldownKey(chosen))) {
                return chosen;
            }
        }
        SwitchingMember first = candidates.get(0);
        currentIndex.set(0);
        return first;
    }

    /**
     * 智能策略：轻活给便宜的，重活给强的；长提示词优先上下文够大的。
     *
     * @param candidates 候选
     * @param prompt     用户输入
     * @return 选中的成员
     */
    private SwitchingMember smart(List<SwitchingMember> candidates, String prompt) {
        int length = prompt == null ? 0 : prompt.length();
        int longChars = groupConfig.getLongPromptChars() > 0 ? groupConfig.getLongPromptChars() : DEFAULT_LONG_PROMPT_CHARS;
        boolean longPrompt = length >= longChars;
        double cheapest = candidates.stream().mapToDouble(this::price).min().orElse(0D);
        SwitchingMember best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (SwitchingMember m : candidates) {
            MemberFacts f = facts.get(m.label());
            int window = f == null ? 0 : f.contextWindow;
            if (longPrompt && window > 0 && window < length) {
                // 上下文装不下整个提示词的成员直接跳过，避免必然失败的一次往返
                continue;
            }
            double iq = f == null ? 0D : f.intelligence;
            double price = price(m);
            double ttft = ttft(m);
            double score = iq / SMART_HIGH_IQ;
            if (cheapest > 0 && price <= cheapest * SMART_CHEAP_RATIO) {
                score += 0.5D;
            }
            // 轻活降权智慧；重活提高智慧权重
            double weight = longPrompt ? 1.0D : 0.6D;
            score = score * weight + Math.max(0D, 1D - ttft / (2 * DEFAULT_TTFT_MS)) * 0.4D;
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best != null ? best : candidates.get(0);
    }

    /**
     * 按权重随机。
     *
     * @param candidates 候选
     * @return 选中的成员
     */
    private SwitchingMember weighted(List<SwitchingMember> candidates) {
        int total = 0;
        for (SwitchingMember m : candidates) {
            total += Math.max(1, m.weight());
        }
        int point = (int) (System.nanoTime() & 0x7FFFFFFF) % total;
        for (SwitchingMember m : candidates) {
            point -= Math.max(1, m.weight());
            if (point < 0) {
                return m;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    @Override
    protected void onSuccess(SwitchingMember member, String prompt, long costMs) {
        ClientCooldownRegistry.success(cooldownKey(member));
        // 实时视图：这一轮真的被派给了它，并且成功
        RouterGroupActivity.record(groupId, member.label(), modelOf(member.label()), true, costMs);
        MemberFacts f = facts.computeIfAbsent(member.label(), k -> new MemberFacts());
        f.lastSuccess = System.currentTimeMillis();
        f.successCount++;
        // TTFT 只有流式能测到；同步调用退而记录整次耗时，作为「速度优先」的参考
        f.totalTtftMs += costMs;
        f.ttftSamples++;
    }

    @Override
    protected void onFailure(SwitchingMember member, String prompt, Throwable error) {
        ClientCooldownRegistry.failure(cooldownKey(member), error);
        // 实时视图：这一轮派给了它但失败（下一步会换成员）
        RouterGroupActivity.record(groupId, member.label(), modelOf(member.label()), false, 0L);
        MemberFacts f = facts.computeIfAbsent(member.label(), k -> new MemberFacts());
        f.failureCount++;
    }

    /**
     * 从成员标识 {@code providerId:model} 里取模型名（展示用）。
     *
     * @param label 成员标识
     * @return 模型名；没有分隔符时原样返回
     */
    private static String modelOf(String label) {
        if (label == null) {
            return "";
        }
        int idx = label.indexOf(':');
        return idx >= 0 && idx + 1 < label.length() ? label.substring(idx + 1) : label;
    }

    /**
     * 被限流（进入冷却）时按「被限流时」策略处理。
     *
     * @param key 冷却键
     */
    private void onRateLimited(String key) {
        RateLimitPolicy policy = groupConfig.getOnRateLimit() == null ? RateLimitPolicy.DEFAULT : groupConfig.getOnRateLimit();
        String label = key.startsWith(cooldownPrefix) ? key.substring(cooldownPrefix.length()) : key;
        if (!policy.movesToEnd()) {
            return;
        }
        // 排到最后：调整内存中的顺序，并通知上层持久化，重启后顺序仍生效
        List<SwitchingMember> current = new ArrayList<>(members());
        SwitchingMember target = null;
        for (SwitchingMember m : current) {
            if (m.label().equals(label)) {
                target = m;
                break;
            }
        }
        if (target == null) {
            return;
        }
        current.remove(target);
        current.add(target);
        resetMembers(current);
        List<String> ordered = current.stream().map(SwitchingMember::label).toList();
        groupConfig.setMembers(current.stream().map(this::toGroupMember).toList());
        log.info("[RouterGroup] 被限流，成员排到最后 group={} member={} 新顺序={}", groupId, label, ordered);
        Runnable listener = memberListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Exception e) {
                log.debug("[RouterGroup] 成员顺序持久化失败: {}", e.getMessage());
            }
        }
    }

    // ======================== 成员构造 ========================

    /**
     * 按配置重建成员客户端。
     */
    private void reloadMembers() {
        reloadMembers(null);
    }

    /**
     * 按配置重建成员客户端。
     *
     * @param factory 成员 → 客户端；null 时保留已有客户端（只重建顺序）
     */
    private void reloadMembers(Function<RouterGroupSetting.Member, ChatClient> factory) {
        List<RouterGroupSetting.Member> configured =
                groupConfig.getMembers() == null ? List.of() : groupConfig.getMembers();
        Map<String, ChatClient> existing = new HashMap<>();
        for (SwitchingMember m : members()) {
            existing.put(m.label(), m.client());
        }
        List<SwitchingMember> rebuilt = new ArrayList<>(configured.size());
        for (RouterGroupSetting.Member m : configured) {
            if (!m.enabled()) {
                continue;
            }
            ChatClient client = factory == null ? null : existing.get(m.label());
            if (client == null && factory != null) {
                try {
                    client = factory.apply(m);
                } catch (Exception e) {
                    log.warn("[RouterGroup] 构造成员失败 {}/{}: {}", groupId, m.label(), e.getMessage());
                    continue;
                }
            }
            if (client == null) {
                continue;
            }
            // 逐成员 effort：成员自己带档位，会话层不再覆盖
            if (m.effort() != null && !m.effort().isBlank()) {
                try {
                    client.thinkingEffort(m.effort().trim());
                } catch (Exception e) {
                    log.debug("[RouterGroup] 设置成员 effort 失败 {}: {}", m.label(), e.getMessage());
                }
            }
            rebuilt.add(toSwitchingMember(m, client));
            facts.computeIfAbsent(m.label(), k -> new MemberFacts());
        }
        resetMembers(rebuilt);
        log.info("[RouterGroup] 成员就绪 group={} 策略={} 成员={}", groupId, groupConfig.getStrategy(),
                rebuilt.stream().map(SwitchingMember::label).toList());
    }

    /**
     * 构造成员（沿用已存在的客户端）。
     *
     * @param m 成员
     * @return 切换型成员
     */
    private SwitchingMember toSwitchingMember(RouterGroupSetting.Member m) {
        ChatClient existing = null;
        for (SwitchingMember member : members()) {
            if (member.label().equals(m.label())) {
                existing = member.client();
                break;
            }
        }
        return toSwitchingMember(m, existing);
    }

    /**
     * 构造成员。
     *
     * @param m      成员配置
     * @param client 客户端
     * @return 切换型成员
     */
    private SwitchingMember toSwitchingMember(RouterGroupSetting.Member m, ChatClient client) {
        AtomicInteger used = new AtomicInteger();
        for (SwitchingMember member : members()) {
            if (member.label().equals(m.label())) {
                used = member.used();
                break;
            }
        }
        return new SwitchingMember(m.label(), Math.max(1, m.weight()), client, used);
    }

    /**
     * 切换型成员 → 组配置成员（持久化用）。
     *
     * @param member 切换型成员
     * @return 组配置成员
     */
    private RouterGroupSetting.Member toGroupMember(SwitchingMember member) {
        for (RouterGroupSetting.Member m : groupConfig.getMembers()) {
            if (m.label().equals(member.label())) {
                return new RouterGroupSetting.Member(m.providerId(), m.model(), m.enabled(),
                        member.weight(), m.effort(), m.free(), m.expireAt());
            }
        }
        int split = member.label().indexOf(':');
        String providerId = split > 0 ? member.label().substring(0, split) : "";
        String model = split > 0 ? member.label().substring(split + 1) : member.label();
        return new RouterGroupSetting.Member(providerId, model, true, member.weight(), null, null, null);
    }

    // ======================== 成员指标 ========================

    /**
     * 读取成员指标（不存在时返回空指标）。
     *
     * @param label 成员标签
     * @return 指标
     */
    private MemberFacts existedFacts(String label) {
        MemberFacts f = facts.get(label);
        return f == null ? new MemberFacts() : f;
    }

    /**
     * 冷却键
     *
     * @param member 成员
     * @return 冷却键
     */
    private String cooldownKey(SwitchingMember member) {
        return cooldownPrefix + member.label();
    }

    /**
     * 成员是否免费
     *
     * @param member 成员
     * @return true 表示免费
     */
    private boolean isFree(SwitchingMember member) {
        RouterGroupSetting.Member m = settingMember(member);
        if (m != null && m.free() != null) {
            return m.free();
        }
        return price(member) <= 0D;
    }

    /**
     * 成员价格（每百万输入 token 的美元价）
     *
     * @param member 成员
     * @return 价格；未知为 0
     */
    private double price(SwitchingMember member) {
        MemberFacts f = facts.get(member.label());
        return f == null ? 0D : f.priceIn;
    }

    /**
     * 成员 TTFT（毫秒）
     *
     * @param member 成员
     * @return 首字延迟；无样本时取默认
     */
    private double ttft(SwitchingMember member) {
        MemberFacts f = facts.get(member.label());
        if (f == null || f.ttftSamples == 0) {
            return f == null || f.upstreamTtftMs <= 0 ? DEFAULT_TTFT_MS : f.upstreamTtftMs;
        }
        double measured = (double) f.totalTtftMs / f.ttftSamples;
        return f.upstreamTtftMs > 0 ? Math.min(measured, f.upstreamTtftMs) : measured;
    }

    /**
     * 成员的额度到期时间（无到期视为最晚）
     *
     * @param member 成员
     * @return 毫秒时间戳
     */
    private long expireAt(SwitchingMember member) {
        RouterGroupSetting.Member m = settingMember(member);
        return m == null || m.expireAt() == null ? Long.MAX_VALUE : m.expireAt();
    }

    /**
     * 最近一次成功时间
     *
     * @param member 成员
     * @return 毫秒时间戳；从未成功为 0
     */
    private long recentSuccess(SwitchingMember member) {
        MemberFacts f = facts.get(member.label());
        return f == null ? 0L : f.lastSuccess;
    }

    /**
     * 查找组配置成员
     *
     * @param member 切换型成员
     * @return 组配置成员；找不到返回 null
     */
    private RouterGroupSetting.Member settingMember(SwitchingMember member) {
        for (RouterGroupSetting.Member m : groupConfig.getMembers()) {
            if (m.label().equals(member.label())) {
                return m;
            }
        }
        return null;
    }

    /**
     * 登记成员指标（由上层从模型库行回填：价格 / 上下文 / 智慧分 / 上游 TTFT）。
     *
     * @param label        成员标签
     * @param contextWindow 上下文窗口
     * @param priceIn      每百万输入 token 价格
     * @param intelligence 智慧分
     * @param ttftMs       上游实测首字延迟
     */
    public void describeMember(String label, int contextWindow, double priceIn, double intelligence, long ttftMs) {
        MemberFacts f = facts.computeIfAbsent(label, k -> new MemberFacts());
        f.contextWindow = contextWindow;
        f.priceIn = priceIn;
        f.intelligence = intelligence;
        f.upstreamTtftMs = ttftMs;
    }

    /**
     * 读取成员模型清单（供前端展示组成员与顺序）。
     *
     * @return {@link ModelDefinition} 列表
     */
    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> result = new ArrayList<>();
        for (SwitchingMember m : members()) {
            ModelDefinition def = ModelDefinition.builder()
                    .id(m.label())
                    .name(m.label())
                    .provider(groupId)
                    .build();
            result.add(def);
        }
        return result;
    }

    /**
     * 会话层不支持 effort：路由组的档位由每个成员自己配置，这里不做广播。
     *
     * @param effort 会话层传入的档位（忽略）
     * @return 当前实例
     */
    @Override
    public ChatClient thinkingEffort(String effort) {
        return this;
    }

    /**
     * 组名不能下发给成员：每个成员各自绑定了模型（{@code provider:model} 里的 model），
     * 请求里的模型是「组的名字」，广播下去成员会带着组名请求上游 → 400。
     *
     * @return false，表示不广播模型名
     */
    @Override
    protected boolean propagateModel() {
        return false;
    }

    @Override
    public void close() {
        ClientCooldownRegistry.subscribe(cooldownPrefix, null);
        super.close();
    }

    /**
     * 成员成员恢复检查的轮询间隔（毫秒）
     */
    private static final long MEMBER_POLL_MS = 500L;

    /**
     * 成员运行时指标。
     */
    private static final class MemberFacts {
        /**
         * 上下文窗口
         */
        private int contextWindow;
        /**
         * 每百万输入 token 价格
         */
        private double priceIn;
        /**
         * 智慧分
         */
        private double intelligence;
        /**
         * 上游实测首字延迟
         */
        private long upstreamTtftMs;
        /**
         * 本进程累计首字延迟
         */
        private long totalTtftMs;
        /**
         * 首字延迟样本数
         */
        private int ttftSamples;
        /**
         * 成功次数
         */
        private int successCount;
        /**
         * 失败次数
         */
        private int failureCount;
        /**
         * 最近成功时间
         */
        private long lastSuccess;
    }
}
