package com.chua.starter.datasync.mapping;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.FileConfigDefinition;
import com.chua.starter.datasync.config.TextConfigDefinition;
import com.chua.starter.datasync.model.DataSyncMapping;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* 默认数据同步映射管理器。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DefaultDataSyncMappingManager implements DataSyncMappingManager {

    /**
    * 映射注册表
    */
    private final Map<String, DataSyncMapping> mappings = new ConcurrentHashMap<>();

    @Override
    /** 添加Mapping */
    public void addMapping(DataSyncMapping mapping) {
        mappings.put(mapping.mappingId(), mapping);
        log.info("添加映射: mappingId={}, inputId={}, outputId={}", mapping.mappingId(), mapping.inputId(), mapping.outputId());
    }

    @Override
    /** 获取Mappings */
    public List<DataSyncMapping> getMappings() {
        return new ArrayList<>(mappings.values());
    }

    @Override
    /** 获取mappingsby输入id */
    public List<DataSyncMapping> getMappingsByInputId(String inputId) {
        List<DataSyncMapping> result = new ArrayList<>();
        for (DataSyncMapping mapping : mappings.values()) {
            if (mapping.inputId().equals(inputId)) {
                result.add(mapping);
            }
        }
        return result;
    }

    @Override
    /** 获取mappingsby输出id */
    public List<DataSyncMapping> getMappingsByOutputId(String outputId) {
        List<DataSyncMapping> result = new ArrayList<>();
        for (DataSyncMapping mapping : mappings.values()) {
            if (mapping.outputId().equals(outputId)) {
                result.add(mapping);
            }
        }
        return result;
    }

    @Override
    /** 移除Mapping */
    public void removeMapping(String mappingId) {
        mappings.remove(mappingId);
    }

    @Override
    /** 创建从配置 */
    public DataSyncMapping createFromConfig(String mappingId, DataSyncConfigDefinition config) {
        return new DefaultDataSyncMapping(mappingId, config);
    }
}
