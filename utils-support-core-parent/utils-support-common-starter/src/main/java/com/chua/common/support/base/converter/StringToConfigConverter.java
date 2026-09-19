package com.chua.common.support.base.converter;

import java.util.Map;

/**
 * 字符串转配置转换器接口。
 *
 * <p>定义了将字符串格式的配置转换为 Map 结构的标准接口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface StringToConfigConverter {

    /**
     * 将字符串格式的值转换为配置 Map。
     *
     * @param value 字符串格式的配置值
     * @return 配置 Map
     */
    Map<String, Object> convert(String value);
}
