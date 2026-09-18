package com.chua.starter.datasync.mapping;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.DirectoryConfigDefinition;
import com.chua.starter.datasync.config.FileConfigDefinition;
import com.chua.starter.datasync.config.TextConfigDefinition;
import com.chua.starter.datasync.model.DataSyncMapping;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;

/**
* 默认数据同步映射。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public record DefaultDataSyncMapping(
        String mappingId,
        String inputId,
        String sourceId,
        String outputId,
        String sinkId,
        DataSyncConfigDefinition config,
        List<DataSyncFieldMapping> mappings,
        int batch,
        String cronType,
        String cron,
        Map<String, Object> params,
        com.chua.common.support.task.scheduler.Trigger trigger
) implements DataSyncMapping {

    /**
    * 从配置定义构造映射。
    * @param mappingId mappingid
    * @param config 配置
    * @return 默认数据同步mapping的结果
    */
    public DefaultDataSyncMapping(String mappingId, DataSyncConfigDefinition config) {
        this(
                mappingId,
                config.inputId(),
                config.sourceId(),
                config.outputId(),
                config.sinkId(),
                config,
                config.mappings(),
                config.batch(),
                config.cronType(),
                config.cron(),
                config.params(),
                null // Config doesn't have trigger yet, so null
        );
    }

    @Override
    /** Trigger */
    public com.chua.common.support.task.scheduler.Trigger trigger() {
        return trigger;
    }
}
