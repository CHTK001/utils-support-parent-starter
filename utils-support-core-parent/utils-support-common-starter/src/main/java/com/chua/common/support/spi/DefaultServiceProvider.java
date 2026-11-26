// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.spi;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.collection.SortedArrayList;
import com.chua.common.support.collection.SortedList;
import com.chua.common.support.constant.NameConstant;
import com.chua.common.support.function.InitializingAware;
import com.chua.common.support.function.SafeFunction;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.ProxyUtils;
import com.chua.common.support.proxy.intercept.DelegateMethodIntercept;
import com.chua.common.support.proxy.intercept.VoidMethodIntercept;
import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.autowire.AutoServiceAutowire;
import com.chua.common.support.spi.autowire.ServiceAutowire;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.spi.resolver.CustomServiceResolver;
import com.chua.common.support.spi.resolver.SamePackageServiceResolver;
import com.chua.common.support.spi.resolver.ServiceLoaderServiceResolver;
import com.chua.common.support.spi.resolver.ServiceResolver;
import com.chua.common.support.utils.*;
import com.chua.common.support.value.Value;
import com.google.common.base.Splitter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.chua.common.support.constant.NameConstant.DEFAULT;
import static com.chua.common.support.constant.NumberConstant.DEFAULT_SIZE;
import static com.chua.common.support.spi.definition.ServiceDefinition.COMPARATOR;


/**
 * 默认服务提供者实现，提供 SPI 机制的完整功能。
 *
 * <p>该类实现了 {@link ServiceProvider} 接口，提供了服务发现、注册、获取、管理等完整功能。
 * 支持多种服务解析方式，包括注解解析、服务加载 解析、自定义解析等。</p>
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>服务注册和发现</li>
 *   <li>按名称获取服务实例</li>
 *   <li>条件加载（基于类存在性、属性配置等）</li>
 *   <li>服务优先级排序</li>
 *   <li>服务监控和生命周期管理</li>
 *   <li>自动装配</li>
 * </ul>
 *
 * @param <T> 服务接口类型
 * @author CH
 * @since 1.0
 * @see ServiceProvider
 * @see ServiceDefinition
 */
@SuppressWarnings({"ALL", "unchecked"})
@Slf4j
public class DefaultServiceProvider<T> implements ServiceProvider<T>, InitializingAware {
    /**
     * SPI 名称缓存
     * 使用弱引用的 类 作为键
     */
    private static final Map<Class<?>, String> SPI_NAME =
        new ConcurrentReferenceHashMap<>(64, ConcurrentReferenceHashMap.ReferenceType.WEAK);

    /**
     * 服务类型
     */
    private final Class<T> type;
    /**
     * 类加载器
     */
    private final ClassLoader classLoader;
    /**
     * 自动装配器
     */
    private final ServiceAutowire serviceAutowire = new AutoServiceAutowire();
    /**
     * 服务定义映射表
     */
    private final Map<String, SortedList<ServiceDefinition>> definitions = new ConcurrentHashMap<>();
    /**
     * 默认服务定义列表
     */
    private final SortedList<ServiceDefinition> defaultDefinitions = new SortedArrayList<>(COMPARATOR);
    /**
     * 默认解析器列表
     */
    private final List<ServiceResolver> defaultResolvers = Wraps.list();
    /**
     * 是否已加载默认实现
     */
    private final AtomicBoolean hasDefaultAndLoaded = new AtomicBoolean(false);

    /**
     * 条件评估器
     */
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

    /**
     * 服务定义查找器
     */
    private final ServiceDefinitionFinder definitionFinder;

