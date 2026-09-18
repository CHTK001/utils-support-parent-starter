package com.chua.flow.support.store;

import com.chua.common.support.task.flow.FlowInstance;
import com.chua.flow.support.DefaultFlowInstance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* 流程实例注册中心。
*
* <p>登记运行中的流程实例，支持按实例 ID 查询，
* 用于外部触发挂起实例的恢复（断点续跑）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class FlowInstanceRegistry {

    /**
    * 实例存储映射
    */
    private final Map<String, FlowInstance> storage = new ConcurrentHashMap<>();

    /**
    * 登记流程实例。
    *
    * @param instance 流程实例
    */
    public void register(FlowInstance instance) {
        storage.put(instance.getInstanceId(), instance);
    }

    /**
    * 按实例 标识 查询流程实例。
    *
    * @param instanceId 实例 标识
    * @return 流程实例，不存在时返回 空
    */
    public FlowInstance get(String instanceId) {
        return storage.get(instanceId);
    }

    /**
    * 移除流程实例。
    *
    * @param instanceId 实例 标识
    */
    public void remove(String instanceId) {
        storage.remove(instanceId);
    }
}
