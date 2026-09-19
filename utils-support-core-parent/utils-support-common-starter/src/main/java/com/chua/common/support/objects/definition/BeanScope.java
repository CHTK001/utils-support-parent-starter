package com.chua.common.support.objects.definition;


/**
 * Bean 作用域枚举。
 *
 * <p>定义 Bean 实例的生命周期范围：
 * <ul>
 *   <li>{@link #SINGLETON} — 单例模式，整个容器生命周期内只有一个实例</li>
 *   <li>{@link #PROTOTYPE} — 原型模式，每次获取都创建新的实例</li>
 * </ul></p>
 *
 * @author CH
 * @since 2024/12/20
 */
public enum BeanScope {

    /** 单例，整个容器生命周期内只有一个实例 */
    SINGLETON("singleton"),

    /** 原型，每次获取都创建新实例 */
    PROTOTYPE("prototype");

    /** 作用域名称 */
    /**
     * 名称
     */
    private final String name;

    /**
     * 构造方法，创建 BeanScope 实例。
     *
     * @param name 名称，不允许为 null
     */
    BeanScope(String name) {
        this.name = name;
    }

    /**
     * 获取作用域名称。
     *
     * @return 作用域名称，如 "单例"、"原型"
     */
    public String getName() {
        return name;
    }

    /**
     * 根据名称获取作用域枚举。
     *
     * <p>名称匹配不区分大小写。如果传入 null 或空字符串，默认返回 {@link #SINGLETON}。</p>
     *
     * @param name 作用域名称
     * @return 对应的作用域枚举，未匹配时返回 单例
     */
    public static BeanScope fromName(String name) {
        if (name == null || name.isEmpty()) {
            return SINGLETON;
        }
        for (BeanScope scope : values()) {
            if (scope.name.equalsIgnoreCase(name)) {
                return scope;
            }
        }
        return SINGLETON;
    }
}
