package com.chua.common.support.spi.condition;

import org.jspecify.annotations.NullUnmarked;

/**
 * SPI条件
 * @author CH
 */
@NullUnmarked
public interface SpiCondition {
    /**
     * 是否条件
     * @return 是否条件
     */
    boolean isCondition();
}
