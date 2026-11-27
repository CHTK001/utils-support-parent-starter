package com.chua.common.support.objects;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.objects.definition.*;
import com.chua.common.support.objects.environment.DefaultEnvironment;
import com.chua.common.support.objects.environment.Environment;
import com.chua.common.support.objects.exception.BeanNotFoundException;
import com.chua.common.support.objects.generator.BeanDefinitionGenerator;
import com.chua.common.support.objects.generator.BeanDefinitionGenerators;
import com.chua.common.support.objects.provider.DefaultObjectProvider;
import com.chua.common.support.objects.provider.ObjectProvider;
import com.chua.common.support.objects.publisher.EventPublisher;
import com.chua.common.support.objects.register.BeanDefinitionRegistry;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import com.chua.common.support.utils.CollectionUtils;

/**
 * 默认对象容器实现。
 *
 * <p>持有 {@link ObjectContextConfig} 和 {@link BeanDefinitionRegistry} 实例，
 * 替代接口静态 {@code WeakHashMap} holder，提供线程安全、可 GC 的轻量级 IOC 容器。</p>
 *
 * <p>核心职责：
 * <ul>
 *   <li>管理配置 {@link ObjectContextConfig} 和注册中心 {@link BeanDefinitionRegistry}</li>
 *   <li>Bean 生命周期：注册、获取、初始化、销毁</li>
 *   <li>依赖注入链路：{@code @AutoInject} / {@code @ConfigValue} 通过
 *       {@link BeanDefinition#setBeanNameProvider(Function)} /
 *       {@link BeanDefinition#setBeanTypeProvider(Function)} 回调解析依赖</li>
 *   <li>事件发布：委托 {@link EventPublisher}</li>
 * </ul></p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * DefaultObjectContext context = new DefaultObjectContext();
 * context.init(ObjectContextConfig.defaults());
 * context.registerBean(new MyService());
 * MyService service = context.getBean(MyService.class);
 * }</pre>
 * }
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public class DefaultObjectContext implements ObjectContext {

    /**
     * 容器配置
     */
    private ObjectContextConfig config;

    /**
     * Bean 定义注册中心（延迟初始化，直到第一次 获取registry() 或 初始化()）
     */
    private volatile BeanDefinitionRegistry registry;

    /**
     * 事件发布器
     */
    private final EventPublisher eventPublisher = new EventPublisher();

    /**
     * 环境配置
     */
    private final Environment environment = new DefaultEnvironment();

    /**
     * 容器是否已关闭。
     */
    private volatile boolean closed = false;

    /**
     * 容器是否已完成初始化（防止重复 init 造成重复扫描与注册）。
     */
    private volatile boolean initialized = false;

    // ==================== 构造器 ====================

    /**
     * 使用默认配置创建容器（不自动 初始化，需手动调用 {@link #init()}）
     */
    public DefaultObjectContext() {
    }

    /**
     * 使用指定配置创建容器（不自动 初始化）
     * @param config 配置
     */
    public DefaultObjectContext(ObjectContextConfig config) {
        this.config = config;
    }

    /**
     * 创建容器并从 类路径 配置（application.yml 等）完成初始化。
     *
     * @return 已 初始化 的上下文
     */
    public static DefaultObjectContext create() {
        DefaultObjectContext context = new DefaultObjectContext();
        context.init();
        return context;
    }

    /**
     * 创建容器并使用指定配置完成初始化。
     *
     * @param config 容器配置
     * @return 已 初始化 的上下文
     */
    public static DefaultObjectContext create(ObjectContextConfig config) {
        DefaultObjectContext context = new DefaultObjectContext();
        context.init(config);
        return context;
    }

    /**
     * 创建容器并直接完成初始化。
     *
     * <p>与 {@link #create()} 语义一致，仅方法名显式表达「创建即可用」，
     * 避免调用方在拿到实例后再手动调用 {@link #init()} 造成重复初始化。</p>
     *
     * @return 已初始化的上下文
     */
    public static DefaultObjectContext createWithInitize() {
        return create();
    }

    /**
     * 创建容器并使用指定配置直接完成初始化。
     *
     * @param config 容器配置
     * @return 已初始化的上下文
     */
    public static DefaultObjectContext createWithInitize(ObjectContextConfig config) {
        return create(config);
    }

    // ==================== 生命周期 ====================

        /**
         * 初始化
         */
@Override
    public void init() {
        init(config != null ? config : ObjectContextConfig.defaults());
    }

        /**
         * 初始化
         */
