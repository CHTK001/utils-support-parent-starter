package com.chua.common.support.objects.definition;

import org.jspecify.annotations.NullUnmarked;

/**
 * 单例 Bean 定义。
 *
 * <p>提供单例作用域的 BeanDefinition 实现，确保整个容器生命周期内
 * 每个 Bean 名称只对应一个实例。实例在首次调用 {@link #initializeBean()}
 * 时完成创建、依赖注入和生命周期初始化。</p>
 *
 * <p>生命周期管理：
 * <ul>
 *   <li>初始化：调用 {@link #initializeBean()} 依次执行字段注入、{@code @PostConstruct}、{@code InitializingAware}</li>
 *   <li>销毁：调用 {@link #destroyBean()} 执行 {@code @PreDestroy} 并释放引用</li>
 *   <li>状态跟踪：通过父类的 AtomicBoolean 保证初始化和销毁操作的线程安全性</li>
 * </ul></p>
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
public class SingletonBeanDefinition extends AbstractBeanDefinition {

    /** 单例实例，volatile 保证多线程可见性 */
    private volatile Object singletonInstance;

    /**
     * 构造单例 Bean 定义（懒加载）。
     *
     * @param name      Bean 名称
     * @param beanClass Bean 类
     */
    public SingletonBeanDefinition(String name, Class<?> beanClass) {
        super(name, beanClass, BeanScope.SINGLETON);
    }

    /**
     * 从已创建的实例构造单例 Bean 定义。
     *
     * <p>实例通过 {@link #setBean(Object)} 保存，不设置初始化标志 —
     * 后续需显式调用 {@link #initializeBean()} 以完成注入和生命周期回调。</p>
     *
     * @param definition Bean 定义
     * @param instance   Bean 实例
     */
    public SingletonBeanDefinition(BeanDefinition definition, Object instance) {
        super(definition.getName(), definition.getBeanClass(), BeanScope.SINGLETON);
        setPriority(definition.getPriority());
        setBean(instance);
    }

    /**
     * 创建单例 Bean 定义。
     *
     * @param bean Bean 实例
     * @return 单例 Bean 定义
     */
    public static SingletonBeanDefinition of(Object bean) {
        return new SingletonBeanDefinition(BeanDefinition.EMPTY_BEAN_DEFINITION, bean);
    }

    /**
     * 获取已缓存的单例实例。
     *
     * @return 单例实例
     */
    @Override
    protected Object doGetBean() {
        return singletonInstance;
    }

    /**
     * 保存单例实例。
     *
     * @param bean 单例实例
     */
    @Override
    protected void setBean(Object bean) {
        this.singletonInstance = bean;
    }

    /**
     * 创建 Bean 实例。
     *
     * <p>已存在实例时直接返回，否则反射新建。</p>
     *
     * @return Bean 实例
     */
    @Override
    public Object createInstance() {
        if (singletonInstance != null) {
            return singletonInstance;
        }
        try {
            return getBeanClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("创建 Bean 失败: " + getName(), e);
        }
    }

    /**
     * 初始化 Bean。
     *
     * <p>已存在实例时不需要额外处理，父类 {@code initializeBean()} 会通过
     * 覆盖的 {@link #createInstance()} 获取现有实例，依次执行注入和生命周期初始化。</p>
     *
     * @return 初始化后的 Bean 实例
     */
    @Override
    public Object initializeBean() {
        return super.initializeBean();
    }

    @Override
    public void destroyBean() {
        if (isDestroyed()) {
            return;
        }
        // 调用父类销毁（执行 BeanDefinitionLifecycleManager.destroy 处理）
        super.destroyBean();
        // 释放单例引用
        this.singletonInstance = null;
    }

    @Override
    public boolean isDestroyed() {
        // 父类 destroyed 标志即销毁完成
        return super.isDestroyed();
    }
}
