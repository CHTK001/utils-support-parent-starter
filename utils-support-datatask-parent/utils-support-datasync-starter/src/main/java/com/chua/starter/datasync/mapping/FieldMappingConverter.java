package com.chua.starter.datasync.mapping;

import com.chua.common.support.utils.CollectionUtils;

import java.util.List;
import java.util.Map;

/**
 * 字段映射转换器接口。
 *
 * <p>用于在数据同步过程中对字段进行类型转换和映射。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FieldMappingConverter {

    /**
     * 转换单个字段的值。
     *
     * @param value         原始值
     * @param sourceField   源字段名
     * @param targetField   目标字段名
     * @param converter     转换器标识（如 "toString", "toInteger" 等）
     * @return 转换后的值
     */
    Object convert(Object value, String sourceField, String targetField, String converter);

    /**
     * 应用字段映射列表到数据记录。
     *
     * <p>仅包含映射后的目标字段，并按映射顺序排列，不保留原记录其他字段。</p>
     *
     * @param record    原始数据记录
     * @param mappings  字段映射列表
     * @return 转换后的数据记录（只包含成功映射的字段）
     */
    default Map<String, Object> applyMappings(Map<String, Object> record, List<DataSyncFieldMapping> mappings) {
        if (record == null || CollectionUtils.isEmpty(mappings)) {
            return record;
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (DataSyncFieldMapping mapping : mappings) {
            Object value = record.get(mapping.sourceField());
            if (value != null) {
                Object converted = convert(value, mapping.sourceField(), mapping.targetField(), mapping.converter());
                result.put(mapping.targetField(), converted);
            }
        }
        return result;
    }
}