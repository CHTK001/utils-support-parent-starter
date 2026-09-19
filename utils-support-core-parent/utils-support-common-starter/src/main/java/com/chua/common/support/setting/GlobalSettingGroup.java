package com.chua.common.support.setting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 全局配置分组注解。
 * <p>
 * 标注在 Bean 类上，由 {@link GlobalSettingAutoConfiguration#registerGlobalSettings}
 * 自动扫描并注册到 {@link com.chua.common.support.application.GlobalSettingFactory}。
 * </p>
 *
 * <pre>{@code
 * @GlobalSettingGroup("default")
 * public class GuestDefaultSetting {
 *     private boolean checkCodeOpen;
 *     ...
 * }
 * }</pre>eckCodeOpen;
 *     ...
 * }
 * }</pre>
 *
 * @author CH
 * @since 2024/8/13
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalSettingGroup {

    /**
     * 配置分组名，对应 sys_setting.sys_setting_群体 字段。
     *
     * @return 分组名
     */
    String value();

    /**
     * 分组是否启用（默认 true）。
     *
     * @return true=启用, false=禁用
     */
    boolean enabled() default true;
}
