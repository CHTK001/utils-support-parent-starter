package com.chua.common.support.config.source;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 空属性源实现类。
 * <p>
 * 该类用于提供一个不返回任何实际配置值的属性源。
 * 当需要占位或作为默认空值源时使用。
 * </p>
 *
 * @author CH
 * @since 2023-08-01
 */
public class EmptyPropertySource implements PropertySource {

    /**
     * 获取指定键的属性值。
     * <p>
     * 由于是空属性源，该方法始终返回 null。
     * </p>
     *
     * @param key 要查询的配置键名
     * @return 永远为 null，表示不存在该属性
     */
    @Override
    public Object getProperty(String key) {
        return null;
    }

    /**
     * 获取该属性源的名称。
     * <p>
     * 返回固定的标识符 "empty"。
     * </p>
     *
     * @return 属性源名称
     */
    @Override
    public String getName() {
        return "empty";
    }
}
