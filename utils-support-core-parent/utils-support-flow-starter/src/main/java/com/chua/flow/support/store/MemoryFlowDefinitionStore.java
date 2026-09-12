package com.chua.flow.support.store;

import com.chua.common.support.task.flow.FlowDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存流程定义存储实现。
 *
 * <p>基于 {@link ConcurrentHashMap} 存储流程定义，进程重启后数据丢失。
 * 适用于原型演示与轻量场景，生产环境可替换为数据库实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MemoryFlowDefinitionStore implements FlowDefinitionStore {

    /**
     * 流程定义存储映射
     */
    private final Map<String, FlowDefinition> storage = new ConcurrentHashMap<>();

    /**
     * 保存或更新流程定义。
     *
     * @param definition 流程定义
     */
    @Override
    public void save(FlowDefinition definition) {
        storage.put(definition.getId(), definition);
    }

    /**
      * 按 标识 查询流程定义。
     *
     * @param flowId 流程 标识
     * @return 流程定义，不存在时返回 空
     */
    @Override
    public FlowDefinition get(String flowId) {
        return storage.get(flowId);
    }

    /**
      * 按 标识 删除流程定义。
     *
     * @param flowId 流程 标识
     * @return 删除成功返回 true
     */
    @Override
    public boolean remove(String flowId) {
        return storage.remove(flowId) != null;
    }

    /**
     * 查询全部流程定义。
     *
     * @return 流程定义列表
     */
    @Override
    public List<FlowDefinition> list() {
        return new ArrayList<>(storage.values());
    }
}
