package com.chua.springboot.support.context;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.ObjectContextConfig;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.MethodDefinition;
import com.chua.common.support.objects.definition.TypeBeanDefinition;
import com.chua.common.support.objects.environment.Environment;
import com.chua.common.support.objects.provider.ObjectProvider;
import com.chua.common.support.objects.publisher.EventPublisher;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanDefinitionRegistry;
import com.chua.common.support.objects.scanner.ObjectContextScanner;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.context.ApplicationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.chua.common.support.objects.environment.EnvironmentChangeListener;

/**
 * Spring Boot 集成 对象上下文 — 桥接 Spring application上下文 与核心容器。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>将 Spring {@link ApplicationContext} 的 Bean 管理能力暴露给 {@link ObjectContext} 体系</li>
 *   <li>支持 SPI 发现、注解扫描、包扫描等 ObjectContext 标准能力</li>
 *   <li>提供统一的 Bean 查找路径：Spring → 本地注册表 → SPI</li>
 * </ul>
 *
 * <p><b>边界</b>：本类不感知任何具体容器实现（如 OSGi、远程节点），
 * 外部容器由消费方通过 {@link ObjectContext#registerBean(BeanDefinitionRegister)} 自行挂接。</p>
 *
 * <h2>Bean 查找优先级</h2>
 * <pre>
 *   getBean(name, type)
 *     ├─ 1. Spring ApplicationContext.getBean(name, type)
 *     ├─ 2. 本地 BeanDefinitionRegistry.getBean(name, type)
 *     └─ 3. SPI ServiceProvider.getExtension(type)
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SpringBootObjectContext implements ObjectContext {

    /**
     * Spring 应用上下文引用
     */
    private final ApplicationContext applicationContext;

    /**
     * 本地 Beandefinitionregistry（用于非 Spring 管理的 Bean）
     */
    private final BeanDefinitionRegistry localRegistry;

    /**
     * 环境配置
     */
    private Environment environment;

    /**
     * 事件发布器，每个 springboot对象上下文 实例独立持有
     */
    private final EventPublisher eventPublisher = new EventPublisher();

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 构造 springboot对象上下文。
     *
     * @param applicationContext Spring application上下文
     * @param config             容器配置
     */
    public SpringBootObjectContext(ApplicationContext applicationContext, ObjectContextConfig config) {
        this.applicationContext = applicationContext;
        this.localRegistry = new BeanDefinitionRegistry();
        this.localRegistry.initialize(config != null && config.isSpiEnabled());

        // 初始化自身配置
        if (config != null) {
            setConfig(config);
        } else {
            init();
        }

        // 执行包扫描（如果配置了）
        if (config != null && config.shouldScan()) {
            scan(config.getScanPackages());
        }

        log.info("[springboot-context] 初始化完成，Spring Bean: {}",
                applicationContext.getBeanDefinitionCount());
    }

    // ==================== Bean 获取（多级查找） ====================

    /**
     * 获取Bean
    */
    @Override
    public <T> T getBean(String name, Class<T> type) {
 // 1. Spring application上下文
        try {
            if (applicationContext.containsBean(name)) {
                return applicationContext.getBean(name, type);
            }
        } catch (Exception e) {
            log.trace("[springboot-context] Spring getBean('{}') failed: {}", name, e.getMessage());
        }

 // 2. 按类型从 Spring 查找（名称 可能不匹配 Spring Bean 名称）
        try {
            T bean = applicationContext.getBean(type);
            if (bean != null) {
                return bean;
            }
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }

        // 3. 本地注册表
        try {
            return localRegistry.getBean(name, type);
        } catch (Exception e) {
            log.trace("[springboot-context] localRegistry getBean('{}') failed: {}", name, e.getMessage());
        }

        // 4. SPI 作为最后兜底
        try {
            return ServiceProvider.of(type).getDefault();
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 获取Bean的类型
    */
    @Override
    public <T> T getBeanOfType(Class<T> type) {
        // 1. Spring
        try {
            return applicationContext.getBean(type);
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }

        // 2. 本地注册表
        try {
            return localRegistry.getBeanOfType(type);
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }

        // 3. SPI
        try {
            return ServiceProvider.of(type).getDefault();
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 获取Bean的类型safely
    */
    @Override
    public <T> T getBeanOfTypeSafely(Class<T> type) {
        try {
            return getBeanOfType(type);
        } catch (Exception e) {
            log.trace("[springboot-context] getBeanOfTypeSafely({}) failed: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 获取Bean的类型
    */
    @Override
    public <T> Map<String, T> getBeanOfTypes(Class<T> type) {
        Map<String, T> result = Wraps.linkedMap();

        // 1. Spring
        try {
            result.putAll(applicationContext.getBeansOfType(type));
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }

        // 2. 本地注册表
        try {
            result.putAll(localRegistry.getBeansOfType(type));
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 获取Bean的类型集合
    */
    @Override
    public <T> Collection<T> getBeanOfTypeCollection(Class<T> type) {
        return getBeanOfTypes(type).values();
    }

    /**
     * 获取Beanwith注解
    */
    @Override
    public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        return applicationContext.getBeansWithAnnotation(annotationType);
    }

    /**
     * 获取方法with注解
    */
    @Override
    public List<MethodDefinition> getMethodWithAnnotation(Class<? extends Annotation> annotationType) {
        List<MethodDefinition> result = Wraps.list();
        // 1. Spring 容器中标注了该类注解的 Bean 上的方法
        try {
            Map<String, Object> springBeans = applicationContext.getBeansWithAnnotation(annotationType);
            for (Map.Entry<String, Object> e : springBeans.entrySet()) {
                collectMethodDefinitions(result, e.getKey(), e.getValue(), annotationType);
            }
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }
        // 2. 本地注册表中通过方法注解查找
        Map<String, BeanDefinition> byMethod = localRegistry.getBeansWithMethodAnnotation(annotationType);
        for (Map.Entry<String, BeanDefinition> e : byMethod.entrySet()) {
            BeanDefinition def = e.getValue();
            if (def == null) {
                continue;
            }
            try {
                Object bean = def.getBean();
                if (bean != null) {
                    collectMethodDefinitions(result, e.getKey(), bean, annotationType);
                }
            } catch (Exception lookupEx) {
                log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", lookupEx.getMessage());
            }
        }
        return result;
    }

    /**
     * 收集 Bean 上标注了指定注解的方法，包装为 方法definition。
     *
     * @param result         输出列表
     * @param beanName       Bean 名称
     * @param bean           Bean 实例
     * @param annotationType 注解类型
     */
    private void collectMethodDefinitions(List<MethodDefinition> result, String beanName, Object bean,
                                          Class<? extends Annotation> annotationType) {
        if (bean == null) {
            return;
        }
        Class<?> beanClass = bean.getClass();
        for (Method method : beanClass.getMethods()) {
            if (method.isBridge() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.isAnnotationPresent(annotationType)) {
 // 通过 类型Beandefinition 复用 方法definition 引用父 Beandefinition 的能力
                BeanDefinition parent = TypeBeanDefinition.of(beanClass, beanName);
                result.add(new MethodDefinition(parent, method));
            }
        }
    }

    /**
     * Autowire
    */
    @Override
    public void autowire(Object bean) {
        if (bean == null) {
            return;
        }
 // 利用 Spring 的 autowirecapableBean工厂 进行依赖注入
        try {
            applicationContext.getAutowireCapableBeanFactory().autowireBean(bean);
        } catch (Exception e) {
            log.trace("[springboot-context] autowire failed: {}", e.getMessage());
        }
    }

    // ==================== Bean 存在性检查 ====================

    /**
     * containsBean
    */
    @Override
    public boolean containsBean(String name) {
        if (applicationContext.containsBean(name)) {
            return true;
        }
        return localRegistry.containsBean(name);
    }

    /**
     * 是否单例
    */
    @Override
    public boolean isSingleton(String name) {
        if (applicationContext.containsBean(name)) {
            return applicationContext.isSingleton(name);
        }
        return localRegistry.isSingleton(name);
    }

    /**
     * 获取Beandefinition名称
    */
    @Override
    public Collection<String> getBeanDefinitionNames() {
        Set<String> names = Wraps.linkedSet();
        names.addAll(Arrays.asList(applicationContext.getBeanDefinitionNames()));
        names.addAll(localRegistry.getBeanDefinitionNames());
        return names;
    }

    /**
     * 获取Beandefinition计算数量
    */
    @Override
    public int getBeanDefinitionCount() {
        return applicationContext.getBeanDefinitionCount() + localRegistry.getBeanDefinitionCount();
    }

    /**
     * 是否拥有Bean的类型
    */
    @Override
    public <T> boolean hasBeanOfType(Class<T> type) {
        try {
            return !applicationContext.getBeansOfType(type).isEmpty();
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }
        return localRegistry.hasBeanOfType(type);
    }

    /**
     * 获取Bean名称
    */
    @Override
    public Collection<String> getBeanNames(Class<?> type) {
        Set<String> names = Wraps.linkedSet();
        try {
            names.addAll(Arrays.asList(applicationContext.getBeanNamesForType(type)));
        } catch (Exception e) {
            log.debug("[SpringBootObjectContext] 操作失败，已忽略: {}", e.getMessage());
        }
        names.addAll(localRegistry.getBeanNames(type));
        return names;
    }

    /**
     * 获取Bean提供者
    */
    @Override
    public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
        return () -> getBeanOfType(requiredType);
    }

    // ==================== Environment ====================

    /**
     * 获取环境
    */
    @Override
    public Environment getEnvironment() {
        if (environment == null) {
            environment = new SpringBootEnvironment(applicationContext);
        }
        return environment;
    }

    // ==================== 事件发布 ====================

    /**
     * 发布
    */
    @Override
    public int publish(Object event) {
        if (event == null) {
            return 0;
        }
        // 同步发布到 Spring 容器
        try {
            applicationContext.publishEvent(event);
        } catch (Exception e) {
            log.trace("[springboot-context] Spring publishEvent failed: {}", e.getMessage());
        }
        // 同时触发自有监听器
        return eventPublisher.publish(event);
    }

    /**
     * 获取事件发布
    */
    @Override
    public EventPublisher getEventPublisher() {
        return eventPublisher;
    }

    // ==================== Bean 注册 ====================

    /**
     * 注册Bean
    */
    @Override
    public void registerBean(Object bean) {
        if (bean == null) {
            return;
        }
        // 1. 注册到本地注册表
        ObjectContext.super.registerBean(bean);

        // 2. 同步到 Spring 容器（如果尚未注册），使后续 @Autowired 可以注入
        try {
            SpringObjectContextBridge.registerIfAbsent(applicationContext, bean);
        } catch (Exception e) {
            log.trace("[springboot-context] 同步到 Spring 失败: {}", e.getMessage());
        }
    }

    /**
     * 注册Bean
    */
    @Override
    public void registerBean(Class<?> type) {
        ObjectContext.super.registerBean(type);
    }

    /**
     * 注册Bean
    */
    @Override
    public void registerBean(BeanDefinition beanDefinition) {
        ObjectContext.super.registerBean(beanDefinition);
    }

    /**
     * 注册Bean
    */
    @Override
    public boolean registerBean(BeanDefinitionRegister register) {
        if (register == null) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException("BeanDefinitionRegister 不能为空");
        }
        return getRegistry(getConfig().isSpiEnabled()).addRegister(register);
    }

    // ==================== 包扫描 ====================

    /**
     * 扫描
    */
    @Override
    public void scan(String basePackage) {
        ObjectContextScanner.scan(this, basePackage);
    }

    /**
     * 扫描
    */
    @Override
    public void scan(List<String> basePackages) {
        ObjectContextScanner.scan(this, basePackages);
    }

    // ==================== 注册中心 ====================

    /**
     * 获取Registry
    */
    @Override
    public BeanDefinitionRegistry getRegistry() {
        return localRegistry;
    }

    /**
     * 获取Registry
    */
    @Override
    public BeanDefinitionRegistry getRegistry(boolean spiEnabled) {
        return localRegistry;
    }

    // ==================== Spring ApplicationContext 访问 ====================

    /**
     * 获取 Spring application上下文。
     *
     * @return Spring application上下文
     */
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭上下文，释放资源。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // 清理本地注册表（不销毁 Bean，但清空缓存）
        try {
            localRegistry.clear();
        } catch (Exception e) {
            log.warn("[springboot-context] 清理本地注册表失败: {}", e.getMessage(), e);
        }

 // 调用接口默认实现清理 配置_HOLDER / REGISTRY_HOLDER
        ObjectContext.super.close();
        log.info("[springboot-context] 已关闭");
    }

    /**
     * 是否Closed
    */
    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== Spring Environment 适配器 ====================

    /**
     * Spring 环境 适配器 — 将 Spring 的 环境 适配为 对象上下文 的 环境。
     * @author CH
     * @since 4.0.0
     */
    private static class SpringBootEnvironment implements Environment {

        /**
         * springenv
        */
        private final org.springframework.core.env.Environment springEnv;

        SpringBootEnvironment(ApplicationContext applicationContext) {
            this.springEnv = applicationContext.getEnvironment();
        }

        /**
         * 获取财产
        */
        @Override
        public String getProperty(String key) {
            return springEnv.getProperty(key);
        }

        /**
         * 获取财产
        */
        @Override
        public String getProperty(String key, String defaultValue) {
            return springEnv.getProperty(key, defaultValue);
        }

        /**
         * 获取财产
        */
        @Override
        public <T> T getProperty(String key, Class<T> targetType) {
            return springEnv.getProperty(key, targetType);
        }

        /**
         * 获取财产
        */
        @Override
        public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
            T value = springEnv.getProperty(key, targetType);
            return value != null ? value : defaultValue;
        }

        /**
         * 设置财产
        */
        @Override
        public void setProperty(String key, Object value) {
 // Spring 环境 不支持直接 设置财产，留空
        }

        /**
         * contains财产
        */
        @Override
        public boolean containsProperty(String key) {
            return springEnv.containsProperty(key);
        }

        /**
         * 添加改变监听器
        */
        @Override
        public void addChangeListener(EnvironmentChangeListener listener) {
 // Spring 环境 适配器暂不实现监听器
        }

        /**
         * 移除改变监听器
        */
        @Override
        public void removeChangeListener(EnvironmentChangeListener listener) {
 // Spring 环境 适配器暂不实现监听器
        }

        /**
         * 添加配置源
        */
        @Override
        public void addConfigSource(com.chua.common.support.config.source.PropertySource propertySource) {
 // Spring 环境 适配器暂不实现 配置源 动态添加
        }

        /**
         * 移除配置源
        */
        @Override
        public void removeConfigSource(com.chua.common.support.config.source.PropertySource propertySource) {
 // Spring 环境 适配器暂不实现 配置源 动态移除
        }

        /**
         * Refresh
        */
        @Override
        public void refresh() {
 // Spring 环境 不需要主动刷新配置源
        }
    }
}
