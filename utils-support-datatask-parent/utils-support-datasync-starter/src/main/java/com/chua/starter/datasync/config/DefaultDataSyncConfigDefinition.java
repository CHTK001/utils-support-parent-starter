package com.chua.starter.datasync.config;

import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /**
     * 规范构造器：字段映射列表与参数映射做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。</p>
     *
     * <p>两个集合刻意保留 空 语义：本 record 是公开配置载体，可由 YAML 解析、数据库加载
     * 或第三方 {@link DataSyncConfigDefinition} 实现喂入，而调用方
     * {@code DefaultSyncDataSchedulerManager} 明确写过
     * {@code mapping.mappings() == null ? List.of() : ...} 与
     * {@code mapping.params() == null ? new HashMap<>() : ...} 的降级分支，
     * 改成拒绝 空 值等于把既有降级路径变成 空指针。</p>
     *
     * <p>采用 {@link ArrayList} / {@link LinkedHashMap} 快照 + {@link Collections} 包装
     * 而非 {@code List.copyOf} / {@code Map.copyOf}：后两者既拒绝 空 元素，又不保证
     * {@code params} 的迭代顺序，而参数顺序需与配置文件保持一致。</p>
     *
     * @param mappings 字段映射列表
     * @param params 参数映射
     */
    public DefaultDataSyncConfigDefinition {
        mappings = mappings == null ? null : Collections.unmodifiableList(new ArrayList<>(mappings));
        params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
