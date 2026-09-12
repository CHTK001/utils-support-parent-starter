package com.chua.starter.datasync.mapping;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.model.DataSyncMapping;
import java.util.List;

/**
* 数据同步映射管理器。
*
* @author CH
* @since 4.0.0.42
 */
public interface DataSyncMappingManager {

    /**
    * 添加映射。
    *
    * @param mapping 映射配置
     */
    void addMapping(DataSyncMapping mapping);

    /**
    * 获取所有映射。
    *
    * @return 映射列表
     */
    List<DataSyncMapping> getMappings();

    /**
    * 根据输入标识获取映射。
    *
    * @param inputId 输入标识
    * @return 映射列表
     */
    List<DataSyncMapping> getMappingsByInputId(String inputId);

    /**
    * 根据输出标识获取映射。
    *
    * @param outputId 输出标识
    * @return 映射列表
     */
    List<DataSyncMapping> getMappingsByOutputId(String outputId);

    /**
    * 从配置定义创建映射。
    *
    * @param mappingId 映射 标识
    * @param config 配置定义
    * @return 映射实例
     */
    DataSyncMapping createFromConfig(String mappingId, DataSyncConfigDefinition config);

    /**
    * 移除映射。
    *
    * @param mappingId 映射 标识
     */
    void removeMapping(String mappingId);
}