@Override
    public void init(ObjectContextConfig config) {
        if (initialized) {
            if (log.isDebugEnabled()) {
                log.debug("[ObjectContext] 容器已初始化，忽略重复的 init 调用");
            }
            return;
        }
        if (config == null) {
            config = ObjectContextConfig.defaults();
        }
        this.config = config;

        getRegistry(config.isSpiEnabled());

        if (config.shouldScan()) {
            scan(config.getScanPackages());
        }

        configureAllBeanDefinitionProviders();
        initialized = true;
    }

    /**
     * 为注册中心中所有 Beandefinition 设置容器回调，
     * 使其在构造器注入和字段注入时能从容器查找依赖。
     */
    private void configureAllBeanDefinitionProviders() {
        BeanDefinitionRegistry reg = this.registry;
        if (reg == null) {
            return;
        }
        for (String name : reg.getBeanDefinitionNames()) {
            configureBeanDefinitionProvider(reg.getBeanDefinition(name));
        }
    }

    /**
     * 为单个 Beandefinition 设置容器回调。
     *
     * <p>幂等：回调与环境只设置一次。{@code getBean} 是最高频入口，
     * 逐次设置意味着每次查找都要分配 2 个 lambda 并写 3 个字段。</p>
     *
     * @param def def
     */
    private void configureBeanDefinitionProvider(BeanDefinition def) {
        if (!(def instanceof AbstractBeanDefinition abd)) {
            return;
        }
        if (abd.getBeanNameProvider() != null && abd.getBeanTypeProvider() != null && abd.getEnvironment() != null) {
            return;
        }
        abd.setBeanTypeProvider(this::getBeanOfType);
        abd.setBeanNameProvider(n -> getBean(n, null));
        abd.setEnvironment(getEnvironment());
    }

        /**
         * 设置配置
         */
@Override
    public void setConfig(ObjectContextConfig config) {
        this.config = config;
    }

        /**
         * 获取配置
         */
@Override
    public ObjectContextConfig getConfig() {
        if (config != null) {
            return config;
        }
        if (config == null) {
            config = ObjectContextConfig.defaults();
        }
        return config;
    }

        /**
         * 获取Registry
         */
@Override
    public BeanDefinitionRegistry getRegistry() {
        return getRegistry(getConfig().isSpiEnabled());
    }

        /**
         * 获取Registry
         */
@Override
    public BeanDefinitionRegistry getRegistry(boolean spiEnabled) {
        if (registry == null) {
            synchronized (this) {
                if (registry == null) {
                    registry = new BeanDefinitionRegistry();
                    registry.initialize(spiEnabled);
                }
            }
        }
        return registry;
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
        return eventPublisher.publish(event);
    }

        /**
         * 获取Bean提供者
         */
@Override
    public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
        return new DefaultObjectProvider<>(this, requiredType);
    }

    // ==================== Bean 注册/注销 ====================

        /**
         * 注册Bean
         */
@Override
    public void registerBean(Object bean) {
        if (bean == null) {
            return;
        }
        if (closed) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                    "容器已关闭，无法注册 Bean: " + bean.getClass().getName());
        }
        // registry 延迟初始化：先 getRegistry() 确保容器可用，再判重
        BeanDefinitionRegistry registry = getRegistry(getConfig().isSpiEnabled());
        if (registry.containsInstance(bean)) {
            return;
        }
        List<BeanDefinitionGenerator> generators = BeanDefinitionGenerators.sorted();

        boolean registered = false;
        for (BeanDefinitionGenerator gen : generators) {
            if (Boolean.TRUE.equals(gen.isSupport(bean.getClass()))) {
                List<BeanDefinition> defs = gen.generate(bean.getClass());
                if (CollectionUtils.isNotEmpty(defs)) {
                    // 注册被拒时保持原语义：不抛异常，落空后走下方类型兜底
                    if (registerSingletonDefinition(registry, defs.getFirst(), bean, false)) {
                        registered = true;
                    }
                }
                break;
            }
        }

        if (!registered) {
            com.chua.common.support.objects.definition.TypeBeanDefinition def =
                    com.chua.common.support.objects.definition.TypeBeanDefinition.of(bean.getClass());
            if (def != null) {
                registerSingletonDefinition(registry, def, bean, true);
            }
        }
    }

    /**
     * 注册单例 Bean 定义并完成初始化。
     *
     * @param registry       注册中心
     * @param def            由生成器或类型推断得到的 Bean 定义
     * @param bean           Bean 实例
     * @param failIfRejected 注册被拒时是否抛异常
     * @return 是否注册并初始化成功
     */
    private boolean registerSingletonDefinition(BeanDefinitionRegistry registry, BeanDefinition def,
                                                Object bean, boolean failIfRejected) {
        SingletonBeanDefinition singletonDef = new SingletonBeanDefinition(def, bean);
        singletonDef.setBeanNameProvider(name -> getBean(name, null));
        singletonDef.setBeanTypeProvider(this::getBeanOfType);
        singletonDef.setEnvironment(getEnvironment());
        if (!registry.register(singletonDef)) {
            if (failIfRejected) {
                throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                        "Bean 注册失败: " + bean.getClass().getName());
            }
            return false;
        }
        try {
            singletonDef.getBean();
        } catch (Exception e) {
            // 初始化失败回滚注册
            registry.unregister(singletonDef);
            throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                    "Bean 初始化失败: " + bean.getClass().getName(), e);
        }
        return true;
    }

        /**
         * 注销Bean
         */
