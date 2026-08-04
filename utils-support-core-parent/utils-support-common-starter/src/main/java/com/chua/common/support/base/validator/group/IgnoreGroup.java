package com.chua.common.support.base.validator.group;

import org.jspecify.annotations.NullUnmarked;

/**
 * 忽略组校验标记接口
 *
 * <p>用于在 {@code @Validated(IgnoreGroup.class)} 场景下标记无需进行字段校验的分组。</p>
 *
 * @author CH
 * @since 2025/1/1
 */
@NullUnmarked
public interface IgnoreGroup {
}
