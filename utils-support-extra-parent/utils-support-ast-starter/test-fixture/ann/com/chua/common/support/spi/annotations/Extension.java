package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
* Stub mirroring common-starter's com.chua.通用.支持.spi.注解.延伸 (same FQN).
*
* @author CH
* @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Extension {

    /**
    * 延伸 名称.
    *
    * @return extension 名称
     */
    String value();
}

