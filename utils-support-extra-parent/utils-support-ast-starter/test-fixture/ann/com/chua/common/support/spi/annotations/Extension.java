package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * Stub mirroring common-starter's com.chua.common.support.spi.annotations.Extension (same FQN).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Extension {

    /**
     * Extension name.
     *
     * @return extension name
     */
    String value();
}
