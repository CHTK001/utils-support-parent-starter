package com.chua.common.support.objects;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.MethodDefinition;
import com.chua.common.support.objects.definition.SingletonBeanDefinition;
import com.chua.common.support.objects.environment.Environment;
import com.chua.common.support.objects.generator.BeanDefinitionGenerator;
import com.chua.common.support.objects.provider.ObjectProvider;
import com.chua.common.support.objects.publisher.EventPublisher;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanDefinitionRegistry;
import com.chua.common.support.objects.scanner.ObjectContextScanner;
import com.chua.common.support.spi.ServiceProvider;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对象容器上下文接口。
 * <p>轻量级 IoC 容器的核心抽象，提供 Bean 的获取、注册、查询和生命周期管理能力。</p>
 * <p>类似于 Spring 的 ApplicationContext，但更加精简，专注于对象查找和依赖注入。</p>
 * <p>核心功能包括：按名称/类型获取 Bean 实例、按注解批量获取 Bean、判断 Bean 是否存在及是否为单例、
 * 获取环境配置接口、事件发布能力、Bean 注册/注销、包扫描以及可配置 SPI 发现开关。</p>
 *
 * <h2>Bean 管理体系</h2>
 * <pre>
 * Class 来源
 *   ├─ SPI 发现（ServiceProvider）
 *   ├─ 注解扫描（BeanDefinitionDetector）
 *   ├─ registerBean(Object) 手动注册
 *   └─ scan(basePackage) 包扫描
 *         │
 *         ▼
 * BeanDefinitionGenerator  →  BeanDefinition
 *         │
 *         ▼
 * BeanDefinitionRegister.register(BeanDefinition)
 *   ├─ 缓存 name→register、type→names
 *   └─ beanNameCache / typeToBeanNames
 *         │
 *         ▼
 * createInstance() → 反射构造
 *   ├─ BeanDefinitionServiceInjector（@Autowired 等）
 *   ├─ BeanDefinitionConfigInjector（@Value 等）
 *   └─ BeanDefinitionLifecycle（@PostConstruct, @PreDestroy）
 *         │
 *         ▼
 * getBean() / getBeanOfType()  供消费
 * </pre>
 *
 * @author CH
 * @since 2024/12/20
 */
public interface ObjectContext {

    /**
      * 存储 对象上下文 实例对应的配置。
     * <p>用于 default 方法持有一个状态副本。</p>
     */
    Map<ObjectContext, ObjectContextConfig> CONFIG_HOLDER = new ConcurrentHashMap<>();

    /**
      * 每个 对象上下文 实例持有的唯一 Beandefinitionregistry。
     * <p>解决 registerBean()/getBean() 数据不互通的问题。</p>
     * <p>使用 {@link ConcurrentHashMap} 的强引用持有，容器关闭时（{@link #close()}）显式移除，
      * 避免 weak哈希映射 在实例暂时无强引用时被 GC 后丢失所有 Beandefinition。</p>
     */
    Map<ObjectContext, BeanDefinitionRegistry> REGISTRY_HOLDER = new ConcurrentHashMap<>();

    // ==================== Bean 获取 ====================

    /**
     * 根据名称和类型获取 Bean 实例。
     *
     * @param <T>  目标类型泛型
     * @param name Bean 名称
     * @param type Bean 类型
     * @return Bean 实例
     */
    <T> T getBean(String name, Class<T> type);

    /**
     * 按类型获取 Bean 实例。
     * <p>从所有 BeanDefinitionRegistry 中按类型匹配，
      * 按 Beandefinition#获取priority() 从高到低返回第一个成功初始化的非空实例。</p>
     *
     * @param <T>  目标类型泛型
     * @param type Bean 类型
     * @return Bean 实例，不存在返回 空
     */
    <T> T getBeanOfType(Class<T> type);

    /**
     * 安全地按类型获取 Bean 实例。
     *
     * @param <T>  目标类型泛型
     * @param type Bean 类型
     * @return Bean 实例，不存在返回 空
     */
    <T> T getBeanOfTypeSafely(Class<T> type);

    /**
     * 获取指定类型的所有 Bean 实例映射。
     *
     * @param <T>  目标类型泛型
     * @param type Bean 类型
     * @return 名称到 Bean 实例映射
     */
    <T> Map<String, T> getBeanOfTypes(Class<T> type);

