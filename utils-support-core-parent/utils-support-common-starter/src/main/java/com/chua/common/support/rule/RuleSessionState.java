package com.chua.common.support.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则会话的内部状态对象。
 *
 * <p>把原先散落在 {@link RuleSession} 里的<strong>可变数据</strong>集中到一个对象中管理，
 * 使 {@code RuleSession} 只承担「编排」职责：议程推进、激活解析、事件发布。
 * 本类不感知规则语义，也不发布事件；事件由 {@code RuleSession} 在状态变更后发布，
 * 以保持「状态变更」与「事件通知」的先后顺序与原实现一致。</p>
 *
 * <p><b>线程安全约定：</b>工作内存（事实集合、类型索引、版本表）在 {@link #memoryLock}
 * 上同步；结果与计数器为会话内单线程使用，读方法不做同步，
 * 与重构前 {@code RuleSession} 的并发语义保持一致。</p>
 *
 * <p>本类为包内实现细节，<b>不属于对外 API</b>，不承诺向后兼容。</p>
 *
 * @author chua
 */
final class RuleSessionState {

    /**
     * 工作内存锁，保护事实集合、类型索引与版本表。
     */
    private final Object memoryLock = new Object();

    /**
     * 工作内存，保留插入顺序。
     */
    private final List<Object> facts = new ArrayList<>();

    /**
     * 事实类型索引，避免模式匹配时全量扫描。
     */
    private final Map<Class<?>, List<Object>> factIndex = new HashMap<>();

    /**
     * 事实版本表，按对象同一性记录。
     *
     * <p>用于实现 Drools 的 refraction（折射）语义：
     * 同一「规则 + 事实绑定元组」在事实未发生变化时只触发一次；
     * 事实被修改后版本递增，签名随之改变，规则才可再次触发。</p>
     */
    private final Map<Object, Integer> factVersions = new IdentityHashMap<>();

    /**
     * 主结果。
     */
    private Object result;

    /**
     * 结果条目。
     */
    private final Map<String, Object> entries = new LinkedHashMap<>();

    /**
     * 列表结果。
     */
    private final Map<String, List<Object>> lists = new LinkedHashMap<>();

    /**
     * 累计触发规则数。
     */
    private int firedCount;

    /**
     * 累计规则失败数。
     */
    private int failedCount;

    /**
     * 累计推理轮数。
     */
    private int cycleCount;

    /**
     * 是否已关闭。
     */
    private boolean closed;

    // ==================== 工作内存 ====================

    /**
     * 插入事实到工作内存。
     *
     * @param fact 待插入事实，非 null
     */
    void insertFact(Object fact) {
        synchronized (memoryLock) {
            facts.add(fact);
            factIndex.computeIfAbsent(fact.getClass(), ignored -> new ArrayList<>()).add(fact);
        }
    }

    /**
     * 按对象同一性从工作内存撤销事实。
     *
     * @param fact 待撤销事实
     * @return 撤销成功返回 true
     */
    boolean retractFact(Object fact) {
        synchronized (memoryLock) {
            boolean removed = false;
            for (int i = facts.size() - 1; i >= 0; i--) {
                if (facts.get(i) == fact) {
                    facts.remove(i);
                    removed = true;
                }
            }
            List<Object> bucket = factIndex.get(fact.getClass());
            if (bucket != null) {
                for (int i = bucket.size() - 1; i >= 0; i--) {
                    if (bucket.get(i) == fact) {
                        bucket.remove(i);
                    }
                }
            }
            return removed;
        }
    }

    /**
     * 事实若在工作内存中，则递增其版本号。
     *
     * @param fact 事实
     * @return 事实在工作内存中返回 true
     */
    boolean touchFact(Object fact) {
        synchronized (memoryLock) {
            boolean present = facts.stream().anyMatch(item -> item == fact);
            if (present) {
                factVersions.merge(fact, 1, Integer::sum);
            }
            return present;
        }
    }

    /**
     * 获取事实当前版本号。
     *
     * @param fact 事实
     * @return 版本号，从 0 开始
     */
    int versionOfFact(Object fact) {
        if (fact == null) {
            return 0;
        }
        synchronized (memoryLock) {
            return factVersions.getOrDefault(fact, 0);
        }
    }

    /**
     * 获取指定类型的事实，按运行时类型索引做可赋值匹配。
     *
     * @param type 事实类型
     * @return 事实快照，只读
     */
    List<Object> factsOfType(Class<?> type) {
        if (type == null) {
            return List.of();
        }
        synchronized (memoryLock) {
            List<Object> matched = new ArrayList<>();
            for (Map.Entry<Class<?>, List<Object>> entry : factIndex.entrySet()) {
                if (type.isAssignableFrom(entry.getKey())) {
                    matched.addAll(entry.getValue());
                }
            }
            return List.copyOf(matched);
        }
    }

    /**
     * 获取全部事实。
     *
     * @return 事实快照，只读
     */
    List<Object> allFacts() {
        synchronized (memoryLock) {
            return List.copyOf(facts);
        }
    }

    /**
     * 获取工作内存事实数量。
     *
     * @return 事实数量
     */
    int factCount() {
        synchronized (memoryLock) {
            return facts.size();
        }
    }

    // ==================== 结果 ====================

    /**
     * 合并单条激活产出的结果。
     *
     * @param ruleResult 规则上下文产出的主结果，可为 null（表示不覆盖）
     * @param ruleEntries 规则上下文产出的条目
     * @param ruleLists 规则上下文产出的列表
     */
    void mergeResult(Object ruleResult, Map<String, Object> ruleEntries,
                     Map<String, List<Object>> ruleLists) {
        if (ruleResult != null) {
            this.result = ruleResult;
        }
        this.entries.putAll(ruleEntries);
        for (Map.Entry<String, List<Object>> entry : ruleLists.entrySet()) {
            lists.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
        }
    }

    /**
     * 获取主结果。
     *
     * @return 主结果，未设置返回 null
     */
    Object result() {
        return result;
    }

    /**
     * 获取结果条目。
     *
     * @return 结果条目快照，只读
     */
    Map<String, Object> entries() {
        return Map.copyOf(entries);
    }

    /**
     * 获取指定键的列表结果。
     *
     * @param key 列表键
     * @return 列表快照，不存在返回空列表
     */
    List<Object> listOf(String key) {
        List<Object> value = lists.get(key);
        return value == null ? List.of() : List.copyOf(value);
    }

    /**
     * 获取全部列表结果。
     *
     * @return 列表结果快照，只读
     */
    Map<String, List<Object>> allLists() {
        Map<String, List<Object>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : lists.entrySet()) {
            snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    // ==================== 计数器 ====================

    /**
     * 记一次规则触发。
     */
    void markFired() {
        firedCount++;
    }

    /**
     * 记一次规则失败。
     */
    void markFailed() {
        failedCount++;
    }

    /**
     * 设置当前推理轮数。
     *
     * @param cycle 轮次，从 1 开始
     */
    void setCycle(int cycle) {
        cycleCount = cycle;
    }

    /**
     * 推进一轮推理。
     */
    void advanceCycle() {
        cycleCount++;
    }

    /**
     * 获取累计触发规则数。
     *
     * @return 触发次数
     */
    int firedCount() {
        return firedCount;
    }

    /**
     * 获取累计规则失败数。
     *
     * @return 失败次数
     */
    int failedCount() {
        return failedCount;
    }

    /**
     * 获取累计推理轮数。
     *
     * @return 推理轮数
     */
    int cycleCount() {
        return cycleCount;
    }

    // ==================== 生命周期 ====================

    /**
     * 是否已关闭。
     *
     * @return 已关闭返回 true
     */
    boolean isClosed() {
        return closed;
    }

    /**
     * 标记会话已关闭。
     */
    void markClosed() {
        closed = true;
    }

    /**
     * 清空工作内存、结果与计数器。
     *
     * <p>不重置关闭标记：{@code close()} 依赖「先清空、后标记」的顺序。</p>
     *
     * <p><b>注意：</b>此处刻意<b>不</b>清空事实版本表，以保持与重构前
     * {@code RuleSession.reset()} 完全一致的行为（该行为本身存在
     * 强引用滞留问题，是否修正需单独评估，不在本次重构范围内）。</p>
     */
    void reset() {
        synchronized (memoryLock) {
            facts.clear();
            factIndex.clear();
        }
        result = null;
        entries.clear();
        lists.clear();
        firedCount = 0;
        failedCount = 0;
        cycleCount = 0;
    }
}
