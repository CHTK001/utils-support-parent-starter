package com.chua.common.support.function;

import org.jspecify.annotations.NullUnmarked;

/**
 * 名称感知接口
 *
 * @author CH
 */
@NullUnmarked
@FunctionalInterface
public interface NameAware {

    /**
     * 获取名称数组
     *
     * @return 名称数组
     */
    String[] named();
}
