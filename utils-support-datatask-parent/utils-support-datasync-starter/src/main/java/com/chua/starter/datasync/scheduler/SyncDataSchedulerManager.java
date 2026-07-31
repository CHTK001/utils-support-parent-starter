package com.chua.starter.datasync.scheduler;

import com.chua.starter.datasync.model.DataSyncMapping;

/**
 * 数据同步调度器管理器，每秒执行一次，获取满足条件的 Mapping 并执行。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SyncDataSchedulerManager {

    /**
     * 添加映射。
     *
     * @param mapping 映射配置
     */
    void addMapping(DataSyncMapping mapping);

    /**
     * 移除映射。
     *
     * @param mappingId 映射 ID
     */
    void removeMapping(String mappingId);

    /**
     * 获取映射。
     *
     * @param mappingId 映射 ID
     * @return 映射配置
     */
    DataSyncMapping getMapping(String mappingId);

    /**
     * 获取所有映射。
     *
     * @return 映射列表
     */
    java.util.List<DataSyncMapping> getMappings();

    /**
     * 启动调度器。
     */
    void start();

    /**
     * 停止调度器。
     */
    void stop();
}
