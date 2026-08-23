package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * Stub mirroring common-starter's com.chua.common.support.spi.annotations.Extension (same FQN).
 *
 * @author CH
 * @since 4.0.0.42
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

