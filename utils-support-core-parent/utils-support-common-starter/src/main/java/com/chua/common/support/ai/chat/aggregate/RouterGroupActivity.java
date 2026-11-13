package com.chua.common.support.ai.chat.aggregate;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 路由组最近一次/最近若干次路由决策的内存快照。
 *
 * <p>只用于前端「实时」视图：把「这一轮请求被派给了哪个成员、成功还是失败、耗时多少」
 * 暴露给管理页做连线动画。它不参与路由决策，也不做持久化 —— 进程重启后清空，
 * 视图回到「等待请求」状态即可。</p>
 *
 * <p>每个组只保留最近 {@value #CAPACITY} 条，避免长时间运行无限增长；读写都在
 * 无锁并发容器里完成，路由热路径上只是几次入队/出队。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class RouterGroupActivity {

    /**
     * 每个组保留的决策条数上限
     */
    private static final int CAPACITY = 20;

    /**
     * 组 ID → 最近决策（队首最新）
     */
    private static final Map<String, Deque<Decision>> RECENT = new ConcurrentHashMap<>();

    private RouterGroupActivity() {
    }

    /**
     * 记一次路由决策结果。
     *
     * @param groupId     路由组供应商 ID
     * @param memberLabel 成员标识（{@code providerId:model}）
     * @param memberModel 成员模型名（前端展示用）
     * @param success     是否成功
     * @param costMs      本次耗时（毫秒，失败传 0）
     */
    public static void record(String groupId, String memberLabel, String memberModel,
                              boolean success, long costMs) {
        if (groupId == null || memberLabel == null) {
            return;
        }
        Deque<Decision> deque = RECENT.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(new Decision(memberLabel, memberModel, System.currentTimeMillis(), success, costMs));
        while (deque.size() > CAPACITY) {
            Decision tail = deque.pollLast();
            if (tail == null) {
                break;
            }
        }
    }

    /**
     * 读取某组最近决策（最新在前）。
     *
     * @param groupId 路由组供应商 ID
     * @return 决策列表；无记录时为空列表
     */
    public static List<Decision> recent(String groupId) {
        Deque<Decision> deque = groupId == null ? null : RECENT.get(groupId);
        if (deque == null) {
            return List.of();
        }
        List<Decision> result = new ArrayList<>(deque.size());
        Iterator<Decision> it = deque.iterator();
        while (it.hasNext()) {
            result.add(it.next());
        }
        return result;
    }

    /**
     * 清空所有组的决策快照（测试与配置大改后使用）。
     */
    public static void clear() {
        RECENT.clear();
    }

    /**
     * 一条路由决策。
     *
     * @param memberLabel 成员标识
     * @param memberModel 成员模型名
     * @param at          发生时间（毫秒时间戳）
     * @param success     是否成功
     * @param costMs      耗时（毫秒）
     */
    public record Decision(String memberLabel, String memberModel, long at, boolean success, long costMs) {
    }
}
