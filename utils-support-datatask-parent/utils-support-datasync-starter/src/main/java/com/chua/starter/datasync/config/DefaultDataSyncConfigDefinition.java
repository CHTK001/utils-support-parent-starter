package com.chua.starter.datasync.config;

import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import java.util.List;
import java.util.Map;

/**
 * 默认数据同步配置定义。
 *
 * @author CH
 * @since 4.0.0.42
 */
public record DefaultDataSyncConfigDefinition(
        String mappingId,
        String inputId,
        String sourceId,
        String outputId,
        String sinkId,
        List<DataSyncFieldMapping> mappings,
        int batch,
        String cronType,
        String cron,
        Map<String, Object> params
) implements DataSyncConfigDefinition {

}
