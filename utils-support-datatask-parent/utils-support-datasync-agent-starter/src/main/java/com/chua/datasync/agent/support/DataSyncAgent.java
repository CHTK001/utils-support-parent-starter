package com.chua.datasync.agent.support;

import reactor.core.publisher.Flux;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 数据同步 Agent 接口，统一管理生命周期与数据监听。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataSyncAgent {

    /**
     * 获取 Agent 唯一标识。
     *
     * @return Agent ID
     */
    String agentId();

    /**
     * 获取 Agent 关联的所有 Source 实例。
     *
     * @return Source 列表，不可修改
     */
    default List<DataSyncAgentSource> sources() {
        return Collections.emptyList();
    }

    /**
     * 根据 sourceId 获取 Source 实例。
     *
     * @param sourceId Source 实例 ID
     * @return Source 实例，不存在返回 null
     */
    default DataSyncAgentSource getSource(String sourceId) {
        return null;
    }

    /**
     * 获取 Agent 关联的所有 Sink 实例。
     *
     * @return Sink 列表，不可修改
     */
    default List<DataSyncAgentSink> sinks() {
        return Collections.emptyList();
    }

    /**
     * 根据 sinkId 获取 Sink 实例。
     *
     * @param sinkId Sink 实例 ID
     * @return Sink 实例，不存在返回 null
     */
    default DataSyncAgentSink getSink(String sinkId) {
        return null;
    }

    /**
     * 数据到达监听回调。
     *
     * @param data 接收到的数据
     */
    default void onDataReceived(List<Map<String, Object>> data) {
    }

    /**
     * 获取当前 Agent 对应的 Source（若有）。
     *
     * @return Source 实例，默认返回 null
     */
    default DataSyncSource toSource() {
        return null;
    }

    /**
     * 获取当前 Agent 对应的 Sink（若有）。
     *
     * @return Sink 实例，默认返回 null
     */
    default DataSyncSink toSink() {
        return null;
    }

    /**
     * 是否正在运行。
     *
     * @return 运行状态
     */
    default boolean isRunning() {
        return false;
    }

    /**
     * 获取数据源的 URL（供调试）。
     *
     * @return URL 描述
     */
    default String dataUrl() {
        return "";
    }

    /**
     * 启动 Agent。
     */
    void start();

    /**
     * 停止 Agent。
     */
    void stop();
}
