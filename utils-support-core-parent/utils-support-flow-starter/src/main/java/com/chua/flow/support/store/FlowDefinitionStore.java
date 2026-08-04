package com.chua.flow.support.store;

import com.chua.common.support.task.flow.FlowDefinition;

import java.util.List;

/**
 * 流程定义存储接口。
 *
 * <p>定义流程定义的保存、查询、删除能力，
 * 由具体实现决定持久化介质（内存、文件、数据库等）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FlowDefinitionStore {

    /**
     * 保存或更新流程定义。
     *
     * <p>以定义 ID 为键，已存在时覆盖更新。</p>
     *
     * @param definition 流程定义
     */
    void save(FlowDefinition definition);

    /**
     * 按 ID 查询流程定义。
     *
     * @param flowId 流程 ID
     * @return 流程定义，不存在时返回 null
     */
    FlowDefinition get(String flowId);

    /**
     * 按 ID 删除流程定义。
     *
     * @param flowId 流程 ID
     * @return 删除成功返回 true
     */
    boolean remove(String flowId);

    /**
     * 查询全部流程定义。
     *
     * @return 流程定义列表
     */
    List<FlowDefinition> list();
}
