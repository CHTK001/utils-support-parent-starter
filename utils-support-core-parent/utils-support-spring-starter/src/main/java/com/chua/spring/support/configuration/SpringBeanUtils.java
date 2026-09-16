package com.chua.spring.support.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.util.PathMatcher;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.util.ReflectionUtils;

import com.chua.common.support.reflection.ReflectUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

import static org.springframework.util.ClassUtils.getAllInterfacesForClass;
import static org.springframework.util.ReflectionUtils.*;

/**
* Spring Bean 工具类，提供 application上下文 持有、Bean 获取注册、类型转换等功能。
* 该类封装了 Spring 容器的常用操作，支持线程安全的上下文管理、动态 Bean 注册与注销、
* 以及请求映射信息的查询等高级功能。
*
* <p>使用方式：
* <ul>
*   <li>在 Spring 容器启动时通过 {@link #setApplicationContext(ApplicationContext)} 设置上下文</li>
*   <li>或使用 {@link ApplicationAwareApplicationContextInitializer} 自动初始化</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class SpringBeanUtils {

    /**
    * 路径匹配器实例，用于 URL 模式匹配。
     */
    public static final PathMatcher MATCHER = new AntPathMatcher();

    /**
    * 应用上下文持有者，使用 thread本地 存储当前线程的 application上下文。
     */
    private static final ThreadLocal<ApplicationContext> APPLICATION_CONTEXT = new ThreadLocal<>();

    /**
    * 请求映射处理器映射静态引用。
     */
    private static volatile RequestMappingHandlerMapping requestMappingHandlerMappingStatic;

    /**
    * 设置应用上下文到当前线程的 thread本地 中。
    * 通常在 Spring 容器初始化时调用，以便后续工具方法能获取上下文。
    *
    * @param applicationContext 应用上下文实例
     */
    public static void setApplicationContext(ApplicationContext applicationContext) {
        APPLICATION_CONTEXT.set(applicationContext);
    }

    /**
    * 获取当前线程的应用上下文。
    *
    * @return 应用上下文实例
    * @throws IllegalStateException 如果上下文未初始化
     */
    public static ApplicationContext getApplicationContext() {
        ApplicationContext applicationContext = APPLICATION_CONTEXT.get();
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext 未初始化，请先调用 setApplicationContext()");
        }
        return applicationContext;
    }

    /**
    * 获取当前线程的应用上下文，允许返回 空。
    * 适用于不确定上下文是否已初始化的场景。
    *
    * @return 应用上下文实例，未初始化则返回 空
     */
    public static ApplicationContext getApplicationContextOrNull() {
        return APPLICATION_CONTEXT.get();
    }

    /**
    * 清除当前线程的 application上下文 引用。
    * 通常在请求结束或线程销毁时调用，防止内存泄漏。
     */
    public static void clearApplicationContext() {
        APPLICATION_CONTEXT.remove();
    }

    /**
    * 获取请求映射处理器映射
    *
    * @return RequestMappingHandlerMapping 实例
     */
    public static RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        return requestMappingHandlerMappingStatic;
    }

    /**
    * 设置请求映射处理器映射
    *
    * @param requestMappingHandlerMapping 请求mapping处理器mapping 实例
     */
    public static void setRequestMappingHandlerMapping(RequestMappingHandlerMapping requestMappingHandlerMapping) {
        requestMappingHandlerMappingStatic = requestMappingHandlerMapping;
    }

    /**
    * 通过反射查找方法
    *
    * @param clazz       类
    * @param name        方法名
    * @param paramTypes  参数类型
    * @return Method 对象，未找到返回 空
     */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        if (clazz == null || name == null) {
            return null;
        }
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }
    }

    /**
    * 根据类型从当前上下文中获取 Bean 实例。
    * 如果未找到或发生异常，返回 空。
    *
    * @param target Bean 的目标类型
    * @param <T>    Bean 的类型泛型
    * @return Bean 实例，未找到则返回 空
     */
    public static <T> T getBean(Class<T> target) {
        try {
            return getApplicationContext().getBean(target);
        } catch (BeansException e) {
            return null;
        }
    }

    /**
    * 根据类型从指定的应用上下文中获取 Bean 实例，若未找到则返回默认值。
    *
    * @param applicationContext 指定的应用上下文
    * @param target             Bean 的目标类型
    * @param defaultValue       未找到时的默认返回值
    * @param <T>                Bean 的类型泛型
    * @return Bean 实例或默认值
     */
    public static <T> T getBean(ApplicationContext applicationContext, Class<T> target, T defaultValue) {
        try {
            return applicationContext.getBean(target);
        } catch (BeansException ignored) {
        }
        return defaultValue;
    }

    /**
    * 根据类型从指定的应用上下文中获取 Bean 实例，若未找到则返回 空。
    *
    * @param applicationContext 指定的应用上下文
    * @param target             Bean 的目标类型
    * @param <T>                Bean 的类型泛型
    * @return Bean 实例，未找到则返回 空
     */
    public static <T> T getBean(ApplicationContext applicationContext, Class<T> target) {
        try {
            return applicationContext.getBean(target);
        } catch (BeansException ignored) {
        }
        return null;
    }

    /**
    * 根据名称和类型从当前上下文中获取 Bean 实例。
    *
    * @param name   Bean 的名称
    * @param target Bean 的目标类型
    * @param <T>    Bean 的类型泛型
    * @return Bean 实例
     */
    public static <T> T getBean(String name, Class<T> target) {
        return getApplicationContext().getBean(name, target);
    }

    /**
    * 获取指定类型的所有 Bean 实例集合。
    *
    * @param target Bean 的目标类型
    * @param <T>    Bean 的类型泛型
    * @return Bean 实例集合
     */
    public static <T> Collection<T> getBeanList(Class<T> target) {
        return getApplicationContext().getBeansOfType(target).values();
    }

    /**
    * 将控制器 Bean 动态注册到 请求mapping处理器mapping 中，使其能够处理 HTTP 请求。
    * 此方法常用于动态加载或热部署场景下的控制器注册。
    *
    * <p><strong>注意：</strong>此方法依赖 Spring 内部 API（{@code detectHandlerMethods}），
    * 在不同 Spring 版本中可能不兼容。建议仅在必要时使用，并充分测试。
    *
    * @param controllerBeanName           控制器的 Bean 名称
    * @param requestMappingHandlerMapping 请求映射处理器映射对象
    * @deprecated 依赖 Spring 内部 API，建议使用 Spring 官方动态注册机制
     */
    @Deprecated(since = "4.0.0.42", forRemoval = true)
    public static void registerController(String controllerBeanName, RequestMappingHandlerMapping requestMappingHandlerMapping) {
        Object controller = getApplicationContext().getBean(controllerBeanName);
        if (controller == null) {
            return;
        }
        try {
            Method method = findMethod(requestMappingHandlerMapping.getClass(), "detectHandlerMethods", Object.class);
            if (method != null) {
                ReflectUtils.invoke(requestMappingHandlerMapping, method.getName(), method.getReturnType(), controllerBeanName);
            }
        } catch (Exception e) {
            throw new RuntimeException("注册控制器失败: " + controllerBeanName, e);
        }
    }

    /**
    * 取消注册控制器，从 请求mapping处理器mapping 中移除该控制器的所有映射信息。
    * 此方法遍历控制器的所有声明方法，计算其对应的 请求mapping信息 并执行注销操作。
    *
    * <p><strong>注意：</strong>此方法依赖 Spring 内部 API（{@code getMappingForMethod}），
    * 在不同 Spring 版本中可能不兼容。建议仅在必要时使用，并充分测试。
    *
    * @param controllerBeanName           控制器的 Bean 名称
    * @param requestMappingHandlerMapping 请求映射处理器映射对象
    * @deprecated 依赖 Spring 内部 API，建议使用 Spring 官方动态注册机制
     */
    @Deprecated(since = "4.0.0.42", forRemoval = true)
    public static void unregisterController(String controllerBeanName, RequestMappingHandlerMapping requestMappingHandlerMapping) {
        Object controller = getApplicationContext().getBean(controllerBeanName);
        if (controller == null) {
            return;
        }
        final Class<?> targetClass = controller.getClass();
        doWithMethods(targetClass, method -> {
            try {
                Method createMappingMethod = findMethod(requestMappingHandlerMapping.getClass(), "getMappingForMethod", Method.class, Class.class);
                if (createMappingMethod != null) {
                    Object requestMappingInfo = ReflectUtils.invoke(requestMappingHandlerMapping, createMappingMethod.getName(), createMappingMethod.getReturnType(), method, targetClass);
                    if (requestMappingInfo != null && requestMappingInfo instanceof RequestMappingInfo) {
                        requestMappingHandlerMapping.unregisterMapping((RequestMappingInfo) requestMappingInfo);
                    }
                }
            } catch (Exception e) {
                log.error("[spring-configuration] 取消注册控制器方法失败: {}", method.getName(), e);
            }
        }, ReflectionUtils.USER_DECLARED_METHODS);
    }

    /**
    * 将自定义的 Bean 定义注册到 Spring 容器中。
    * 适用于需要在运行时动态创建和注册 Bean 的场景。
    *
    * @param beanName       Bean 的唯一标识名称
    * @param beanDefinition Bean 的定义对象，包含 Bean 的类名、作用域等信息
     */
    public static void registerBean(String beanName, BeanDefinition beanDefinition) {
        ConfigurableApplicationContext configurableApplicationContext = (ConfigurableApplicationContext) getApplicationContext();
        DefaultListableBeanFactory defaultListableBeanFactory = (DefaultListableBeanFactory) configurableApplicationContext.getBeanFactory();
        defaultListableBeanFactory.registerBeanDefinition(beanName, beanDefinition);
    }

    /**
    * 尝试将给定值转换为指定的 类型 类型。
    * 如果目标类型不是 类 类型，则返回 空。
    *
    * @param value 待转换的值
    * @param type  目标类型
    * @param <T>   目标类型泛型
    * @return 转换后的值，若无法转换或类型不匹配则返回 空
     */
    @SuppressWarnings("unchecked")
    public static <T> T convertIfNecessary(Object value, Type type) {
        return type instanceof Class ? convertIfNecessary(value, (Class<? extends T>) type) : null;
    }

    /**
    * 内部持有 转换服务 的单例类，延迟加载以避免循环依赖或空指针问题。
     */
    private static final class ConversionServiceHolder {
        /** 转换服务 */
        private static volatile ConversionService conversionService;

        /**
        * 获取
        *
        * @param applicationContext application上下文
        * @return 获取的结果
         */
        static ConversionService get(ApplicationContext applicationContext) {
            if (conversionService == null) {
                synchronized (ConversionServiceHolder.class) {
                    if (conversionService == null) {
                        conversionService = applicationContext.getBean(ConversionService.class);
                    }
                }
            }
            return conversionService;
        }
    }

    /**
    * 尝试将给定值转换为指定的 类 类型，利用 Spring 的 转换服务 进行转换。
    *
    * @param value 待转换的值
    * @param type  目标 类 类型
    * @param <T>   目标类型泛型
    * @return 转换后的值
     */
    public static <T> T convertIfNecessary(Object value, Class<T> type) {
        return ConversionServiceHolder.get(getApplicationContext()).convert(value, type);
    }

    /**
    * 获取当前 Spring 环境配置实例。
    *
    * @return 环境实例
     */
    public static Environment getEnvironment() {
        return getApplicationContext().getEnvironment();
    }

    /**
    * 根据提供的 URL 查找对应的 处理器方法。
    * 通过遍历 请求mapping处理器mapping 中的映射关系，使用 Ant路径匹配 进行模式匹配。
    *
    * @param requestMappingHandlerMapping 请求映射处理器映射对象
    * @param url                          请求的 URL 路径
    * @return 匹配的 处理器方法 实例，未找到则返回 空
     */
    public static HandlerMethod getMethodInfo(RequestMappingHandlerMapping requestMappingHandlerMapping, String url) {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            RequestMappingInfo requestMappingInfo = entry.getKey();
            PatternsRequestCondition patternsCondition = requestMappingInfo.getPatternsCondition();
            for (String pattern : Optional.ofNullable(patternsCondition.getPatterns()).orElse(Collections.emptySet())) {
                if (MATCHER.match(pattern, url)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
    * 获取当前服务器的端口号。
    * 从 环境 中读取 {@code server.port} 配置，默认值为 8080。
    *
    * @return 服务器端口号
     */
    public static int getPort() {
        String port = getEnvironment().getProperty("server.port", "8080");
        return Integer.parseInt(port);
    }

    /**
    * 获取当前应用的上下文路径（上下文 路径）。
    * 从 环境 中读取 {@code server.servlet.context-path} 配置，默认值为 "/"。
    *
    * @return 上下文路径字符串
     */
    public static String getContextPath() {
        return getEnvironment().getProperty("server.servlet.context-path", "/");
    }

    /**
    * 自动装配 Bean 的依赖字段和方法注入。
    * 类似于 Spring 容器对 Bean 的处理过程，可用于手动创建的 Bean 实例。
    *
    * @param bean 需要装配依赖的 Bean 实例
     */
    public static void autowireBean(Object bean) {
        getApplicationContext().getAutowireCapableBeanFactory().autowireBean(bean);
    }

    /**
    * 解析字符串中的占位符（如 ${键}），替换为实际的环境变量或配置值。
    *
    * @param value 包含占位符的原始字符串
    * @return 解析后的字符串，若输入为 空 则返回 空
     */
    public static String resolvePlaceholders(String value) {
        if (null == value) {
            return null;
        }
        return getEnvironment().resolvePlaceholders(value);
    }

    /**
    * 单例互斥锁的键名常量，用于在特定场景下获取或创建唯一的锁对象。
     */
    private static final String SINGLETON_MUTEX_KEY = "SINGLETON_MUTEX";

    /**
    * 获取单例 Bean 注册时的互斥锁对象，确保多线程环境下单例 Bean 的安全创建。
    * 尝试直接访问底层 API，若失败则通过反射或注册备用锁的方式实现兼容。
    *
    * @param applicationContext 应用上下文实例
    * @return 互斥锁对象
     */
    public static Object getSingletonMutex(ApplicationContext applicationContext) {
        DefaultSingletonBeanRegistry autowireCapableBeanFactory =
                (DefaultSingletonBeanRegistry) applicationContext.getAutowireCapableBeanFactory();
        try {
            return autowireCapableBeanFactory.getSingletonMutex();
        } catch (Throwable t1) {
            try {
                Method method = findMethod(DefaultSingletonBeanRegistry.class, "getSingletonMutex");
                if (method != null) {
                    return ReflectUtils.invoke(autowireCapableBeanFactory, method.getName(), method.getReturnType());
                }
            } catch (Throwable t2) {
                // 忽略反射异常，继续降级处理
            }
            if (!autowireCapableBeanFactory.containsSingleton(SINGLETON_MUTEX_KEY)) {
                autowireCapableBeanFactory.registerSingleton(SINGLETON_MUTEX_KEY, new Object());
            }
            return autowireCapableBeanFactory.getSingleton(SINGLETON_MUTEX_KEY);
        }
    }

    // ==================== 链式事件发布工具 ====================

    /**
    * 链式事件发布器，支持以流式 API 发布一个或多个应用事件。
     */
    public static final class EventPublisher {

        /** Application上下文 */
        private final ApplicationContext applicationContext;

        /**
        * 创建 事件发布 实例
        * @param applicationContext application上下文
        * @return 事件发布的结果
         */
        private EventPublisher(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        /**
        * 的
        *
        * @param applicationContext application上下文
        * @return 的的结果
         */
        public static EventPublisher of(ApplicationContext applicationContext) {
            return new EventPublisher(applicationContext);
        }

        /**
        * 的
        *
        * @return 的的结果
         */
        public static EventPublisher of() {
            return new EventPublisher(getApplicationContext());
        }

        /**
        * 发布
        *
        * @param event 事件
        * @return 发布的结果
         */
        public EventPublisher publish(Object event) {
            applicationContext.publishEvent(event);
            return this;
        }

        @SafeVarargs
        /**
        * 发布全部
        *
        * @param events 事件
        * @return 发布全部的结果
         */
        public final EventPublisher publishAll(Object... events) {
            for (Object event : events) {
                applicationContext.publishEvent(event);
            }
            return this;
        }
    }

    /**
    * 获取指定配置键的值，若不存在则抛出 illegal状态异常。
    *
    * @param key 配置键
    * @return 配置值
    * @throws IllegalStateException 若配置不存在或为空
     */
    public static String getRequiredProperty(String key) {
        String value = getEnvironment().getProperty(key);
        if (null == value) {
            throw new IllegalStateException("Required property '" + key + "' not found");
        }
        return value;
    }

    /**
    * 获取指定配置键的值，并转换为目标类型。
    *
    * @param key 配置键
    * @param type 目标类型
    * @param <T> 目标类型泛型
    * @return 转换后的配置值，若不存在则返回 空
     */
    public static <T> T getPropertyAs(String key, Class<T> type) {
        return getEnvironment().getProperty(key, type);
    }

    /**
    * 检查指定名称的 Bean 是否存在。
    *
    * @param beanName Bean 名称
    * @return 是否存在
     */
    public static boolean isBeanPresent(String beanName) {
        return getApplicationContext().containsBean(beanName);
    }

    /**
    * 获取底层可配置的 Bean工厂。
    *
    * @return ConfigurableListableBeanFactory 实例
     */
    public static ConfigurableListableBeanFactory getBeanFactory() {
        return ((ConfigurableApplicationContext) getApplicationContext()).getBeanFactory();
    }

    // ==================== 事务模板工具 ====================

    /**
    * 获取默认事务管理器。
    *
    * @return PlatformTransactionManager 实例
     */
    public static PlatformTransactionManager transactionManager() {
        return getApplicationContext().getBean(PlatformTransactionManager.class);
    }

    /**
    * 获取事务模板。
    *
    * @return TransactionTemplate 实例
     */
    public static TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager());
    }

    /**
    * 获取事务模板并指定传播行为。
    *
    * @param propagationBehavior 传播行为，见 {@link TransactionDefinition}
    * @return TransactionTemplate 实例
     */
    public static TransactionTemplate transactionTemplate(int propagationBehavior) {
        TransactionTemplate template = transactionTemplate();
        template.setPropagationBehavior(propagationBehavior);
        return template;
    }

    // ==================== 配置属性绑定 ====================

    /**
    * 将指定前缀的配置属性绑定到目标类型实例。
    * 依赖 Spring Boot 的 {@code Binder}；若不可用则回退到 {@link org.springframework.beans.BeanWrapper}。
    *
    * @param prefix 配置前缀
    * @param type 目标类型
    * @param <T> 目标类型泛型
    * @return 绑定后的实例
     */
    public static <T> T bindProperties(String prefix, Class<T> type) {
        T instance = org.springframework.beans.BeanUtils.instantiateClass(type);
        org.springframework.beans.BeanWrapper bw = new org.springframework.beans.BeanWrapperImpl(instance);
        Environment env = getEnvironment();
        String targetPrefix = (null == prefix || prefix.isBlank()) ? "" : (prefix.endsWith(".") ? prefix : prefix + ".");
        for (java.beans.PropertyDescriptor pd : bw.getPropertyDescriptors()) {
            String propName = pd.getName();
            if (!bw.isWritableProperty(propName)) {
                continue;
            }
            String key = targetPrefix + propName;
            if (env.containsProperty(key)) {
                Object value = env.getProperty(key, pd.getPropertyType());
                bw.setPropertyValue(propName, value);
            }
        }
        return instance;
    }

    // ==================== AOP 代理工具 ====================

    /**
    * 获取 AOP 代理后的目标对象（若为目标类则直接返回）。
    *
    * @param bean Bean 实例
    * @return 目标对象
     */
    public static Object getTargetBean(Object bean) {
        return AopProxyUtils.getSingletonTarget(bean) != null ? AopProxyUtils.getSingletonTarget(bean) : bean;
    }

    /**
    * 获取目标对象的实际类。
    *
    * @param bean Bean 实例
    * @return 目标类
     */
    public static Class<?> getTargetClass(Object bean) {
        Object target = getTargetBean(bean);
        return target != null ? target.getClass() : bean.getClass();
    }

    /**
    * 判断 Bean 是否为 AOP 代理。
    *
    * @param bean Bean 实例
    * @return 是否为代理
     */
    public static boolean isProxy(Object bean) {
        return AopUtils.isAopProxy(bean);
    }

    // ==================== 异步执行器 ====================

    /**
    * 获取名为 {@code taskExecutor} 的 任务执行器；若不存在则返回一个轻量默认实现。
    *
    * @return TaskExecutor 实例
     */
    public static TaskExecutor getTaskExecutor() {
        if (getApplicationContext().containsBean("taskExecutor")) {
            return getApplicationContext().getBean("taskExecutor", TaskExecutor.class);
        }
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("spring-support-");
        executor.setConcurrencyLimit(0);
        return executor;
    }

    /**
    * 异步执行任务。
    *
    * @param task 任务
     */
    public static void runAsync(Runnable task) {
        getTaskExecutor().execute(task);
    }

    /**
    * 使用指定执行器异步执行任务。
    *
    * @param task 任务
    * @param executor 执行器
     */
    public static void runAsync(Runnable task, TaskExecutor executor) {
        executor.execute(task);
    }

    // ==================== Bean 生命周期钩子 ====================

    /**
    * 注册关闭钩子回调。
    *
    * @param callback 回调
     */
    public static void registerShutdownHook(Runnable callback) {
        ((ConfigurableApplicationContext) getApplicationContext()).registerShutdownHook();
        getApplicationContext().getAutowireCapableBeanFactory().autowireBean(callback);
        Thread shutdownHook = new Thread(callback, "spring-support-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
    * 注册应用事件监听器。
    *
    * @param listener 监听器
     */
    public static void addApplicationListener(org.springframework.context.ApplicationListener<?> listener) {
        ((ConfigurableApplicationContext) getApplicationContext()).addApplicationListener(listener);
    }

    // ==================== 国际化 MessageSource ====================

    /**
    * 获取 消息源 工具。
    *
    * @return MessageSourceAccessor 实例
     */
    public static MessageSourceAccessor getMessageSource() {
        MessageSource messageSource = getApplicationContext();
        return new MessageSourceAccessor(messageSource, Locale.getDefault());
    }

    /**
    * 根据编码获取国际化消息。
    *
    * @param code 消息编码
    * @param args 参数
    * @return 消息
     */
    public static String getMessage(String code, Object... args) {
        return getMessageSource().getMessage(code, args);
    }

    /**
    * 根据编码和区域获取国际化消息。
    *
    * @param code 消息编码
    * @param args 参数
    * @param locale 区域
    * @return 消息
     */
    public static String getMessage(String code, Object[] args, Locale locale) {
        return getMessageSource().getMessage(code, args, locale);
    }

    // ==================== 条件化 Bean 查询 ====================

    /**
    * 获取所有带有指定注解的 Bean。
    *
    * @param annotationType 注解类型
    * @param <T> Bean 类型
    * @return Bean 实例 映射
     */
    @SuppressWarnings("unchecked")
    public static <T> Map<String, T> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        return (Map<String, T>) getApplicationContext().getBeansWithAnnotation(annotationType);
    }

    /**
    * 按名称查找 Bean，若不存在则返回 空。
    *
    * @param name Bean 名称
    * @param <T> Bean 类型
    * @return Bean 实例或 空
     */
    @SuppressWarnings("unchecked")
    public static <T> T findBean(String name) {
        try {
            return (T) getApplicationContext().getBean(name);
        } catch (BeansException e) {
            return null;
        }
    }
}
