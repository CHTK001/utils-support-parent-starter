package com.chua.starter.datasync.config;

import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import java.util.List;
import java.util.Map;

/**
 * 数据同步配置定义。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataSyncConfigDefinition {

    /**
     * 获取输入标识。
     *
     * @return 输入标识
     */
    String inputId();

    /**
     * 获取输入 Source 实例 ID。
     *
     * @return 输入 Source ID
     */
    String sourceId();

    /**
     * 获取输出标识。
     *
     * @return 输出标识
     */
    String outputId();

    /**
     * 获取输出 Sink 实例 ID。
     *
     * @return 输出 Sink ID
     */
    String sinkId();

    /**
     * 获取字段映射列表。
     *
     * @return 字段映射列表
     */
    List<DataSyncFieldMapping> mappings();

    /**
     * 获取批量大小。
     *
     * @return 批量大小
     */
    int batch();

    /**
     * 获取定时类型。
     *
     * @return 定时类型
     */
    String cronType();

    /**
     * 获取定时表达式。
     *
     * @return 定时表达式
     */
    String cron();

    /**
     * 获取额外参数。
     *
     * @return 参数映射
     */
    Map<String, Object> params();
}
