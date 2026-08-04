package com.chua.common.support.lang.placeholder;

import java.util.Map;
import org.jspecify.annotations.NullUnmarked;


/**
 * 占位符动态解析器接口。
 * 用于动态添加、移除和解析占位符，支持链式调用。
 *
 * @author CH
 */
@NullUnmarked
public interface PlaceholderDynamicResolver {
    /**
     * 添加一个名称和对应的值到解析器中。
     *
     * @param name  占位符的名称（例如：${name}中的"name"）
     * @param value 与名称关联的值
     * @return 当前实例，支持链式调用
     */
    PlaceholderDynamicResolver add(String name, Object value);

    /**
     * 批量添加多个名称和值到解析器中。
     *
     * @param value 包含名称和值的映射表
     * @return 当前实例，支持链式调用
     */
    default PlaceholderDynamicResolver add(Map<String, ?> value) {
        value.forEach(this::add);
        return this;
    }

    /**
     * 从解析器中移除指定名称的占位符。
     *
     * @param name 要移除的占位符名称
     */
    void remove(String name);
}