@Override
    public boolean unregisterBean(Object bean) {
        if (bean == null) {
            return false;
        }
        BeanDefinitionRegistry registry = getRegistry(getConfig().isSpiEnabled());
        boolean removed = registry.getBeanDefinitionOfType(bean.getClass()).stream()
                .filter(def -> {
                    try {
                        return def.getBean() == bean;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .peek(BeanDefinition::destroyBean)
                .peek(registry::unregister)
                .findAny()
                .isPresent();
        return removed;
    }

    // ==================== Bean 获取 ====================

        /**
         * 获取Bean
         */
    @SuppressWarnings("unchecked")
@Override
    public <T> T getBean(String name, Class<T> type) {
        if (name == null || closed) {
            return null;
        }
        if (registry == null) {
            // 容器尚未初始化任何定义，等同于找不到 Bean，而不是 NPE
            throw new com.chua.common.support.objects.exception.BeanNotFoundException(name, type);
        }
        BeanDefinition def = registry.getBeanDefinition(name);
        if (def == null) {
            throw new com.chua.common.support.objects.exception.BeanNotFoundException(name, type);
        }
        configureBeanDefinitionProvider(def);
        Object bean = def.getBean();
        // 严格类型校验：type==null 时不进行强转前的类型校验，但也不应强转失败
        if (type != null && bean != null && !type.isInstance(bean)) {
            throw new com.chua.common.support.objects.exception.BeanTypeMismatchException(
                    "Bean 类型不匹配: name=" + name
                            + ", expected=" + type.getName()
                            + ", actual=" + bean.getClass().getName());
        }
        return (T) bean;
    }

        /**
         * 获取Bean的类型
         */
@Override
    public <T> T getBeanOfType(Class<T> type) {
        if (type == null || closed) {
            return null;
        }
        if (registry == null) {
            return null;
        }
        Collection<BeanDefinition> defs = registry.getBeanDefinitionOfType(type);
        if (defs.isEmpty()) {
            return null;
        }
        // 单实现快路径：免拷贝、免排序（绝大多数按类型查找命中唯一实现）
        if (defs.size() == 1) {
            BeanDefinition def = defs.iterator().next();
            try {
                configureBeanDefinitionProvider(def);
                Object bean = def.getBean();
                if (type.isInstance(bean)) {
                    return type.cast(bean);
                }
            } catch (Exception e) {
                log.debug("获取 Bean 失败: {}", def.getName(), e);
            }
            return null;
        }
        List<BeanDefinition> sorted = Wraps.list(defs);
        sorted.sort(Comparator.comparingInt(BeanDefinition::getPriority).reversed());
        for (BeanDefinition def : sorted) {
            try {
                configureBeanDefinitionProvider(def);
                Object bean = def.getBean();
                if (type.isInstance(bean)) {
                    return type.cast(bean);
                }
            } catch (Exception e) {
                log.debug("获取 Bean 失败: {}", def.getName(), e);
            }
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
            return null;
        }
    }

        /**
         * 获取Bean的类型
         */
@Override
    public <T> Map<String, T> getBeanOfTypes(Class<T> type) {
        if (type == null || closed || registry == null) {
            return Collections.emptyMap();
        }
        Collection<BeanDefinition> defs = registry.getBeanDefinitionOfType(type);
        if (defs.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, T> result = Wraps.linkedMap();
        // 单实现快路径：免拷贝、免排序
        if (defs.size() == 1) {
            BeanDefinition def = defs.iterator().next();
            try {
                configureBeanDefinitionProvider(def);
                Object bean = def.getBean();
                if (type.isInstance(bean)) {
                    result.put(def.getName(), type.cast(bean));
                }
            } catch (Exception e) {
                log.debug("获取 Bean 失败: {}", def.getName(), e);
            }
            return result;
        }
        List<BeanDefinition> sorted = Wraps.list(defs);
        sorted.sort(Comparator.comparingInt(BeanDefinition::getPriority).reversed());
        for (BeanDefinition def : sorted) {
            try {
                configureBeanDefinitionProvider(def);
                Object bean = def.getBean();
                if (type.isInstance(bean)) {
                    result.put(def.getName(), type.cast(bean));
                }
            } catch (Exception e) {
                log.debug("获取 Bean 失败: {}", def.getName(), e);
            }
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
        if (annotationType == null || closed || registry == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = Wraps.linkedMap();
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition def = registry.getBeanDefinition(name);
            if (def != null && def.getBeanClass() != null && def.getBeanClass().isAnnotationPresent(annotationType)) {
                try {
                    configureBeanDefinitionProvider(def);
                    Object bean = def.getBean();
                    if (bean != null) {
                        result.put(name, bean);
                    }
                } catch (Exception e) {
                    log.debug("获取带注解 Bean 失败: {}", name, e);
                }
            }
        }
        return result;
    }

        /**
         * 获取带指定注解的方法列表
         */
@Override
    public List<MethodDefinition> getMethodWithAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType == null || closed || registry == null) {
            return Collections.emptyList();
        }
        List<MethodDefinition> result = Wraps.list();
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition def = registry.getBeanDefinition(name);
            if (def == null || def.getBean() == null) {
                continue;
            }
            try {
                configureBeanDefinitionProvider(def);
                Object bean = def.getBean();
                if (bean == null) {
                    continue;
                }
                ClassUtils.doWithMethods(bean.getClass(), method -> {
                    if (method.isBridge() || method.getDeclaringClass() == Object.class) {
                        return;
                    }
                    if (method.isAnnotationPresent(annotationType)) {
                        MethodDefinition methodDef = new MethodDefinition(def, method);
                        result.add(methodDef);
                    }
                });
            } catch (Exception e) {
                log.debug("获取带注解方法失败: {}", name, e);
            }
        }
        return result;
    }

        /**
         * Autowire
         */
@Override
    public void autowire(Object bean) {
        if (bean == null) {
            return;
        }
        AbstractBeanDefinition beanDefinition = SingletonBeanDefinition.of(bean);
        beanDefinition.setBeanNameProvider(name -> getBean(name, null));
        beanDefinition.setBeanTypeProvider(this::getBeanOfType);
        beanDefinition.setEnvironment(getEnvironment());
        beanDefinition.injectAndAssemble(bean);
    }

        /**
         * containsBean
         */
@Override
    public boolean containsBean(String name) {
        if (closed) {
            return false;
        }
        return name != null && registry.containsBean(name);
    }

        /**
         * 是否单例
         */
@Override
    public boolean isSingleton(String name) {
        if (closed || name == null) {
            return false;
        }
        BeanDefinition def = registry.getBeanDefinition(name);
        return def != null && def.getScope() == BeanScope.SINGLETON;
    }

        /**
         * 获取Beandefinition名称
         */
@Override
    public Collection<String> getBeanDefinitionNames() {
        if (closed) {
            return Collections.emptyList();
        }
        return getRegistry(getConfig().isSpiEnabled()).getBeanDefinitionNames();
    }

        /**
         * 获取Beandefinition计算数量
         */
@Override
    public int getBeanDefinitionCount() {
        if (closed) {
            return 0;
        }
        return getRegistry(getConfig().isSpiEnabled()).getBeanDefinitionCount();
    }

        /**
         * 是否拥有Bean的类型
         */
@Override
    public <T> boolean hasBeanOfType(Class<T> type) {
        if (type == null || closed) {
            return false;
        }
        return !registry.getBeanDefinitionOfType(type).isEmpty();
    }

        /**
         * 获取Bean名称
         */
@Override
    public Collection<String> getBeanNames(Class<?> type) {
        if (type == null || closed) {
            return Collections.emptyList();
        }
        return registry.getBeanDefinitionOfType(type).stream()
                .map(BeanDefinition::getName)
                .filter(Objects::nonNull)
                .toList();
    }

        /**
         * 获取环境
         */
@Override
    public Environment getEnvironment() {
        return environment;
    }

        /**
         * 获取事件发布
         */
@Override
    public EventPublisher getEventPublisher() {
        return eventPublisher;
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭容器，销毁所有 Bean 并清理相关状态。
     * <p>幂等：重复调用安全。关闭后所有查找类方法返回 null/empty，注册类方法抛出 {@link
     * com.chua.common.support.objects.exception.BeanDefinitionException}。</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            BeanDefinitionRegistry reg = this.registry;
            if (reg != null) {
                reg.close();
            }
        } finally {
            this.registry = null;
            this.config = null;
            eventPublisher.clear();
            // 同步清理接口层静态持有表：CONFIG_HOLDER / REGISTRY_HOLDER 以容器实例为键
            // 强引用容器，不移除会导致已关闭的容器实例无法回收（见 ObjectContext#close 约定）。
            REGISTRY_HOLDER.remove(this);
            CONFIG_HOLDER.remove(this);
            // 允许关闭后的容器重新初始化（保持历史行为），仅拦截「未关闭时的重复 init」。
            initialized = false;
        }
    }

    /**
     * 容器是否已关闭。
     * <p>基于实例字段，避免依赖 {@link ObjectContext#REGISTRY_HOLDER} 的状态。</p>
     */
    @Override
    public boolean isClosed() {
        return closed;
    }
}
