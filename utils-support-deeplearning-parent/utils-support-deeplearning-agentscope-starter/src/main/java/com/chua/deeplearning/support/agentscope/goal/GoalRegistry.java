package com.chua.deeplearning.support.agentscope.goal;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级目标注册表：把「会话 → 目标状态机」的映射集中管理。
 *
 * <p>为什么需要它：{@code AgentScopeAgent} 是<b>每请求新建</b>的（facade 每次请求建一个），
 * 而一个目标可能横跨多次请求、被多次暂停/恢复。因此目标状态不能挂在 agent 实例上，
 * 必须有一个比请求存活更久的容器；本类就是那个容器。
 *
 * <p><b>有界</b>：容量上限 {@code maxGoals}。达到上限时先淘汰已进入终态的目标；
 * 若仍然占满（说明有大量目标既不结束也不清理），则拒绝新建并抛异常——
 * 静默丢弃一个正在运行的目标，比拒绝新建严重得多。
 *
 * <p>线程安全：底层用 {@link ConcurrentHashMap}，状态迁移由 {@link GoalTracker} 自身串行化。
 *
 * @author CH
 */
public final class GoalRegistry {

    /**
     * 进程内共享实例（后端可直接使用）。
     */
    private static final GoalRegistry SHARED = new GoalRegistry(500);

    private final Map<String, GoalTracker> trackers = new ConcurrentHashMap<>();

    private final int maxGoals;

    /**
     * 使用默认容量。
     */
    public GoalRegistry() {
        this(500);
    }

    /**
     * @param maxGoals 容量上限，至少 1
     */
    public GoalRegistry(int maxGoals) {
        if (maxGoals < 1) {
            throw new IllegalArgumentException("maxGoals 至少为 1: " + maxGoals);
        }
        this.maxGoals = maxGoals;
    }

    /**
     * 进程内共享实例。
     *
     * @return 共享注册表
     */
    public static GoalRegistry shared() {
        return SHARED;
    }

    /**
     * 绑定（或覆盖）一个会话的目标。
     *
     * @param goal 目标快照，不能为 null
     * @return 该会话的目标状态机
     */
    public GoalTracker bind(SessionGoal goal) {
        Objects.requireNonNull(goal, "goal 不能为 null");
        evictIfNeeded(goal.sessionId());
        GoalTracker tracker = new GoalTracker(goal);
        trackers.put(goal.sessionId(), tracker);
        return tracker;
    }

    /**
     * 注册一个已有状态机（例如由上层自行构造的场景）。
     *
     * @param sessionId 会话标识
     * @param tracker   状态机，不能为 null
     * @return 注册进去的状态机
     */
    public GoalTracker bind(String sessionId, GoalTracker tracker) {
        Objects.requireNonNull(sessionId, "sessionId 不能为 null");
        Objects.requireNonNull(tracker, "tracker 不能为 null");
        evictIfNeeded(sessionId);
        trackers.put(sessionId, tracker);
        return tracker;
    }

    /**
     * 查找会话的目标状态机。
     *
     * @param sessionId 会话标识
     * @return 状态机（可能为空）
     */
    public Optional<GoalTracker> find(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(trackers.get(sessionId));
    }

    /**
     * 会话当前目标状态。
     *
     * @param sessionId 会话标识
     * @return 展示投影；无目标时返回 null
     */
    public GoalStatus status(String sessionId) {
        return find(sessionId).map(GoalTracker::status).orElse(null);
    }

    /**
     * 暂停会话目标。
     *
     * @param sessionId 会话标识
     * @return 暂停后的展示投影
     */
    public GoalStatus pause(String sessionId, long now) {
        return require(sessionId).pause(now);
    }

    /**
     * 恢复会话目标。
     *
     * @param sessionId 会话标识
     * @return 恢复后的展示投影
     */
    public GoalStatus resume(String sessionId, long now) {
        return require(sessionId).resume(now);
    }

    /**
     * 取消会话目标。
     *
     * @param sessionId 会话标识
     * @param reason    取消原因
     * @param now       当前时间（毫秒时间戳）
     * @return 取消后的展示投影
     */
    public GoalStatus cancel(String sessionId, String reason, long now) {
        return require(sessionId).cancel(reason, now);
    }

    /**
     * 移除会话目标（终态清理）。
     *
     * @param sessionId 会话标识
     * @return 被移除的展示投影；不存在时返回 null
     */
    public GoalStatus remove(String sessionId) {
        GoalTracker removed = trackers.remove(sessionId);
        return removed == null ? null : removed.status();
    }

    /**
     * 当前登记的目标数。
     *
     * @return 数量
     */
    public int size() {
        return trackers.size();
    }

    /**
     * 清空（测试用）。
     */
    public void clear() {
        trackers.clear();
    }

    /**
     * 取会话的目标状态机，不存在则抛异常。
     *
     * @param sessionId 会话标识
     * @return 状态机
     */
    private GoalTracker require(String sessionId) {
        return find(sessionId).orElseThrow(
                () -> new IllegalStateException("该会话没有目标: " + sessionId));
    }

    /**
     * 容量保护：达到上限时先淘汰终态目标；若仍占满则拒绝并抛异常。
     *
     * @param incomingSession 即将写入的会话标识（同会话覆盖时不占用新容量）
     */
    private void evictIfNeeded(String incomingSession) {
        if (trackers.containsKey(incomingSession) || trackers.size() < maxGoals) {
            return;
        }
        trackers.entrySet().removeIf(entry -> entry.getValue().status().state().terminal());
        if (trackers.size() < maxGoals) {
            return;
        }
        Map.Entry<String, GoalTracker> oldest = trackers.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().status().updatedAt()))
                .orElse(null);
        throw new IllegalStateException("目标容量已达上限 " + maxGoals
                + "，请先结束或移除旧目标（最旧的是 " + (oldest == null ? "无" : oldest.getKey()) + "）");
    }
}
