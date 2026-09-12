package com.chua.starter.datasync.model;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import java.util.List;
import java.util.Map;

/**
 * 数据同步映射模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataSyncMapping {

    /**
      * 获取映射 标识。
     *
     * @return 映射 标识
     */
    String mappingId();

    /**
     * 获取输入标识。
     *
     * @return 输入标识
     */
    String inputId();

    /**
      * 获取输入 源 实例 标识。
     *
     * @return 输入 源 标识
     */
    String sourceId();

    /**
     * 获取输出标识。
     *
     * @return 输出标识
     */
    String outputId();

    /**
      * 获取输出 Sink 实例 标识。
     *
     * @return 输出 Sink 标识
     */
    String sinkId();

    /**
     * 获取配置定义。
     *
     * @return 配置定义
     */
    DataSyncConfigDefinition config();

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

    /**
     * 获取触发器。
     * <p>优先使用该触发器判断调度条件；若为空则降级使用 {@link #cron()}。</p>
     *
     * @return 触发器，可能为空
     */
    com.chua.common.support.task.scheduler.Trigger trigger();
}