    /**
     * 缓存存活扩展实例
     */
    /**
     * 缓存存活扩展实例。
     *
     * <p><b>到期不 close</b>：调用方可能长期持有取到的实例，到期就 close 会让它在背后被关掉
     * （表现为"跑一段时间后突然报连接已关闭"）。生命周期交给容器/调用方，这里只做缓存淘汰。</p>
     */
    Cache<String, Value<T>> keepAlive = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES).build();

    /**
     * 默认实现
     */
    private T defaultImpl;

    /**
     * 定时任务执行器
     */
    private static volatile ScheduledExecutorService executor;

    /**
     * 创建 默认服务提供者 实例
     * @param type 类型
     * @param classLoader 类加载
     */
    public DefaultServiceProvider(Class<T> type, ClassLoader classLoader) {
        this.type = type;
        this.classLoader = classLoader;
        this.definitionFinder = new ServiceDefinitionFinder(definitions, serviceAutowire);
        afterPropertiesSet();
        definitionFinder.setDynamicResolvers(Collections.unmodifiableList(defaultResolvers), type, classLoader);
    }

    /**
     * 获取延伸
     */
    @Override
    public Set<String> getExtensions() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(currentNamedDefinitions().keySet()));
    }

    /**
     * 获取新延伸
     */
    @Override
    public List<T> getNewExtensions(String name, Object... args) {
        SortedList<ServiceDefinition> serviceDefinitions = getDefinitions(name);
        List<T> result = Wraps.list();
        for (ServiceDefinition serviceDefinition : serviceDefinitions) {
            Object newInstance = newExtension(serviceDefinition, args);
            if (null == newInstance) {
                continue;
            }
            result.add((T) newInstance);
        }

        if (result.isEmpty() && null != defaultImpl) {
            result.add(defaultImpl);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * OSGi 实例由框架管理生命周期；不得绕过注册实例重新调用插件构造器。
     *
     * @param definition 当前服务定义
     * @param args 静态扩展构造参数
     * @return 已注册的 OSGi 实例，或新建的静态扩展
     */
    private T newExtension(ServiceDefinition definition, Object... args) {
        if (isFrameworkManaged(definition)) {
            return (T) definition.getObj();
        }
        return definition.newInstance(serviceAutowire, args);
    }

    /**
     * 判断定义是否携带由 OSGi 框架管理的注册实例。
     *
     * @param definition 服务定义
     * @return 是否由框架管理
     */
    private boolean isFrameworkManaged(ServiceDefinition definition) {
        if (definition == null) {
            return false;
        }
        Class<?> finderType = definition.getFinderType();
        return finderType != null
                && com.chua.common.support.spi.resolver.OsgiServiceResolver.class.isAssignableFrom(finderType)
                && definition.getObj() != null;
    }

    /**
     * 空名称仍要求唯一扩展名，但候选必须包含当前动态服务。
     *
     * @return 唯一扩展名中优先级最高的定义，不唯一时返回 null
     */
    private ServiceDefinition singleDefinition() {
        SortedList<ServiceDefinition> current = getDefinitions(null);
        if (current.isEmpty()) {
            return null;
        }
        ServiceDefinition first = current.first();
        for (ServiceDefinition definition : current) {
            if (!Objects.equals(first.getName(), definition.getName())) {
                return null;
            }
        }
        return first;
    }

    /**
     * 按当前优先级为每个名称选取一个定义，静态和动态入口共用同一视图。
     *
     * @return 当前名称到定义的有序快照
     */
    private Map<String, ServiceDefinition> currentNamedDefinitions() {
        Map<String, ServiceDefinition> current = new LinkedHashMap<>();
        for (ServiceDefinition definition : getDefinitions(null)) {
            current.putIfAbsent(definition.getName(), definition);
        }
        return current;
    }

    /**
     * 获取延伸
     */
    @Override
    public T getExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            ServiceDefinition single = singleDefinition();
            return single == null ? getDefaultImpl() : single.getObj(serviceAutowire);
        }
        ServiceDefinition serviceDefinition = definitionFinder.getServiceDefinition(name);
        return (T) Optional.ofNullable(serviceDefinition.getObj(serviceAutowire)).orElseGet(this::getDefaultImpl);
    }

    /**
     * 获取延伸
     */
    @Override
    public T getExtension(String... name) {
        for (String s : name) {
            ServiceDefinition definition = definitionFinder.getServiceDefinition(s);
            if (null != definition) {
                return definition.getObj(serviceAutowire);
            }
        }
        return getDefaultImpl();
    }


    /**
     * 获取新延伸
     */
    @Override
    public T getNewExtension(String name, Object... args) {
        if (StringUtils.isEmpty(name)) {
            ServiceDefinition single = singleDefinition();
            return single == null ? getDefaultImpl(args) : newExtension(single, args);
        }
        ServiceDefinition serviceDefinition = definitionFinder.getServiceDefinition(name, args);
        T instance = newExtension(serviceDefinition, args);
        if (instance == null) {
            // 与 getExtension(name) 的语义对齐：名字没命中任何实现时，getServiceDefinition 返回的是
            // 空的 DEFAULT_DEFINITION（implClass 为 null，newInstance 必然得到 null），此时回落到
            // @SpiDefault 标注的默认实现。原实现只有「名字为空」才走默认实现，非空但未注册的名字
            // 会返回 null——调用方往往直接拿它调方法，于是把「没这个扩展」放大成 NPE/500，
            // 而真正有用的信息（扩展名是什么）在调用栈里反而消失。
            T fallback = getDefaultImpl(args);
            if (null != fallback) {
                log.debug("[ServiceProvider] 未命中扩展，回落到默认实现: name={}", name);
                return fallback;
            }
            // 原实现直接 orElse(getDefaultImpl(args))，实例化失败与「没有默认实现」都表现为
            // 返回 null，调用方只能报「未注册 / 实例化失败」，真实原因（构造器签名不匹配、
            // 还是构造过程抛异常）被完全丢弃。此处按 WARN 记一条，让失败可诊断。
            log.warn("[ServiceProvider] 扩展实例化失败: name={} args={} 原因={}", name,
                    args == null ? 0 : args.length, serviceDefinition.getFailureMessage());
        }
        return instance;
    }

    /**
     * 获取新延伸
     */
    @Override
    public T getNewExtension(Class<?> type, Object... args) {
        if (ClassUtils.isVoid(type)) {
            return null;
        }
        ServiceDefinition serviceDefinition = definitionFinder.getServiceDefinition(type, args);
        return null == serviceDefinition
                ? getDefaultImpl(args)
                : (T) Optional.ofNullable(newExtension(serviceDefinition, args)).orElseGet(() -> defaultImpl);
    }

    /**
     * 获取deep新延伸
     */
    @Override
    public T getDeepNewExtension(String name, Object... args) {
        if (null == name) {
            return getDefaultImpl(args);
        }
        name = name.toUpperCase();
        ServiceDefinition definition = definitionFinder.getServiceDefinition(name);
        if (ServiceDefinitionFinder.DEFAULT_DEFINITION != definition) {
            return (T) Optional.ofNullable(newExtension(definition, args))
                    .orElseGet(() -> getDefaultImpl(args));
        }

        while (StringUtils.isNotEmpty(name)) {
            name = FileUtils.getSimpleExtension(name);
            name = name.toUpperCase();
            definition = definitionFinder.getServiceDefinition(name);
            if (ServiceDefinitionFinder.DEFAULT_DEFINITION != definition) {
                return (T) Optional.ofNullable(newExtension(definition, args))
                        .orElseGet(() -> getDefaultImpl(args));
            }
        }

        return getDefaultImpl(args);
    }


    /**
     * 获取保活扩展
     */
    @Override
    public T getKeepExtension(String uid, String name, Object... args) {
        ServiceDefinition current = StringUtils.isEmpty(name) ? singleDefinition()
                : definitionFinder.getServiceDefinition(name, args);
        if (isFrameworkManaged(current)) {
            keepAlive.invalidate(uid);
            return (T) current.getObj();
        }
        if (current == null || current == ServiceDefinitionFinder.DEFAULT_DEFINITION) {
            keepAlive.invalidate(uid);
            return getNewExtension(name, args);
        }
        Value<T> ifPresent = keepAlive.getIfPresent(uid);
        if (null != ifPresent) {
            T value = ifPresent.getValue();
            keepAlive.put(uid, ifPresent);
            return value;
        }
        T newExtension = newExtension(current, args);
        if (newExtension == null) {
            return getDefaultImpl(args);
        }
        checkDaemon();
        keepAlive.put(uid, Value.of(newExtension));
        return newExtension;
    }

    /**
     * 校验Daemon
     */
    private void checkDaemon() {
        if (null == executor) {
            synchronized (DefaultServiceProvider.class) {
                if (null == executor) {
                    executor = ThreadUtils.newScheduledThreadPoolExecutor("spi-scheduler");
                    executor.scheduleAtFixedRate(() -> {
                        Collection<ServiceProvider<?>> values = ServiceProvider.CACHE.values();
                        for (ServiceProvider value : values) {
                            value.check();
                        }
                    }, 10, 10, TimeUnit.SECONDS);
                }
            }
        }
    }

    /**
     * 关闭保活扩展
     */
    @Override
    public void closeKeepExtension(String uid) {
        Value<T> tValue = keepAlive.getIfPresent(uid);
        if (null != tValue) {
            T value = tValue.getValue();
            if (value instanceof AutoCloseable) {
                IoUtils.closeQuietly((AutoCloseable) value);
            }
            keepAlive.invalidate(uid);
        }
    }

    /**
     * 获取valid提供者
     */
    @Override
    public T getValidProvider(Object... args) {
        return ProxyUtils.newProxy(type, classLoader, new DelegateMethodIntercept<>(type, proxyMethod -> {
            for (ServiceDefinition serviceDefinition : currentNamedDefinitions().values()) {
                Object t = newExtension(serviceDefinition, args);
                if (null == t) {
                    continue;
                }
                try {
                    Method method = proxyMethod.getMethod();
                    ReflectUtils.setAccessibleAccessible(method);
                    Object invoke = ReflectUtils.invoke(t, method.getName(), method.getReturnType(),
                            method.getParameterTypes(), proxyMethod.getArgs());
                    if (null != invoke && !Proxy.isProxyClass(invoke.getClass())) {
                        return invoke;
                    }
                } catch (Exception e) {
                    log.debug("[spi] 默认方法调用失败，继续查找其他实现: {}", e.getMessage());
                }
            }
            return ProxyUtils.proxy(type, classLoader, new VoidMethodIntercept<>());
        }));
    }

    /**
     * 获取ifpresent
     */
    @Override
    public Optional<T> getIfPresent(String name) {
        if (null == name) {
            ServiceDefinition single = singleDefinition();
            return single == null ? Optional.empty() : Optional.ofNullable(single.getObj(serviceAutowire));
        }

        return Optional.ofNullable(newExtension(definitionFinder.getServiceDefinition(name)));
    }

    /**
     * Collect
     *
     * <p>{@link #list()} 的 value 允许为 {@code null}（某个扩展实例化失败，例如缺少所需依赖或配置）。
     * 原实现直接 {@code List.copyOf(values)}，只要集合里存在一个 {@code null} 就会抛
     * {@code NullPointerException}，而该异常被此处的 {@code catch (Exception ignore)} 静默吞掉，
     * 方法随即返回空列表 —— 于是「16 个扩展里 14 个实例化失败」被放大成「一个都没有」，
     * 且没有任何日志，极难排查（AI 侧表现为「SPI 发现 0 个 ChatClient」）。</p>
     *
     * <p>这里改为先滤掉 {@code null} 再拷贝，并保持返回不可变列表：实例化失败的项被跳过，
     * 成功的项照常返回。</p>
     */
    @Override
    public List<T> collect() {
        Collection<T> values = list().values();
        if (CollectionUtils.isEmpty(values)) {
            return Collections.emptyList();
        }
        List<T> available = Wraps.list(values.size());
        int failed = 0;
        for (T value : values) {
            if (value == null) {
                failed++;
                continue;
            }
            available.add(value);
        }
        if (failed > 0) {
            log.warn("[ServiceProvider] {} 个扩展实例化失败已跳过（可用 {} 个）", failed, available.size());
        }
        return Collections.unmodifiableList(available);
    }

    /**
     * 列表类型
     */
    @Override
    public Map<String, Class<T>> listType() {
        Map<String, Class<T>> result = new LinkedHashMap<>();
        for (ServiceDefinition definition : currentNamedDefinitions().values()) {
            result.put(definition.getName(), (Class<T>) definition.getImplClass());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Collect
     */
    @Override
    public List<T> collect(Object... args) {
        List<T> result = new ArrayList<>();
        for (ServiceDefinition definition : currentNamedDefinitions().values()) {
            T instance = newExtension(definition, args);
            if (instance != null) {
                result.add(instance);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 列表
     */
    @Override
    public Map<String, T> list() {
        Map<String, T> result = new LinkedHashMap<>();
        for (ServiceDefinition definition : currentNamedDefinitions().values()) {
            result.put(definition.getName(), definition.getObj(serviceAutowire));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 列表
     */
    @Override
    public Map<String, T> list(Object... args) {
        Map<String, T> result = new LinkedHashMap<>();
        for (ServiceDefinition definition : currentNamedDefinitions().values()) {
            Object object = newExtension(definition, args);
            if (null == object) {
                continue;
            }
            result.put(definition.getName(), (T) object);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public List<?> options(boolean showAll) {
        return Collections.emptyList();
    }

    /**
     * 获取spi服务
     */
    @Override
    public T getSpiService() {
        String s = SPI_NAME.get(type);
        if (StringUtils.isEmpty(s)) {
            com.chua.common.support.spi.annotations.Spi spi =
                    type.getDeclaredAnnotation(com.chua.common.support.spi.annotations.Spi.class);
            if (null == spi) {
                throw new IllegalStateException("The " + type.getName() + " must contain the [@Spi] annotation!");
            }

            String[] value = spi.value();
            if (value.length == 0) {
                s = NameConstant.DEFAULT.toUpperCase();
            } else {
                s = value[0].toUpperCase();
            }
            SPI_NAME.putIfAbsent(type, s);
        }
        return getExtension(s);
    }

    /**
     * 是否支持
     */
    @Override
    public boolean isSupport(String name) {
        return null != name && !getDefinitions(name).isEmpty();
    }

    /**
     * foreach
     */
    @Override
    public void forEach(BiConsumer<String, T> consumer, Object... args) {
        list(args).forEach(consumer);
    }

    /**
     * fordefinitioneach
     */
    @Override
    public void forDefinitionEach(Consumer<ServiceDefinition> consumer) {
        currentNamedDefinitions().values().forEach(consumer);
    }

    /**
     * for任意definitioneach
     */
    @Override
    public void forAnyDefinitionEach(Consumer<ServiceDefinition> consumer) {
        getDefinitions(null).forEach(consumer);
    }

    /**
     * moreeach
     */
    @Override
    public void moreEach(BiConsumer<String, T> consumer) {
        Set<String> visited = new HashSet<>();
        for (ServiceDefinition definition : getDefinitions(null)) {
            String name = definition.getName();
            if (!visited.contains(name)) {
                T imageConverter = definition.getObj(serviceAutowire);
                if (null == imageConverter) {
                    continue;
                }
                consumer.accept(name, imageConverter);
                visited.add(name);
            }
        }
    }

    /**
     * 注销
     */
    @Override
    public void unregister(String baseName, Class<? extends ServiceResolver> resolverType) {
        Map<String, List<ServiceDefinition>> remove = Wraps.map(DEFAULT_SIZE);
        for (Map.Entry<String, SortedList<ServiceDefinition>> entry : definitions.entrySet()) {
            SortedList<ServiceDefinition> value = entry.getValue();
            for (ServiceDefinition serviceDefinition : value) {
                doRegisterRemoveCollection(remove, baseName, resolverType, serviceDefinition, entry.getKey());
            }
        }

        if (remove.isEmpty()) {
            return;
        }

        for (Map.Entry<String, List<ServiceDefinition>> entry : remove.entrySet()) {
            SortedList<ServiceDefinition> remaining = definitions.get(entry.getKey());
            if (null == remaining) {
                continue;
            }
            synchronized (remaining) {
                remaining.removeAll(entry.getValue());
                // 摘空后连同 key 一起移除：否则 getExtension(null) 的唯一键分支
                // 会对空列表调用 first() 抛 NoSuchElementException，
                // 宿主在 bundle 卸载后取服务应回落默认实现而不是抛异常
                if (remaining.isEmpty()) {
                    definitions.remove(entry.getKey());
                }
            }
        }
    }

    /**
     * 执行注册移除集合
     *
     * @param remove 移除
     * @param baseName 基础名称
     * @param resolverType 解析器类型
     * @param serviceDefinition 服务definition
     * @param key 键
     */
    private void doRegisterRemoveCollection(Map<String, List<ServiceDefinition>> remove, String baseName,
                                            Class<? extends ServiceResolver> resolverType,
                                            ServiceDefinition serviceDefinition, String key) {
        Class<?> finderType = serviceDefinition.getFinderType();
        if (null != finderType && resolverType.isAssignableFrom(finderType)) {
            doRegisterRemoveCollectionItem(baseName, key, remove, serviceDefinition);
        }
    }

    /**
     * 执行注册移除集合item
     *
     * @param baseName 基础名称
     * @param key 键
     * @param remove 移除
     * @param serviceDefinition 服务definition
     */
    private void doRegisterRemoveCollectionItem(String baseName, String key,
                                                Map<String, List<ServiceDefinition>> remove,
                                                ServiceDefinition serviceDefinition) {
        if (StringUtils.isBlank(baseName)) {
            remove.computeIfAbsent(key, it -> Wraps.list()).add(serviceDefinition);
        }
        if (baseName.equalsIgnoreCase(serviceDefinition.getName())) {
            remove.computeIfAbsent(key, it -> Wraps.list()).add(serviceDefinition);
        }
    }

    /**
     * 注册
     */
    @Override
    public void register(ServiceDefinition... definitions) {
        registerDefinition(List.of(definitions));
    }

    /**
     * 注册
     */
    @Override
    public void register(ServiceResolver resolver) {
        List<ServiceDefinition> resolve = resolver.resolve(type, classLoader);
        //
        registerDefinition(resolve);
    }


    /**
     * 注册
     */
    @Override
    public void register(String name, Object ref) {
        name = name.toUpperCase();
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setName(name);
        serviceDefinition.setObj(ref);
        serviceDefinition.setType(type);
        serviceDefinition.setImplClass(ref.getClass());
        SortedList<ServiceDefinition> list =
                definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR));
        synchronized (list) {
            list.add(serviceDefinition);
        }
        definitionFinder.clearMissCache();
    }

    /**
     * 注册
     */
    @Override
    public void register(String name, Class<T> ref) {
        name = name.toUpperCase();
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setName(name);
        serviceDefinition.setImplClass(ref);
        serviceDefinition.setType(type);
        SortedList<ServiceDefinition> list =
                definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR));
        synchronized (list) {
            list.add(serviceDefinition);
        }
        definitionFinder.clearMissCache();
    }

    /**
     * 获取对象提供者
     */
    @Override
    public T getObjectProvider(String names, Object... args) {
        List<String> requestedNames = new ArrayList<>();
        for (String name : Splitter.on(',').omitEmptyStrings().trimResults().splitToList(names)) {
            requestedNames.add(name);
        }
        Class<T> service = type;
        return ProxyUtils.newProxy(service, classLoader,
                new DelegateMethodIntercept<>(service, new SafeFunction<ProxyMethod, Object>() {
            /**
             * Safe应用
             */
            @Override
            public Object safeApply(ProxyMethod proxyMethod) throws Throwable {
                Object rs = null;
                for (String name : requestedNames) {
                    T t = getNewExtension(name, args);
                    if (t == null) {
                        continue;
                    }
                    rs = invoke(t, proxyMethod);
                }
                return rs;
            }

            /**
             * 调用
             *
             * @param t t
             * @param proxyMethod 代理方法
             * @return invoke的结果
             */
            private Object invoke(T t, ProxyMethod proxyMethod) {
                return proxyMethod.getValue(t);
            }
        }));
    }

    /**
     * 获取Definitions
     */
    @Override
    public SortedList<ServiceDefinition> getDefinitions(String name) {
        return definitionFinder.getDefinitions(name, (Object[]) null);
    }

    /**
     * 获取Definition
     */
    @Override
    public ServiceDefinition getDefinition(String type) {
        return CollectionUtils.findFirst(getDefinitions(type));
    }

    /**
     * 获取Definitions
     *
     * @param name 名称
     * @param args 参数
     * @return 获取definitions的结果
     */
    public SortedList<ServiceDefinition> getDefinitions(String name, Object... args) {
        return definitionFinder.getDefinitions(name, args);
    }

    /**
     * 校验：触发保活缓存的过期清理。
     * <p>历史实现会逐个 getIfPresent 刷新所有保活条目，导致 expireAfterAccess
     * 形同虚设、不再使用的会话被永久保留；此处改为仅清理已过期条目，
     * 让真正不再访问的会话按过期时间正常释放。</p>
     */
    @Override
    public void check() {
        keepAlive.cleanUp();
    }

    /**
     * collect新
     */
    @Override
    public List<T> collectNew() {
        List<T> result = new ArrayList<>();
        for (ServiceDefinition noneObject : currentNamedDefinitions().values()) {
            if (!isFrameworkManaged(noneObject)) {
                noneObject.setObj(null);
                noneObject.setLoaded(false);
            }
            Object obj = noneObject.getObj(serviceAutowire);
            if (null == obj) {
                continue;
            }
            result.add((T) obj);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 监控
     */
    @Override
    public ServiceProvider<T> monitor(boolean open) {
        return this;
    }

    /**
     * 是否空
     */
    @Override
    public boolean isEmpty() {
        return getDefinitions(null).isEmpty();
    }

    /**
     * 获取if可用
     */
    @Override
    public T getIfAvailable(String name, Object... args) {
        SortedList<ServiceDefinition> temp = definitionFinder.getDefinitions(name, args);
        for (ServiceDefinition serviceDefinition : temp) {
            Object object = newExtension(serviceDefinition, args);
            if (null != object) {
                return (T) object;
            }
        }
        return getDefaultImpl(args);
    }

    /**
     * 获取类型
     */
    @Override
    public Class<T> getType() {
        return type;
    }

    /**
     * 获取类加载
     */
    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * 支持类型
     */
    @Override
    public Set<String> supportedTypes() {
        return getExtensions();
    }

    /**
     * 获取新默认延伸
     */
    @Override
    public T getNewDefaultExtension(Object... args) {
        return getNewExtension(DEFAULT, args);
    }

    /**
     * 获取默认
     */
    @Override
    public T getDefault() {
        return defaultImpl;
    }

    /**
     * 获取priority服务definition
     */
    @Override
    public ServiceDefinition getPriorityServiceDefinition() {
        return CollectionUtils.findFirst(getPriorityServiceDefinitions());
    }

    /**
     * 获取Priority
     */
    @Override
    public T getPriority() {
        ServiceDefinition definition = getPriorityServiceDefinition();
        return definition == null ? null : newExtension(definition);
    }

    /**
     * 获取priority服务definitions
     */
    @Override
    public List<ServiceDefinition> getPriorityServiceDefinitions() {
        SortedList<ServiceDefinition> tempList = new SortedArrayList<>(COMPARATOR);
        tempList.addAll(getDefinitions(null));
        tempList.addAll(defaultDefinitions);
        return tempList;
    }

    /**
     * 名称
     */
    @Override
    public Set<String> names() {
        return getExtensions();
    }

    /**
     * 获取服务autowire
     */
    @Override
    public ServiceAutowire getServiceAutowire() {
        return serviceAutowire;
    }




    /**
     * 之后属性设置
     */
    @Override
    public void afterPropertiesSet() {
        if (Void.class == type) {
            return;
        }
        defaultResolvers.add(new ServiceLoaderServiceResolver());
        defaultResolvers.add(new CustomServiceResolver());
        defaultResolvers.add(new SamePackageServiceResolver());
        /**
         * 动态加载扩展解析器：script加载服务解析器 (utils-support-extension-starter)
         */
        ClassUtils.isPresent("com.chua.extension.support.spi.resolver.ScriptLoaderServiceResolver",
                             ServiceResolver.class, defaultResolvers::add);

        /**
         * 动态加载 Spring 解析器：spring服务解析器 (utils-support-spring-starter)
         */
        ClassUtils.isPresent("com.chua.spring.support.configuration.spi.SpringServiceResolver", ServiceResolver.class,
                             defaultResolvers::add);

        /**
         * 动态加载 OSGI 解析器：osgi服务解析器 (utils-support-osgi-starter)
         */
        ClassUtils.isPresent("com.chua.common.support.spi.resolver.OsgiServiceResolver", ServiceResolver.class,
                             defaultResolvers::add);

        for (ServiceResolver defaultResolver : Collections.unmodifiableList(defaultResolvers)) {
            if (defaultResolver.isDynamic()) {
                continue;
            }
            List<ServiceDefinition> resolve = defaultResolver.resolve(type, classLoader);
            registerDefinition(resolve);
        }
    }
    /**
     * 注册服务定义列表。
     * <p>
     * 遍历传入的服务定义集合，对每个定义进行名称校验、条件评估，
     * 并将符合条件的定义添加到内部映射表中。如果该定义被标记为默认实现，
     * 则调用 {@link #registerDefault(ServiceDefinition)} 进行特殊处理。
     * </p>
     *
     * @param analyze 待注册的服务定义列表
     */
    private void registerDefinition(List<ServiceDefinition> analyze) {
        for (ServiceDefinition serviceDefinition : analyze) {
            String name = serviceDefinition.getName();
            if (StringUtils.isEmpty(name)) {
                continue;
            }

            // 评估服务定义的条件是否满足（如类存在性、属性配置等）
            if (!conditionEvaluator.evaluate(serviceDefinition)) {
                continue;
            }

            // 将服务定义存入按优先级排序的列表中
            SortedList<ServiceDefinition> list =
                    definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR));
            synchronized (list) {
                list.add(serviceDefinition);
            }
            // 注册后清未命中缓存：否则之前查不到的名字会一直被缓存成"不存在"
            definitionFinder.clearMissCache();

            // 如果该定义是默认实现，则执行默认注册的逻辑
            if (serviceDefinition.isDefault()) {
                registerDefault(serviceDefinition);
            }
        }
    }

    /**
     * 注册默认服务实现。
     * <p>
     * 尝试从提供的服务定义中获取实例对象。如果实例创建成功，则缓存到成员变量中并设置加载标志；
     * 如果实例创建失败（例如依赖注入失败），则将定义添加到 {@code defaultDefinitions} 列表中以备后续重试或延迟加载。
     * 该方法具有幂等性，一旦默认实例加载完成，后续调用将直接返回。
     * </p>
     *
     * @param serviceDefinition 需要注册为默认实现的服务定义
     */
    private void registerDefault(ServiceDefinition serviceDefinition) {
        // 框架实例随插件生命周期变化，默认选择交给当前定义视图，不持久缓存实例。
        if (isFrameworkManaged(serviceDefinition)) {
            return;
        }
        if (hasDefaultAndLoaded.get()) {
            return;
        }
        this.defaultImpl = serviceDefinition.getObj(serviceAutowire);
        if (null != this.defaultImpl) {
            hasDefaultAndLoaded.set(true);
            return;
        }
        defaultDefinitions.add(serviceDefinition);
    }


    /**
     * 获取所有服务定义的映射表。
     * <p>
     * 该方法遍历当前注册的所有服务定义，提取每个名称对应的第一个（优先级最高）定义实例。
     * 如果 {@code definitions} 为空，则返回空映射。
     * </p>
     *
     * @param args 实例化参数，本方法未实际使用，但保持接口一致性
     * @return 包含服务名称到服务定义映射的不可变或可变 映射，若为空则返回空 映射
     */
    private Map<String, ServiceDefinition> listDefinition(Object[] args) {
        // 如果定义列表为空，直接返回空映射
        if (definitions.isEmpty()) {
            return Collections.emptyMap();
        }

        // 初始化结果映射，容量预设为定义数量以提高性能
        Map<String, ServiceDefinition> result = Wraps.map(definitions.size());

        // 遍历所有服务名称对应的定义列表
        for (SortedList<ServiceDefinition> value : this.definitions.values()) {
            // 获取列表中优先级最高的定义对象
            ServiceDefinition noneObject = value.first();

            // 如果该定义对象为空，跳过本次循环
            if (null == noneObject) {
                continue;
            }

            // 将服务名称与定义对象存入结果映射
            result.put(noneObject.getName(), noneObject);
        }

        // 返回构建完成的映射表
        return result;
    }

    /**
     * 获取默认实现实例。
     * <p>
     * 首先尝试返回已缓存的 {@code defaultImpl}，
     * 如果不存在则遍历 {@code defaultDefinitions} 列表，
     * 创建并返回第一个可用的实例；框架托管的默认实现从当前定义中实时选择。
     * </p>
     *
     * @param args 实例化参数
     * @return 默认实现实例，若未找到则返回 空
     */
    private T getDefaultImpl(Object... args) {
        // 如果已经存在默认的实例对象，直接返回
        if (null != defaultImpl) {
            return defaultImpl;
        }

        // 遍历默认定义列表，尝试创建实例
        for (ServiceDefinition defaultDefinition : defaultDefinitions) {
            // 使用单例自动装配器创建新实例
            Object object = defaultDefinition.newInstance(AutoServiceAutowire.INSTANCE, args);

            // 如果实例创建成功，返回该实例
            if (null != object) {
                return (T) object;
            }
        }

        // 插件默认实现不能写入静态默认缓存，卸载后当前定义视图即不再包含它。
        for (ServiceDefinition definition : getDefinitions(null)) {
            if (definition.isDefault() && isFrameworkManaged(definition)) {
                return (T) definition.getObj();
            }
        }

        // 如果所有尝试都失败，返回空。
        return null;
    }
}
