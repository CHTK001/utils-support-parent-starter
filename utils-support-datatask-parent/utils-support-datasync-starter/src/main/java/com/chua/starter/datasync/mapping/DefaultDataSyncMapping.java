package com.chua.starter.datasync.mapping;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.DirectoryConfigDefinition;
import com.chua.starter.datasync.config.FileConfigDefinition;
import com.chua.starter.datasync.config.TextConfigDefinition;
import com.chua.starter.datasync.model.DataSyncMapping;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
     * 规范构造器：字段映射列表与参数映射做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。</p>
     *
     * <p>两个集合刻意保留 空 语义：{@code DefaultDataSyncMapping} 的规范构造器是 公开 API，
     * 既可被 {@code DatabaseMappingLoader} 直接调用，也可经
     * {@code createFromConfig} 接收第三方 {@link DataSyncConfigDefinition} 的返回值，
     * 而 {@code DefaultSyncDataSchedulerManager} 明确写过
     * {@code mapping.mappings() == null ? List.of() : ...} 与
     * {@code mapping.params() == null ? new HashMap<>() : ...} 的降级分支，
     * 改成拒绝 空 值等于把既有降级路径变成 空指针。</p>
     *
     * <p>{@code config} 与 {@code trigger} 同样不加 校验：{@code DatabaseMappingLoader}
     * 与多个探针都显式传 {@code null}，{@code DataSyncMapping#trigger()} 的契约也写明
     * 「可能为空」。</p>
     *
     * <p>采用 {@link ArrayList} / {@link LinkedHashMap} 快照 + {@link Collections} 包装
     * 而非 {@code List.copyOf} / {@code Map.copyOf}：后两者既拒绝 空 元素，又不保证
     * {@code params} 的迭代顺序，而参数顺序需与配置文件保持一致。</p>
     *
     * @param mappings 字段映射列表
     * @param params 参数映射
     */
    public DefaultDataSyncMapping {
        mappings = mappings == null ? null : Collections.unmodifiableList(new ArrayList<>(mappings));
        params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

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
    /**
     * Trigger
    */
    public com.chua.common.support.task.scheduler.Trigger trigger() {
        return trigger;
    }
}
