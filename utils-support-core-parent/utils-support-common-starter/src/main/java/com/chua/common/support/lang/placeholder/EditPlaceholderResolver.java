package com.chua.common.support.lang.placeholder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 编辑占位符解析器接口。
 * <p>
 * 该接口扩展了 {@link PlaceholderResolver}，提供了设置属性键值对的功能，
 * 用于在解析占位符时动态注入或修改配置信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EditPlaceholderResolver extends PlaceholderResolver {

    /**
     * 设置一个属性键值对。
     * <p>
     * 该方法允许向解析器中注册自定义的属性，这些属性可能在后续的占位符替换过程中被使用。
     *
     * @param key   属性的键，不能为 null
     * @param value 属性的值，可以为 null
     */
    void setProperty(@Nonnull String key, @Nullable String value);
}
