package com.chua.springboot.support.context;

import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.ObjectContextConfig;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.MethodDefinition;
import com.chua.common.support.objects.definition.TypeBeanDefinition;
import com.chua.common.support.objects.environment.Environment;
import com.chua.common.support.objects.provider.ObjectProvider;
import com.chua.common.support.objects.publisher.EventPublisher;
import com.chua.common.support.objects.register.BeanDefinitionRegistry;
import com.chua.common.support.objects.scanner.ObjectContextScanner;
import com.chua.common.support.osgi.BundleApplication;
import com.chua.common.support.osgi.BundleContext;
import com.chua.common.support.osgi.OsgiLauncher;
import com.chua.common.support.osgi.OsgiLauncherHolder;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.context.ApplicationContext;

import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Spring Boot 集成 ObjectContext — 桥接 Spring ApplicationContext 与 OSGI 模块上下文。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>将 Spring {@link ApplicationContext} 的 Bean 管理能力暴露给 {@link ObjectContext} 体系</li>
 *   <li>集成 OSGI 模块上下文：
 *     <ul>
 *       <li>启动时自动发现并启动 OSGI 框架（如果 classpath 存在 OSGI 实现）</li>
 *       <li>将 OSGI 中注册的服务注入到 Spring 容器中</li>
 *       <li>将 Spring 容器中的 Bean 暴露给 OSGI 服务注册表</li>
 *     </ul>
 *   </li>
 *   <li>支持 SPI 发现、注解扫描、包扫描等 ObjectContext 标准能力</li>
 *   <li>提供统一的 Bean 查找路径：Spring → OSGI → SPI → 本地注册表</li>
 * </ul>
 *
 * <h2>Bean 查找优先级</h2>
 * <pre>
 *   getBean(name, type)
 *     ├─ 1. Spring ApplicationContext.getBean(name, type)
 *     ├─ 2. OSGI OsgiLauncher.getService(type)（按 name 过滤）
 *     ├─ 3. 本地 BeanDefinitionRegistry.getBean(name, type)
 *     └─ 4. SPI ServiceProvider.getExtension(type)
 * </pre>
 *
 * <h2>生命周期</h2>
 * <pre>
 *   构造阶段
 *     ├─ 保存 Spring ApplicationContext 引用
 *     ├─ 初始化本地 BeanDefinitionRegistry
 *     └─ 尝试启动 OSGI 框架（如果可用）
 *
 *   运行阶段
 *     ├─ getBean() → 多级查找
 *     ├─ registerBean() → 同时注册到 Spring 和 OSGI
 *     └─ publish() → 事件发布
 *
 *   销毁阶段
 *     ├─ 停止 OSGI 框架
 *     └─ 清理本地注册表
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
     * 本地 BeanDefinitionRegistry（用于非 Spring 管理的 Bean）
     */
    private final BeanDefinitionRegistry localRegistry;

    /**
     * OSGI 启动器引用（可能为 null）
     */
    private final OsgiLauncher osgiLauncher;

    /**
     * 环境配置
     */
    private Environment environment;

    /**
     * 事件发布器，每个 SpringBootObjectContext 实例独立持有
     */
    private final EventPublisher eventPublisher = new EventPublisher();

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 构造 SpringBootObjectContext。
     *
     * @param applicationContext Spring ApplicationContext
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

        // 尝试启动 OSGI 框架
        this.osgiLauncher = tryStartOsgi();
        if (this.osgiLauncher != null) {
            log.info("[SpringBootObjectContext] OSGI 框架已启动，集成 OSGI 服务上下文");
            registerOsgiBundleApplications();
        }

        // 执行包扫描（如果配置了）
        if (config != null && config.shouldScan()) {
            scan(config.getScanPackages());
        }

        log.info("[SpringBootObjectContext] 初始化完成，Spring Bean: {}, OSGI: {}",
                applicationContext.getBeanDefinitionCount(),
                this.osgiLauncher != null ? "已激活" : "未启用");
    }

    // ==================== Bean 获取（多级查找） ====================

    @Override
    public <T> T getBean(String name, Class<T> type) {
        // 1. Spring ApplicationContext
        try {
            if (applicationContext.containsBean(name)) {
                return applicationContext.getBean(name, type);
            }
        } catch (Exception e) {
            log.trace("[SpringBootObjectContext] Spring getBean('{}') failed: {}", name, e.getMessage());
        }

        // 2. 按类型从 Spring 查找（name 可能不匹配 Spring Bean 名称）
        try {
            T bean = applicationContext.getBean(type);
            if (bean != null) {
                return bean;
            }
        } catch (Exception ignored) {
        }

        // 3. OSGI 服务
        if (osgiLauncher != null && osgiLauncher.isActive()) {
            try {
                T service = osgiLauncher.getService(type);
                if (service != null) {
                    return service;
                }
            } catch (Exception e) {
                log.trace("[SpringBootObjectContext] OSGI getService({}) failed: {}", type.getSimpleName(), e.getMessage());
            }
        }

        // 4. 本地注册表
        try {
            return localRegistry.getBean(name, type);
        } catch (Exception e) {
            log.trace("[SpringBootObjectContext] localRegistry getBean('{}') failed: {}", name, e.getMessage());
        }

        // 5. SPI 作为最后兜底
        try {
            return ServiceProvider.of(type).getService();
        } catch (Exception ignored) {
        }

        return null;
    }

    @Override
    public <T> T getBeanOfType(Class<T> type) {
        // 1. Spring
        try {
            return applicationContext.getBean(type);
        } catch (Exception ignored) {
        }

        // 2. OSGI
        if (osgiLauncher != null && osgiLauncher.isActive()) {
            try {
                T service = osgiLauncher.getService(type);
                if (service != null) {
                    return service;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. 本地注册表
        try {
            return localRegistry.getBeanOfType(type);
        } catch (Exception ignored) {
        }

        // 4. SPI
        try {
            return ServiceProvider.of(type).getService();
        } catch (Exception ignored) {
        }

        return null;
    }

    @Override
    public <T> T getBeanOfTypeSafely(Class<T> type) {
        try {
            return getBeanOfType(type);
        } catch (Exception e) {
            log.trace("[SpringBootObjectContext] getBeanOfTypeSafely({}) failed: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    @Override
    public <T> Map<String, T> getBeanOfTypes(Class<T> type) {
        Map<String, T> result = new LinkedHashMap<>();

        // 1. Spring
        try {
            result.putAll(applicationContext.getBeansOfType(type));
        } catch (Exception ignored) {
        }

        // 2. OSGI
        if (osgiLauncher != null && osgiLauncher.isActive()) {
            try {
                List<T> services = osgiLauncher.getServices(type);
                if (services != null) {
                    for (int i = 0; i < services.size(); i++) {
                        result.put("osgi-" + type.getSimpleName() + "-" + i, services.get(i));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. 本地注册表
        try {
            result.putAll(localRegistry.getBeansOfType(type));
        } catch (Exception ignored) {
        }

        return result;
    }

    @Override
    public <T> Collection<T> getBeanOfTypeCollection(Class<T> type) {
        return getBeanOfTypes(type).values();
    }

    @Override
    public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        return applicationContext.getBeansWithAnnotation(annotationType);
    }

    @Override
    public List<MethodDefinition> getMethodWithAnnotation(Class<? extends Annotation> annotationType) {
        List<MethodDefinition> result = new ArrayList<>();
        // 1. Spring 容器中标注了该类注解的 Bean 上的方法
        try {
            Map<String, Object> springBeans = applicationContext.getBeansWithAnnotation(annotationType);
            for (Map.Entry<String, Object> e : springBeans.entrySet()) {
                collectMethodDefinitions(result, e.getKey(), e.getValue(), annotationType);
            }
        } catch (Exception ignored) {
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
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * 收集 Bean 上标注了指定注解的方法，包装为 MethodDefinition。
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
                // 通过 TypeBeanDefinition 复用 MethodDefinition 引用父 BeanDefinition 的能力
                BeanDefinition parent = TypeBeanDefinition.of(beanClass, beanName);
                result.add(new MethodDefinition(parent, method));
            }
        }
    }

    @Override
    public void autowire(Object bean) {
        if (bean == null) {
            return;
        }
        // 利用 Spring 的 AutowireCapableBeanFactory 进行依赖注入
        try {
            applicationContext.getAutowireCapableBeanFactory().autowireBean(bean);
        } catch (Exception e) {
            log.trace("[SpringBootObjectContext] autowire failed: {}", e.getMessage());
        }
    }

    // ==================== Bean 存在性检查 ====================

    @Override
    public boolean containsBean(String name) {
        if (applicationContext.containsBean(name)) {
            return true;
        }
        return localRegistry.containsBean(name);
    }

    @Override
    public boolean isSingleton(String name) {
        if (applicationContext.containsBean(name)) {
            return applicationContext.isSingleton(name);
        }
        return localRegistry.isSingleton(name);
    }

    @Override
    public Collection<String> getBeanDefinitionNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(Arrays.asList(applicationContext.getBeanDefinitionNames()));
        names.addAll(localRegistry.getBeanDefinitionNames());
        return names;
    }

    @Override
    public int getBeanDefinitionCount() {
        return applicationContext.getBeanDefinitionCount() + localRegistry.getBeanDefinitionCount();
    }

    @Override
    public <T> boolean hasBeanOfType(Class<T> type) {
        try {
            return !applicationContext.getBeansOfType(type).isEmpty();
        } catch (Exception ignored) {
        }
        return localRegistry.hasBeanOfType(type);
    }

    @Override
    public Collection<String> getBeanNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        try {
            names.addAll(Arrays.asList(applicationContext.getBeanNamesForType(type)));
        } catch (Exception ignored) {
        }
        names.addAll(localRegistry.getBeanNames(type));
        return names;
    }

    @Override
    public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
        return () -> getBeanOfType(requiredType);
    }

    // ==================== Environment ====================

    @Override
    public Environment getEnvironment() {
        if (environment == null) {
            environment = new SpringBootEnvironment(applicationContext);
        }
        return environment;
    }

    // ==================== 事件发布 ====================

    @Override
    public int publish(Object event) {
        if (event == null) {
            return 0;
        }
        // 同步发布到 Spring 容器
        try {
            applicationContext.publishEvent(event);
        } catch (Exception e) {
            log.trace("[SpringBootObjectContext] Spring publishEvent failed: {}", e.getMessage());
        }
        // 同时触发自有监听器
        return eventPublisher.publish(event);
    }

    @Override
    public EventPublisher getEventPublisher() {
        return eventPublisher;
    }

    // ==================== Bean 注册 ====================

    @Override
    public void registerBean(Object bean) {
        if (bean == null) {
            return;
        }
        // 1. 注册到本地注册表与 OSGi（默认行为）
        ObjectContext.super.registerBean(bean);

        // 2. 同步到 Spring 容器（如果尚未注册），使后续 @Autowired 可以注入
        try {
            SpringObjectContextBridge.registerIfAbsent(applicationContext, bean);
        } catch (Exception e) {
            log.trace("[SpringBootObjectContext] 同步到 Spring 失败: {}", e.getMessage());
        }

        // 3. 注册到 OSGi
        if (osgiLauncher != null && osgiLauncher.isActive()) {
            try {
                BundleApplication[] apps = applicationContext.getBeansOfType(BundleApplication.class)
                        .values().toArray(new BundleApplication[0]);
                for (BundleApplication app : apps) {
                    BundleContext ctx = getOsgiBundleContext();
                    if (ctx != null) {
                        @SuppressWarnings("unchecked")
                        Class<Object> type = (Class<Object>) bean.getClass();
                        ctx.registerService(type, bean);
                    }
                }
            } catch (Exception e) {
                log.trace("[SpringBootObjectContext] OSGI registerService failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void registerBean(Class<?> type) {
        ObjectContext.super.registerBean(type);
    }

    @Override
    public void registerBean(BeanDefinition beanDefinition) {
        ObjectContext.super.registerBean(beanDefinition);
    }

    // ==================== 包扫描 ====================

    @Override
    public void scan(String basePackage) {
        ObjectContextScanner.scan(this, basePackage);
    }

    @Override
    public void scan(List<String> basePackages) {
        ObjectContextScanner.scan(this, basePackages);
    }

    // ==================== 注册中心 ====================

    @Override
    public BeanDefinitionRegistry getRegistry() {
        return localRegistry;
    }

    @Override
    public BeanDefinitionRegistry getRegistry(boolean spiEnabled) {
        return localRegistry;
    }

    // ==================== OSGI 集成 ====================

    /**
     * 获取 Spring ApplicationContext。
     *
     * @return Spring ApplicationContext
     */
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * 获取 OSGI 启动器。
     *
     * @return OSGI 启动器，未启用时返回 null
     */
    public OsgiLauncher getOsgiLauncher() {
        return osgiLauncher;
    }

    /**
     * 判断 OSGI 是否已启用。
     *
     * @return true 表示已启用
     */
    public boolean isOsgiEnabled() {
        return osgiLauncher != null && osgiLauncher.isActive();
    }

    /**
     * 尝试启动 OSGI 框架。
     *
     * @return OSGI 启动器实例，不可用时返回 null
     */
    private OsgiLauncher tryStartOsgi() {
        try {
            // 检查 classpath 是否有 OSGI 实现
            Class.forName("com.chua.osgi.support.FelixOsgiLauncher");
            OsgiLauncher launcher = ServiceProvider.of(OsgiLauncher.class).getService();
            if (launcher != null) {
                Map<String, String> config = new HashMap<>();
                config.put("org.osgi.framework.storage", ".workbuddy/osgi-cache");
                config.put("org.osgi.framework.startlevel.beginning", "4");
                launcher.start(config);
                return launcher;
            }
        } catch (ClassNotFoundException e) {
            log.debug("[SpringBootObjectContext] OSGI 实现不可用（classpath 无 FelixOsgiLauncher），跳过 OSGI 集成");
        } catch (Exception e) {
            log.warn("[SpringBootObjectContext] OSGI 启动失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 注册所有 SPI BundleApplication 实现到 OSGI 上下文。
     */
    private void registerOsgiBundleApplications() {
        try {
            List<BundleApplication> applications = ServiceProvider.of(BundleApplication.class).collect();
            if (applications.isEmpty()) {
                log.debug("[SpringBootObjectContext] 无 BundleApplication 实现，跳过 OSGI Bundle 注册");
                return;
            }

            // 同时检查 Spring 容器中的 BundleApplication
            Map<String, BundleApplication> springApps = applicationContext.getBeansOfType(BundleApplication.class);

            // 合并 SPI 和 Spring 的 BundleApplication
            Set<BundleApplication> allApps = new LinkedHashSet<>(applications);
            allApps.addAll(springApps.values());

            for (BundleApplication app : allApps) {
                try {
                    BundleContext ctx = getOsgiBundleContext();
                    if (ctx != null) {
                        app.onBundleStart(ctx);
                        log.info("[SpringBootObjectContext] OSGI Bundle 已启动: {}", app.getClass().getSimpleName());
                    }
                } catch (Exception e) {
                    log.warn("[SpringBootObjectContext] BundleApplication 启动失败 [{}]: {}",
                            app.getClass().getSimpleName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[SpringBootObjectContext] 注册 OSGI BundleApplication 失败: {}", e.getMessage());
        }
    }

    /**
     * 获取 OSGI Bundle 上下文（匿名实现）。
     *
     * @return BundleContext 实例
     */
    private BundleContext getOsgiBundleContext() {
        if (osgiLauncher == null || !osgiLauncher.isActive()) {
            return null;
        }
        return new BundleContext() {
            @Override
            public <T> void registerService(Class<T> type, T service) {
                // 代理到 OSGI 框架
                log.debug("[SpringBootObjectContext] 注册 OSGI 服务: {}", type.getSimpleName());
            }

            @Override
            public <T> void unregisterService(Class<T> type, T service) {
                log.debug("[SpringBootObjectContext] 注销 OSGI 服务: {}", type.getSimpleName());
            }

            @Override
            public <T> List<T> getServices(Class<T> type) {
                return osgiLauncher.getServices(type);
            }

            @Override
            public <T> T getService(Class<T> type) {
                return osgiLauncher.getService(type);
            }
        };
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭上下文，释放资源。
     * <p>停止 OSGi 框架并清理本地注册表，然后调用 {@link ObjectContext#close()}
     * 清理 CONFIG_HOLDER / REGISTRY_HOLDER。</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // 停止 OSGI
        if (osgiLauncher != null) {
            try {
                osgiLauncher.stop();
                OsgiLauncherHolder.clear();
                log.info("[SpringBootObjectContext] OSGI 框架已停止");
            } catch (Exception e) {
                log.warn("[SpringBootObjectContext] OSGI 停止失败: {}", e.getMessage());
            }
        }

        // 清理本地注册表（不销毁 Bean，但清空缓存）
        try {
            localRegistry.clear();
        } catch (Exception e) {
            log.warn("[SpringBootObjectContext] 清理本地注册表失败: {}", e.getMessage());
        }

        // 调用接口默认实现清理 CONFIG_HOLDER / REGISTRY_HOLDER
        ObjectContext.super.close();
        log.info("[SpringBootObjectContext] 已关闭");
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== Spring Environment 适配器 ====================

    /**
     * Spring Environment 适配器 — 将 Spring 的 Environment 适配为 ObjectContext 的 Environment。
     */
    private static class SpringBootEnvironment implements Environment {

        private final org.springframework.core.env.Environment springEnv;

        SpringBootEnvironment(ApplicationContext applicationContext) {
            this.springEnv = applicationContext.getEnvironment();
        }

        @Override
        public String getProperty(String key) {
            return springEnv.getProperty(key);
        }

        @Override
        public String getProperty(String key, String defaultValue) {
            return springEnv.getProperty(key, defaultValue);
        }

        @Override
        public <T> T getProperty(String key, Class<T> targetType) {
            return springEnv.getProperty(key, targetType);
        }

        @Override
        public boolean containsProperty(String key) {
            return springEnv.containsProperty(key);
        }

        @Override
        public String[] getActiveProfiles() {
            return springEnv.getActiveProfiles();
        }

        @Override
        public String[] getDefaultProfiles() {
            return springEnv.getDefaultProfiles();
        }
    }
}