    /**
     * 获取指定类型的所有 Bean 实例集合。
     *
     * @param <T>  目标类型泛型
     * @param type Bean 类型
     * @return Bean 实例集合
     */
    <T> Collection<T> getBeanOfTypeCollection(Class<T> type);

    /**
     * 获取带有指定注解的所有 Bean 实例映射。
     *
     * @param annotationType 注解类型
     * @return 名称到 Bean 实例映射
     */
    Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType);

    /**
     * 获取所有带有指定注解的方法定义。
     *
     * @param annotationType 注解类型
     * @return 方法定义列表
     */
    List<MethodDefinition> getMethodWithAnnotation(Class<? extends Annotation> annotationType);

    /**
     * 对已存在的对象执行依赖注入。
     *
     * @param bean 待装配的对象
     */
    void autowire(Object bean);

    /**
     * 判断容器中是否包含指定名称的 Bean。
     *
     * @param name Bean 名称
     * @return true 表示存在
     */
    boolean containsBean(String name);

    /**
     * 判断指定名称的 Bean 是否为单例。
     *
     * @param name Bean 名称
     * @return true 表示为单例
     */
    boolean isSingleton(String name);

    /**
     * 获取所有 Bean 的名称集合。
     *
     * @return 所有 Bean 名称
     */
    Collection<String> getBeanDefinitionNames();

    /**
     * 获取 Bean 定义总数量。
     *
     * @return Bean 定义总数量
     */
    int getBeanDefinitionCount();

    /**
     * 判断容器中是否存在指定类型的 Bean。
     *
     * @param <T>  目标类型泛型
     * @param type Bean 类型
     * @return true 表示存在
     */
    <T> boolean hasBeanOfType(Class<T> type);

    /**
     * 获取指定类型的所有 Bean 名称集合。
     *
     * @param type Bean 类型
     * @return Bean 名称集合
     */
    Collection<String> getBeanNames(Class<?> type);

    /**
     * 获取指定类型的 Bean 提供者。
     *
     * @param <T>          目标类型泛型
     * @param requiredType Bean 类型
     * @return ObjectProvider 实例
     */
    <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType);

    /**
     * 获取环境配置实例。
     *
     * @return 环境配置实例
     */
    Environment getEnvironment();

    /**
     * 发布事件。
     * <p>默认委托给当前容器关联的 {@link EventPublisher#publish(Object)}，
      * 子类可重写以路由到原生容器（如 Spring application上下文）。</p>
     *
     * @param event 事件对象
     * @return 本次分发调用的监听器数量；事件为 空 返回 0
     */
    default int publish(Object event) {
        if (event == null) {
            return 0;
        }
        return getEventPublisher().publish(event);
    }

    // ==================== 事件监听器管理 ====================

    /**
     * 注册事件监听器。
     * <p>默认实现转发给当前容器关联的 {@link EventPublisher}。</p>
     *
     * @param <T>      事件类型泛型
     * @param type     事件类型
     * @param listener 事件监听器
     */
    default <T> void addEventListener(Class<T> type, EventPublisher.EventListener listener) {
        getEventPublisher().register(type, listener);
    }

    /**
     * 注销事件监听器。
     *
     * @param <T>      事件类型泛型
     * @param type     事件类型
     * @param listener 待注销的监听器
     * @return true 表示找到并移除
     */
    default <T> boolean removeEventListener(Class<T> type, EventPublisher.EventListener listener) {
        return getEventPublisher().unregister(type, listener);
    }

    /**
     * 获取事件发布器实例，供高级场景直接操作。
     * <p>每个 {@link ObjectContext} 实例独立持有一个发布器，容器关闭不会影响其他容器。</p>
     *
     * @return EventPublisher 实例
     */
    EventPublisher getEventPublisher();

    // ==================== 生命周期 ====================

    /**
     * 关闭容器：销毁所有 Bean、清理注册表、释放资源。
     * <p>调用后 {@link #isClosed()} 返回 true，{@link #getBeanOfType(Object)} 等查找方法返回 null。</p>
     */
    default void close() {
        if (isClosed()) {
            return;
        }
        try {
            BeanDefinitionRegistry registry = REGISTRY_HOLDER.get(this);
            if (registry != null) {
                registry.close();
            }
        } finally {
            REGISTRY_HOLDER.remove(this);
            CONFIG_HOLDER.remove(this);
        }
    }

    /**
     * 容器是否已关闭。
     *
     * @return true 表示已关闭
     */
    default boolean isClosed() {
        return REGISTRY_HOLDER.get(this) == null && CONFIG_HOLDER.get(this) == null;
    }

    // ==================== 配置管理 ====================

    /**
     * 使用默认配置初始化容器。
     * <p>行为对齐 Spring Boot，加载 classpath 配置（application.yml 等）。</p>
     *
     * @see ObjectContextConfig#defaults()
     */
    default void init() {
        init(ObjectContextConfig.defaults());
    }

    /**
     * 使用指定配置初始化容器。
     * <p>根据配置执行以下操作：</p>
     * <ul>
     *   <li>{@link ObjectContextConfig#shouldScan()} — 为 true 时扫描指定包路径下的类并自动注册</li>
     *   <li>{@link ObjectContextConfig#isSpiEnabled()} — 控制 BeanDefinitionRegistry 初始化时
      * 是否通过 SPI 发现 Beandefinition注册 实现（由 {@link #getRegistry(boolean)} 决定）</li>
     * </ul>
     *
     * @param config 容器配置，空 时等同 {@link ObjectContextConfig#defaults()}
     */
    default void init(ObjectContextConfig config) {
        if (config == null) {
            config = ObjectContextConfig.defaults();
        }
        CONFIG_HOLDER.put(this, config);

        if (config.shouldScan()) {
            scan(config.getScanPackages());
        }
    }

    /**
     * 设置容器配置。
     *
     * @param config 容器配置
     */
    default void setConfig(ObjectContextConfig config) {
        if (config != null) {
            CONFIG_HOLDER.put(this, config);
        }
    }

    /**
     * 获取容器配置。
     *
     * @return 当前配置，未设置则返回默认配置
     */
    default ObjectContextConfig getConfig() {
        return CONFIG_HOLDER.getOrDefault(this, ObjectContextConfig.defaults());
    }

    // ==================== Bean 注册/注销 ====================

    /**
     * 按类型注册 Bean。
     * <p>由 SPI 的 BeanDefinitionGenerator 根据 Class 自动生成 BeanDefinition，
     * 并触发实例化、依赖注入和生命周期初始化。</p>
     *
     * @param type Bean 类型
     * @throws com.chua.common.support.objects.exception.BeanDefinitionException 类型 为 空 或注册失败时抛出
     */
    default void registerBean(Class<?> type) {
        if (type == null) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException("Bean 类型不能为空");
        }
        BeanDefinitionRegistry registry = getRegistry(getConfig().isSpiEnabled());
        if (!registry.registerBeanFromClass(type)) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                    "Bean 自动注册失败: type=" + type.getName());
        }
    }

    /**
      * 按 Beandefinition 注册 Bean。
     * <p>直接注册给定的 BeanDefinition，触发实例化、依赖注入和生命周期初始化。</p>
     *
     * @param beanDefinition Bean 定义
     * @throws com.chua.common.support.objects.exception.BeanDefinitionException 注册失败时抛出
     */
    default void registerBean(BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException("BeanDefinition 不能为空");
        }
        BeanDefinitionRegistry registry = getRegistry(getConfig().isSpiEnabled());
        boolean registered = registry.register(beanDefinition);
        if (!registered) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                    "BeanDefinition 注册失败: name=" + beanDefinition.getName()
                            + ", type=" + (beanDefinition.getType() != null ? beanDefinition.getType() : "unknown"));
        }
        try {
            beanDefinition.getBean();
        } catch (Exception e) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                    "BeanDefinition 初始化失败: name=" + beanDefinition.getName(), e);
        }
    }

    /**
     * 注册一个已创建的对象实例到容器。
     * <p>通过 BeanDefinitionGenerator SPI 链生成 BeanDefinition，
      * 用 单例Beandefinition 包装已存在的实例后注册到 Beandefinitionregistry，
      * 并立即执行依赖注入和生命周期初始化（@autoinject、@配置值、@postconstruct）。</p>
     *
     * @param bean 要注册的对象实例
     * @throws com.chua.common.support.objects.exception.BeanDefinitionException 注册失败时抛出
     */
    default void registerBean(Object bean) {
        if (bean == null) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException("Bean 不能为空");
        }
        BeanDefinitionRegistry registry = getRegistry(getConfig().isSpiEnabled());
        // 已注册过则跳过，避免重复注册同一实例
        if (registry.containsInstance(bean)) {
            return;
        }

        List<BeanDefinitionGenerator> generators = ServiceProvider.of(BeanDefinitionGenerator.class)
                .collect().stream()
                .sorted(Comparator.comparingInt(BeanDefinitionGenerator::getPriority).reversed())
                .toList();

        boolean registered = false;
        for (BeanDefinitionGenerator gen : generators) {
            if (Boolean.TRUE.equals(gen.isSupport(bean.getClass()))) {
                List<BeanDefinition> defs = gen.generate(bean.getClass());
                if (defs != null && !defs.isEmpty()) {
                    BeanDefinition def = defs.getFirst();
                    SingletonBeanDefinition singletonDef = new SingletonBeanDefinition(def, bean);
                    if (!registry.register(singletonDef)) {
                        throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                                "BeanDefinition 注册失败: type=" + bean.getClass().getName());
                    }
                    try {
                        singletonDef.getBean();
                        registered = true;
                    } catch (Exception e) {
                        // 初始化失败回滚注册
                        registry.unregister(singletonDef);
                        throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                                "Bean 初始化失败: " + bean.getClass().getName(), e);
                    }
                }
                break;
            }
        }

        if (!registered) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException(
                    "没有找到匹配的 BeanDefinitionGenerator: type=" + bean.getClass().getName());
        }
    }

    /**
      * 注册一个 Beandefinition注册（编程式挂接，绕过 SPI）。
     *
     * <p>用于将外部容器（如 OSGi 框架、远程节点、第三方插件）提供的
      * Beandefinition注册 接入当前上下文，使其参与 Bean 查找与解析。
      * 该方法线程安全，重复注册同名 注册 将被忽略。</p>
     *
     * @param register 待注册的 Beandefinition注册
     * @return true 表示新增成功
     * @throws com.chua.common.support.objects.exception.BeanDefinitionException 入参为空或挂接失败
     */
    default boolean registerBean(BeanDefinitionRegister register) {
        if (register == null) {
            throw new com.chua.common.support.objects.exception.BeanDefinitionException("BeanDefinitionRegister 不能为空");
        }
        return getRegistry(getConfig().isSpiEnabled()).addRegister(register);
    }

    /**
     * 从容器中注销一个对象实例。
     * <p>先执行 Bean 销毁（@PreDestroy、DisposableAware），
     * 再从注册表中移除。匹配基于实例引用（{@code ==}），不依赖类型，
     * 避免同类型多 Bean 时误删。</p>
     *
     * @param bean 要注销的对象实例
     * @return true 表示找到并注销
     */
    default boolean unregisterBean(Object bean) {
        if (bean == null) {
            return false;
        }
        BeanDefinitionRegistry registry = getRegistry(getConfig().isSpiEnabled());
        boolean removed = false;
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition def = registry.getBeanDefinition(name);
            if (def == null) {
                continue;
            }
            boolean matched = false;
            try {
                matched = def.getBean() == bean;
            } catch (Exception ignored) {
            }
            if (matched) {
                try {
                    def.destroyBean();
                } catch (Exception ignored) {
                }
                removed |= registry.unregister(def);
            }
        }
        return removed;
    }

    // ==================== 包扫描 ====================

    /**
     * 扫描指定包路径下的所有类，自动实例化并注册到容器。
     * <p>只扫描有无参构造器的非接口、非抽象、非枚举类。</p>
     *
     * @param basePackage 基包路径
     */
    default void scan(String basePackage) {
        ObjectContextScanner.scan(this, basePackage);
    }

    /**
     * 扫描多个包路径下的所有类。
     *
     * @param basePackages 基包路径列表
     */
    default void scan(List<String> basePackages) {
        ObjectContextScanner.scan(this, basePackages);
    }

    // ==================== 注册中心 ====================

    /**
      * 获取 Beandefinitionregistry。
     *
     * @return Bean 定义注册中心
     */
    default BeanDefinitionRegistry getRegistry() {
        return getRegistry(getConfig().isSpiEnabled());
    }

    /**
      * 获取 Beandefinitionregistry，可控制是否启用 SPI 发现。
     *
     * @param spiEnabled 是否通过 SPI 发现 Beandefinition注册
     * @return Bean 定义注册中心
     */
    default BeanDefinitionRegistry getRegistry(boolean spiEnabled) {
        BeanDefinitionRegistry registry = REGISTRY_HOLDER.get(this);
        if (registry == null) {
            registry = REGISTRY_HOLDER.computeIfAbsent(this, k -> {
                BeanDefinitionRegistry r = new BeanDefinitionRegistry();
                r.initialize(spiEnabled);
                return r;
            });
        }
        return registry;
    }
}
