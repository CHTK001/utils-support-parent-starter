package com.chua.starter.datasync.mapping;

import com.chua.common.support.converter.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 默认字段映射转换器实现。
 *
 * <p>支持的转换类型：
 * <ul>
 *   <li>toString: 转换为字符串</li>
 *   <li>toInteger: 转换为整数</li>
 *   <li>toLong: 转换为长整型</li>
 *   <li>toDouble: 转换为双精度浮点数</li>
 *   <li>toBoolean: 转换为布尔值</li>
 *   <li>toDate: 转换为日期（支持多种格式）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultFieldMappingConverter implements FieldMappingConverter {

    /**
     * 转换类型：转换为字符串
     */
    private static final String CONVERT_TO_STRING = "tostring";

    /**
     * 转换类型：转换为整数
     */
    private static final String CONVERT_TO_INTEGER = "tointeger";

    /**
     * 转换类型：转换为长整型
     */
    private static final String CONVERT_TO_LONG = "tolong";

    /**
     * 转换类型：转换为双精度浮点数
     */
    private static final String CONVERT_TO_DOUBLE = "todouble";

    /**
     * 转换类型：转换为布尔值
     */
    private static final String CONVERT_TO_BOOLEAN = "toboolean";

    /**
     * 转换类型：转换为日期
     */
    private static final String CONVERT_TO_DATE = "todate";

    @Override
    /**
     * 转换
    */
    public Object convert(Object value, String sourceField, String targetField, String converter) {
        if (value == null) {
            return null;
        }

        try {
            switch (converter == null ? "" : converter.toLowerCase()) {
                case CONVERT_TO_STRING:
                    return value.toString();
                case CONVERT_TO_INTEGER:
                    return Converter.convertIfNecessary(value, Integer.class, 0);
                case CONVERT_TO_LONG:
                    return Converter.convertIfNecessary(value, Long.class, 0L);
                case CONVERT_TO_DOUBLE:
                    return Converter.convertIfNecessary(value, Double.class, 0.0);
                case CONVERT_TO_BOOLEAN:
                    return Converter.convertIfNecessary(value, Boolean.class, Boolean.FALSE);
                case CONVERT_TO_DATE:
                    return Converter.convertIfNecessary(value, java.util.Date.class);
                default:
                    return value;
            }
        } catch (Exception e) {
            log.warn("字段转换失败 [{}->{}] value={}, converter={}", sourceField, targetField, value, converter, e);
            return value;
        }
    }
}
