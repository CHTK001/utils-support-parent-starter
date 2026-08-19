package com.chua.common.support.config.loader;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


/**
 * 配置值绑定类
 * <p>
 * 用于封装@ConfigValue注解解析后的信息，包括配置键、表达式、默认值、目标Bean、字段或方法引用等。
 * 支持热重载和回调机制，适用于动态配置注入场景。
 * </p>
 *
 * @author CH
 * @since 2024-12-05
 * @version 1.0.0
 */
@Getter
@Builder
public class ConfigValueBinding {

    /**
     * 配置项的键名
     * 例如：app.name
     */
    private final String configKey;

    /**
     * 配置表达式的原始字符串
     * 例如：${app.name:default}
     */
    private final String expression;

    /**
     * 配置的默认值
     * 当配置不存在时使用的后备值
     */
    private final String defaultValue;

    /**
     * 绑定的Bean实例对象
     * 用于将配置值注入到该Bean中
     */
    private final Object bean;

    /**
     * 绑定的Bean的名称
     * 用于在Spring容器或其他上下文中定位Bean
     */
    private final String beanName;

    /**
     * 绑定的字段对象
     * 当配置值需要注入到类的字段时使用
     */
    private final Field field;

    /**
     * 绑定的方法对象
     * 当配置值需要通过Setter方法注入时使用
     */
    private final Method method;

    /**
     * 方法的参数索引
     * 当method不为null时，表示该方法参数的位置索引
     */
    private final int parameterIndex;

    /**
     * 是否启用热重载功能
     * true表示配置变化时自动触发更新逻辑
     */
    private final boolean hotReload;

    /**
     * 配置变更时的回调方法名称
     * 用于在配置更新后执行自定义逻辑
     */
    private final String callback;

    /**
     * 目标值的类型
     * 用于配置值转换和校验
     */
    private final Class<?> targetType;

    /**
     * 当前已加载的配置值
     * 可能在运行时被动态更新
     * -- SETTER --
     *  设置当前配置值
     *
     * @param value 要设置的配置值对象

     */
    @Setter
    /** 当前值 */
    private Object currentValue;

    /**
     * 判断是否为字段绑定模式
     *
     * @return 如果field不为null则返回true，否则返回false
     */
    public boolean isFieldBinding() {
        return field != null;
    }

    /**
     * 判断是否为方法绑定模式
     *
     * @return 如果method不为null则返回true，否则返回false
     */
    public boolean isMethodBinding() {
        return method != null;
    }

    @Override
    /** ToString */
    public String toString() {
        if (isFieldBinding()) {
            return String.format("ConfigValueBinding[key=%s, bean=%s, field=%s, hotReload=%s]",
                    configKey, beanName, field.getName(), hotReload);
        } else if (isMethodBinding()) {
            return String.format("ConfigValueBinding[key=%s, bean=%s, method=%s, hotReload=%s]",
                    configKey, beanName, method.getName(), hotReload);
        } else {
            return String.format("ConfigValueBinding[key=%s, bean=%s, hotReload=%s]",
                    configKey, beanName, hotReload);
        }
    }
}
