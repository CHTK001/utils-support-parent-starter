package com.chua.common.support.objects.definition;

import com.chua.common.support.objects.register.BeanDefinitionRegister;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bean 定义接口。
 * <p>
 * 描述一个 Bean 的完整元数据信息，包括名称、类型、作用域、注解信息等。
 * 是容器中 Bean 的核心抽象，类似于 Spring 的 Beandefinition。
 * </p>
 * <p>
 * Bean 的生命周期：
 * <ol>
 *   <li>创建 BeanDefinition（通过 {@link com.chua.common.support.objects.generator.BeanDefinitionGenerator}）</li>
 *   <li>注册到 {@link BeanDefinitionRegister}</li>
 *   <li>调用 {@link #createInstance()} 创建原始实例</li>
 *   <li>调用 {@link #initializeBean()} 完成初始化（注入 + 生命周期回调）</li>
 *   <li>容器关闭时调用 {@link #destroyBean()} 销毁</li>
 * </ol>
 * </p>
 *
 * @author CH
 * @since 2024/12/20
*/
public interface BeanDefinition {

    BeanDefinition EMPTY_BEAN_DEFINITION = new AbstractBeanDefinition() {
    };

    /**
    * 获取 Bean 的唯一标识名称。
    *
    * @return Bean 名称
    */
    String getName();

    /**
    * 获取 Bean 类型的全限定类名。
    *
    * @return Bean 类型全限定名
    */
    String getType();

    /**
    * 获取当前 Bean 的作用域配置。
    *
    * @return Bean 作用域
    */
    BeanScope getScope();

    /**
    * 设置 Bean 的作用域范围。
    *
    * @param scope 新的作用域
    */
    void setScope(BeanScope scope);

    /**
    * 获取 Bean 对应的具体 Java 类对象。
    *
    * @return Bean 类
    */
    Class<?> getBeanClass();

    /**
    * 获取已创建的 Bean 实例引用。
    *
    * @return Bean 实例，若未创建则返回 空
    */
    Object getBean();

    /**
    * 获取用于加载 Bean 类的类加载器。
    *
    * @return 类加载器
    */
    ClassLoader getClassLoader();

    /**
    * 检查当前 Bean 是否标注了指定的注解类型。
    *
    * @param annotationType 要检查的注解类型
    * @return 如果存在该注解则返回 true，否则返回 false
    */
    boolean isAnnotationPresent(Class<? extends Annotation> annotationType);

    /**
    * 获取当前 Bean 上标注的指定类型的注解。
    *
    * @param annotationType 要获取的注解类型
    * @param <T>            注解的泛型类型
    * @return 注解实例，如果不存在则返回 空
    */
    <T extends Annotation> T getAnnotation(Class<T> annotationType);

    /**
    * 检查当前 Bean 是否标注了指定类名的注解（支持跨 类加载）。
    *
    * @param annotationTypeName 注解的全限定类名
    * @return 如果存在该注解则返回 true，否则返回 false
    */
    boolean isAnnotationPresent(String annotationTypeName);

    /**
    * 获取当前 Bean 上标注的指定类名的注解（支持跨 类加载）。
    *
    * @param annotationTypeName 注解的全限定类名
    * @return 注解实例，如果不存在则返回 空
    */
    Annotation getAnnotation(String annotationTypeName);

    /**
    * 根据元数据创建一个全新的 Bean 实例。
    *
    * @return 新创建的 Bean 实例
    */
    Object createInstance();

    /**
    * 获取当前 Bean 定义关联的注册器。
    *
    * @return 注册器
    */
    BeanDefinitionRegister getRegister();

    /**
    * 设置当前 Bean 定义关联的注册器。
    *
    * @param register 注册器
    */
    void setRegister(BeanDefinitionRegister register);

    /**
    * 检查当前 Bean 定义是否处于可用状态。
    *
    * @return 如果可用则返回 true，否则返回 false
    */
    boolean isAvailable();

    /**
    * 设置当前 Bean 定义的可用状态。
    *
    * @param available 新的可用状态
    */
    void setAvailable(boolean available);

    /**
    * 检查当前 Bean 类型是否可以赋值给指定的目标类型。
    *
    * @param clazz 目标类型
    * @return 如果可以赋值则返回 true，否则返回 false
    */
    boolean isAssignableFrom(Class<?> clazz);

    /**
    * 检查当前 Bean 类型是否可以赋值给指定的目标类型（按类名字符串）。
    *
    * @param clazz 目标类型的类名字符串
    * @return 如果可以赋值则返回 true，否则返回 false
    */
    boolean isAssignableFrom(String clazz);

    /**
    * 检查当前 Bean 类型是否可以被指定的源类型赋值（即是否是源类型的子类或实现类）。
    *
    * @param clazz 源类型的类名字符串
    * @return 如果可以赋值则返回 true，否则返回 false
    */
    boolean isAssignableTo(String clazz);

    /**
    * 检查当前 Bean 类型是否可以被指定的源类型赋值（即是否是源类型的子类或实现类）。
    *
    * @param clazz 源类型
    * @return 如果可以赋值则返回 true，否则返回 false
    */
    boolean isAssignableTo(Class<?> clazz);

    /**
    * 检查当前 Bean 类型是否与指定的类型完全一致。
    *
    * @param clazz 要比较的类型
    * @return 如果类型一致则返回 true，否则返回 false
    */
    boolean isType(Class<?> clazz);

    /**
    * 检查当前 Bean 类型是否与指定的类名字符串完全一致。
    *
    * @param clazz 要比较的类名字符串
    * @return 如果类型一致则返回 true，否则返回 false
    */
    boolean isType(String clazz);

    /**
    * 获取当前 Bean 类中所有标注了指定注解的方法列表。
    *
    * @param annotationType 要查找的注解类型
    * @return 包含匹配方法的列表
    */
    List<Method> getMethodsWithAnnotation(Class<? extends Annotation> annotationType);

    /**
    * 获取当前 Bean 类中所有标注了指定注解的方法列表（按类名）。
    *
    * @param annotationTypeName 要查找的注解类名
    * @return 包含匹配方法的列表
    */
    List<Method> getMethodsWithAnnotation(String annotationTypeName);

    /**
    * 获取当前 Bean 类的所有方法定义信息。
    *
    * @return 方法定义列表
    */
    List<MethodDefinition> getMethodDefinitions();

    /**
    * 根据 Java 方法 对象获取对应的详细方法定义。
    *
    * @param method Java 反射方法对象
    * @return 方法定义对象
    */
    MethodDefinition getMethodDefinition(Method method);

    /**
    * 获取当前 Bean 类中第一个标注了指定注解的方法。
    *
    * @param annotationType 要查找的注解类型
    * @return 找到的方法，如果未找到则返回 空
    */
    Method getMethodWithAnnotation(Class<? extends Annotation> annotationType);

    /**
    * 获取当前 Bean 类中第一个标注了指定注解的方法（按类名）。
    *
    * @param annotationTypeName 要查找的注解类名
    * @return 找到的方法，如果未找到则返回 空
    */
    Method getMethodWithAnnotation(String annotationTypeName);

    /**
    * 检查当前 Bean 类中是否存在标注了指定注解的方法。
    *
    * @param annotationType 要查找的注解类型
    * @return 如果存在该方法则返回 true，否则返回 false
    */
    boolean hasMethodWithAnnotation(Class<? extends Annotation> annotationType);

    /**
    * 检查当前 Bean 类中是否存在标注了指定注解的方法（按类名）。
    *
    * @param annotationTypeName 要查找的注解类名
    * @return 如果存在该方法则返回 true，否则返回 false
    */
    boolean hasMethodWithAnnotation(String annotationTypeName);

    /**
    * 检查当前 Bean 是否已经完成初始化流程。
    *
    * @return 如果已初始化则返回 true，否则返回 false
    */
    boolean isInitialized();

    /**
    * 检查当前 Bean 是否已经执行了销毁操作。
    *
    * @return 如果已销毁则返回 true，否则返回 false
    */
    boolean isDestroyed();

    /**
    * 执行 Bean 的初始化逻辑，包括依赖注入和生命周期回调。
    *
    * @return 初始化后的 Bean 实例
    */
    Object initializeBean();

    /**
    * 执行 Bean 的销毁逻辑，释放相关资源。
    */
    void destroyBean();

    /**
    * 判断当前 Bean 定义是否启用了代理模式。
    *
    * @return 如果启用代理则返回 true，默认返回 true
    */
    default boolean isProxy() {
        return true;
    }

    /**
    * 设置当前 Bean 定义是否启用代理模式。
    *
    * @param proxy 是否启用代理
    */
    void setProxy(boolean proxy);

    /**
    * 获取排序优先级，值越大优先级越高。
    * 默认实现委托给 获取priority()。
    *
    * @return 优先级数值
    */
    default int order() {
        return getPriority();
    }

    /**
    * 获取 Bean 定义的优先级数值，值越大表示优先级越高。
    *
    * @return 优先级数值
    */
    int getPriority();
}
