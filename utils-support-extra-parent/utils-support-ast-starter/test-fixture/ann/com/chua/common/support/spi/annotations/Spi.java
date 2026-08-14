package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * Stub mirroring common-starter's com.chua.common.support.spi.annotations.Spi (same FQN).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Spi {

    /**
     * Names.
     *
     * @return names
     */
    String[] value() default {};

    /**
     * Order.
     *
     * @return order
     */
    int order() default 0;
}
