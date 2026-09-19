package com.chua.flow.support.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的流程执行快照存储。
 *
 * <p>线程安全，适用于单机、轻量场景。进程重启后数据丢失，
 * 需要持久化时请实现 {@link FlowSnapshotStore} 接入 MySQL/Redis。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MemoryFlowSnapshotStore implements FlowSnapshotStore {

    /**
     * 执行号到快照的映射
     */
    private final Map<String, FlowSnapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * 流程 标识 到执行号列表的映射
     */
    private final Map<String, List<String>> flowIndex = new ConcurrentHashMap<>();

    /**
     * 保存执行快照。
     *
     * @param snapshot 执行快照
     */
    @Override
    public void save(FlowSnapshot snapshot) {
        snapshots.put(snapshot.executionNo(), snapshot);
        flowIndex.computeIfAbsent(snapshot.flowId(), k -> new ArrayList<>())
                .add(snapshot.executionNo());
    }

    /**
     * 按执行号读取快照。
     *
     * @param executionNo 执行号
     * @return 快照，不存在时返回 空
     */
    @Override
    public FlowSnapshot load(String executionNo) {
        return snapshots.get(executionNo);
    }

    /**
     * 按流程 标识 列出快照。
     *
     * @param flowId 流程 标识
     * @return 快照列表，无则返回空列表
     */
    @Override
    public List<FlowSnapshot> list(String flowId) {
        List<String> nos = flowIndex.get(flowId);
        if (nos == null) {
            return List.of();
        }
        List<FlowSnapshot> result = new ArrayList<>(nos.size());
        for (String no : nos) {
            FlowSnapshot snapshot = snapshots.get(no);
            if (snapshot != null) {
                result.add(snapshot);
            }
        }
        return result;
    }
}
