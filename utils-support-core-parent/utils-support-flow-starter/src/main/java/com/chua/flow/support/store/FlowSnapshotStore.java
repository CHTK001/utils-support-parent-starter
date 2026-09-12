package com.chua.flow.support.store;

import java.util.List;

/**
 * 流程执行快照存储接口。
 *
 * <p>一次流程运行结束后引擎会将完整执行快照交由此存储，供全链路追踪、
 * 审计回放与前端"按执行号查看整条链路"使用。默认提供 {@link MemoryFlowSnapshotStore}
 * 内存实现，业务可注入基于 MySQL/Redis 的持久化实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FlowSnapshotStore {

    /**
     * 保存一次流程执行快照。
     *
     * @param snapshot 执行快照
     */
    void save(FlowSnapshot snapshot);

    /**
     * 按执行号读取快照。
     *
     * @param executionNo 执行号
     * @return 快照，不存在时返回 空
     */
    FlowSnapshot load(String executionNo);

    /**
      * 按流程 标识 分页列出快照。
     *
     * @param flowId 流程 标识
     * @return 快照列表，无则返回空列表
     */
    List<FlowSnapshot> list(String flowId);
}