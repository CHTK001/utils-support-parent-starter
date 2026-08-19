package com.chua.common.support.objects.definition;


/**
 * 框架 Bean 定义包装器，用于将外部框架（Spring / CDI / OSGi）管理的 Bean
 * 桥接到框架 IoC 容器。
 *
 * <p>与 {@link SingletonBeanDefinition} 不同，本类直接跳过 IoC 生命周期
 * （依赖注入、{@code @PostConstruct} 等），因为外部框架的 Bean 已被其
 * 原生容器完全初始化。{@link #getBean()} 直接返回外部实例。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
public class FrameworkBeanDefinition extends AbstractBeanDefinition {

    /** instance */
    private volatile Object instance;

    /**
     * 构造框架 Bean 定义。
     *
     * @param name     Bean 名称
     * @param type     Bean 类型
     * @param instance Bean 实例（由外部框架管理）
     */
    public FrameworkBeanDefinition(String name, Class<?> type, Object instance) {
        super(name, type, BeanScope.SINGLETON);
        this.instance = instance;
    }

    @Override
    public Object getBean() {
        return instance;
    }

    @Override
    protected Object doGetBean() {
        return instance;
    }

    @Override
    protected void setBean(Object bean) {
        this.instance = bean;
    }

    @Override
    public Object createInstance() {
        return instance;
    }

    @Override
    public Object initializeBean() {
        return instance;
    }

    @Override
    public void destroyBean() {
        if (isDestroyed()) {
            return;
        }
        super.destroyBean();
        this.instance = null;
    }
}
