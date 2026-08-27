package com.chua.common.support.objects.definition;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Bean 方法定义，同时也是 {@link BeanDefinition} 子类。
 *
 * <p>作为方法级的 Bean 定义，对应工厂方法（如 {@code @Bean}）的返回结果。
 * {@link #getBean()} 通过调用所属 Bean 的工厂方法创建实例，跳过 Ioc 生命周期。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MethodDefinition extends AbstractBeanDefinition {

    /**
     * 父 Bean 定义（工厂方法所属的 Bean）。
     */
    private final BeanDefinition parentBeanDefinition;

    /**
     * Java 反射方法。
     */
    private final Method method;

    /**
     * 缓存的方法返回实例。
     */
    private volatile Object result;

    /**
     * 创建方法定义。
     *
     * @param parentBeanDefinition 所属 Bean 定义
     * @param method               Java 反射方法
     */
    public MethodDefinition(BeanDefinition parentBeanDefinition, Method method) {
        super(buildName(parentBeanDefinition, method), method.getReturnType(), BeanScope.SINGLETON);
        this.parentBeanDefinition = parentBeanDefinition;
        this.method = method;
        setPriority(parentBeanDefinition.getPriority());
    }

    /** 构建Name */
    private static String buildName(BeanDefinition parentBeanDefinition, Method method) {
        return parentBeanDefinition.getName() + "." + method.getName();
    }

    /**
     * 获取父 Bean 定义（工厂方法所属的 Bean）。
     *
     * @return 父 Bean 定义
     */
    public BeanDefinition getParentBeanDefinition() {
        return parentBeanDefinition;
    }

    /**
     * 获取 Java 方法。
     *
     * @return Java 方法
     */
    public Method getMethod() {
        return method;
    }

    /**
     * 反射调用当前 Bean 实例上的方法。
     *
     * @param target 调用目标实例
     * @param args   调用参数
     * @return 方法返回值
     * @throws InvocationTargetException 业务方法抛出异常时抛出
     * @throws IllegalAccessException    方法不可访问时抛出
     */
    public Object invoke(Object target, Object... args) throws InvocationTargetException, IllegalAccessException {
        if (target == null) {
            throw new IllegalStateException("Bean 实例不存在: " + getName());
        }
        ClassUtils.setAccessible(method);
        return ReflectUtils.invoke(target, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
    }

    @Override
    /** 创建Instance */
    public Object createInstance() {
        try {
            Object parent = parentBeanDefinition.getBean();
            if (parent == null) {
                return null;
            }
            return invoke(parent);
        } catch (Exception e) {
            throw new RuntimeException("工厂方法调用失败: " + getName(), e);
        }
    }

    @Override
    /** Do获取Bean */
    protected Object doGetBean() {
        return result;
    }

    @Override
    /** 设置Bean */
    protected void setBean(Object bean) {
        this.result = bean;
    }

    @Override
    /** 初始化Bean */
    public Object initializeBean() {
        if (isInitialized()) {
            return doGetBean();
        }
        Object bean = createInstance();
        if (bean == null) {
            return null;
        }
        setBean(bean);
        return bean;
    }

    @Override
    /** 销毁Bean */
    public void destroyBean() {
        if (isDestroyed()) {
            return;
        }
        super.destroyBean();
        this.result = null;
    }

    @Override
    /** 是否Destroyed */
    public boolean isDestroyed() {
        return super.isDestroyed();
    }
}
