package com.chua.common.support.spi;

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
    private final List<ServiceResolver> defaultResolvers = new LinkedList<>();
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
    Cache<String, Value<T>> keepAlive = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener((RemovalListener<String, Value<T>>) notification -> {
                RemovalCause cause = notification.getCause();
                if (cause == RemovalCause.EXPIRED) {
                    closeKeepExtension(notification.getKey());
                }
            })
            .build();

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
    * @param classLoader 类加载
    */
    public DefaultServiceProvider(Class<T> type, ClassLoader classLoader) {
        this.type = type;
        this.classLoader = classLoader;
        this.definitionFinder = new ServiceDefinitionFinder(definitions, serviceAutowire);
        afterPropertiesSet();
        definitionFinder.setDynamicResolvers(Collections.unmodifiableList(defaultResolvers), type, classLoader);
    }

    @Override
    /** 获取延伸 */
    public Set<String> getExtensions() {
        return definitions.keySet();
    }

    @Override
    /** 获取新延伸 */
    public List<T> getNewExtensions(String name, Object... args) {
        SortedList<ServiceDefinition> serviceDefinitions = getDefinitions(name);
        List<T> result = new LinkedList<>();
        for (ServiceDefinition serviceDefinition : serviceDefinitions) {
            Object newInstance = serviceDefinition.newInstance(serviceAutowire, args);
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

    @Override
    /** 获取延伸 */
    public T getExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            return definitions.size() == 1 ? definitions.values().iterator().next().first().getObj(serviceAutowire) : getDefaultImpl();
        }
        ServiceDefinition serviceDefinition = definitionFinder.getServiceDefinition(name);
        return (T) Optional.ofNullable(serviceDefinition.getObj(serviceAutowire)).orElse(getDefaultImpl());
    }

    @Override
    /** 获取延伸 */
    public T getExtension(String... name) {
        for (String s : name) {
            ServiceDefinition definition = definitionFinder.getServiceDefinition(s);
            if (null != definition) {
                return definition.getObj(serviceAutowire);
            }
        }
        return getDefaultImpl();
    }


    @Override
    /** 获取新延伸 */
    public T getNewExtension(String name, Object... args) {
        if (StringUtils.isEmpty(name)) {
            return definitions.size() == 1 ? definitions.values().iterator().next().first().newInstance(serviceAutowire, args) : getDefaultImpl(args);
        }
        ServiceDefinition serviceDefinition = definitionFinder.getServiceDefinition(name, args);
        return (T) Optional.ofNullable(serviceDefinition.newInstance(serviceAutowire, args)).orElse(getDefaultImpl(args));
    }

    @Override
    /** 获取新延伸 */
    public T getNewExtension(Class<?> type, Object... args) {
        if (ClassUtils.isVoid(type)) {
            return null;
        }
        ServiceDefinition serviceDefinition = definitionFinder.getServiceDefinition(type, args);
        return null == serviceDefinition ? getDefaultImpl(args) : (T) Optional.ofNullable(serviceDefinition.newInstance(serviceAutowire, args)).orElse(defaultImpl);
    }

    @Override
    /** 获取deep新延伸 */
    public T getDeepNewExtension(String name, Object... args) {
        if (null == name) {
            return getDefaultImpl(args);
        }
        name = name.toUpperCase();
        ServiceDefinition definition = definitionFinder.getServiceDefinition(name);
        if (ServiceDefinitionFinder.DEFAULT_DEFINITION != definition) {
            return (T) Optional.ofNullable(definition.newInstance(serviceAutowire, args)).orElse(getDefaultImpl(args));
        }

        while (StringUtils.isNotEmpty(name)) {
            name = FileUtils.getSimpleExtension(name);
            name = name.toUpperCase();
            definition = definitionFinder.getServiceDefinition(name);
            if (ServiceDefinitionFinder.DEFAULT_DEFINITION != definition) {
                return (T) Optional.ofNullable(definition.newInstance(serviceAutowire, args)).orElse(getDefaultImpl(args));
            }
        }

        return getDefaultImpl(args);
    }


    @Override
    /** 获取keep延伸 */
    public T getKeepExtension(String uid, String name, Object... args) {
        checkDaemon();
        Value<T> ifPresent = keepAlive.getIfPresent(uid);
        if (null != ifPresent) {
            T value = ifPresent.getValue();
            keepAlive.put(uid, ifPresent);
            return value;
        }
        T newExtension = getNewExtension(name, args);
        keepAlive.put(uid, Value.of(newExtension));
        return newExtension;
    }

    /** 校验Daemon */
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
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> ThreadUtils.shutdownNow(executor)));
                }
            }
        }
    }

    @Override
    /** 关闭keep延伸 */
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

    @Override
    /** 获取valid提供者 */
    public T getValidProvider(Object... args) {
        Map<String, ServiceDefinition> list = listDefinition(args);
        SortedList<ServiceDefinition> values = new SortedArrayList<>(COMPARATOR);
        values.addAll(list.values());
        return ProxyUtils.newProxy(type, classLoader, new DelegateMethodIntercept<>(type, proxyMethod -> {
            for (ServiceDefinition serviceDefinition : values) {
                Object t = serviceDefinition.newInstance(serviceAutowire, args);
                if (null == t) {
                    continue;
                }
                try {
                    Method method = proxyMethod.getMethod();
                    ClassUtils.setAccessible(method);
                    Object invoke = ReflectUtils.invoke(t, method.getName(), method.getReturnType(), proxyMethod.getArgs(), new Object[0], new Object[0]);
                    if (null != invoke && !Proxy.isProxyClass(invoke.getClass())) {
                        return invoke;
                    }
                } catch (Exception ignore) {
                }
            }
            return ProxyUtils.proxy(type, classLoader, new VoidMethodIntercept<>());
        }));
    }

    @Override
    /** 获取ifpresent */
    public Optional<T> getIfPresent(String name) {
        if (null == name) {
            return definitions.size() == 1 ? Optional.ofNullable(definitions.values().iterator().next().first().getObj(serviceAutowire)) : Optional.empty();
        }

        return Optional.ofNullable(definitionFinder.getServiceDefinition(name).newInstance(serviceAutowire));
    }

    @Override
    /** Collect */
    public List<T> collect() {
        Collection<T> values = list().values();
        if (CollectionUtils.isEmpty(values)) {
            return Collections.emptyList();
        }
        try {
            return List.copyOf(values);
        } catch (Exception ignore) {
        }
        return Collections.emptyList();
    }

    @Override
    /** 列表类型 */
    public Map<String, Class<T>> listType() {
        if (definitions.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Class<T>> result = new HashMap<>(definitions.size());

        for (SortedList<ServiceDefinition> value : this.definitions.values()) {
            ServiceDefinition noneObject = value.first();
            if (null == noneObject) {
                continue;
            }
            result.put(noneObject.getName(), (Class<T>) noneObject.getImplClass());
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    /** Collect */
    public List<T> collect(Object... args) {
        Map<T, Integer> temp = new HashMap<>(definitions.size());
        for (SortedList<ServiceDefinition> value : this.definitions.values()) {
            ServiceDefinition noneObject = value.first();
            if (null == noneObject) {
                continue;
            }
            T o = noneObject.newInstance(new AutoServiceAutowire(), args);
            if (null == o) {
                continue;
            }
            temp.put(o, noneObject.getOrder());
        }
        List<T> rs = new SortedArrayList<T>(Comparator.comparingInt(o -> temp.getOrDefault(o, 0)).reversed());
        rs.addAll(CollectionUtils.keySet(temp));
        return Collections.unmodifiableList(rs);
    }

    @Override
    /** 列表 */
    public Map<String, T> list() {
        if (definitions.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, T> result = new HashMap<>(definitions.size());

        for (SortedList<ServiceDefinition> value : this.definitions.values()) {
            ServiceDefinition noneObject = value.first();
            if (null == noneObject) {
                continue;
            }
            result.put(noneObject.getName(), noneObject.getObj(serviceAutowire));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    /** 列表 */
    public Map<String, T> list(Object... args) {
        if (definitions.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, T> result = new HashMap<>(definitions.size());

        for (SortedList<ServiceDefinition> value : this.definitions.values()) {
            ServiceDefinition noneObject = value.first();
            if (null == noneObject) {
                continue;
            }
            Object object = noneObject.newInstance(serviceAutowire, args);
            if (null == object) {
                continue;
            }
            result.put(noneObject.getName(), (T) object);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public List<?> options(boolean showAll) {
        return Collections.emptyList();
    }

    @Override
    /** 获取spi服务 */
    public T getSpiService() {
        String s = SPI_NAME.get(type);
        if (StringUtils.isEmpty(s)) {
            com.chua.common.support.spi.annotations.Spi spi = type.getDeclaredAnnotation(com.chua.common.support.spi.annotations.Spi.class);
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

    @Override
    /** 是否支持 */
    public boolean isSupport(String name) {
        return null != name && definitions.containsKey(name.toUpperCase());
    }

    @Override
    /** foreach */
    public void forEach(BiConsumer<String, T> consumer, Object... args) {
        list(args).forEach(consumer);
    }

    @Override
    /** fordefinitioneach */
    public void forDefinitionEach(Consumer<ServiceDefinition> consumer) {
        for (Map.Entry<String, SortedList<ServiceDefinition>> entry : definitions.entrySet()) {
            SortedList<ServiceDefinition> value = entry.getValue();
            consumer.accept(value.first());
        }
    }

    @Override
    /** for任意definitioneach */
    public void forAnyDefinitionEach(Consumer<ServiceDefinition> consumer) {
        for (Map.Entry<String, SortedList<ServiceDefinition>> entry : definitions.entrySet()) {
            SortedList<ServiceDefinition> value = entry.getValue();
            for (ServiceDefinition serviceDefinition : value) {
                consumer.accept(serviceDefinition);
            }
        }
    }

    @Override
    /** moreeach */
    public void moreEach(BiConsumer<String, T> consumer) {
        for (Map.Entry<String, SortedList<ServiceDefinition>> entry : definitions.entrySet()) {
            SortedList<ServiceDefinition> value = entry.getValue();
            for (ServiceDefinition definition : value) {
                T imageConverter = definition.getObj(serviceAutowire);
                if (null == imageConverter) {
                    continue;
                }
                consumer.accept(entry.getKey(), imageConverter);
                break;
            }
        }
    }

    @Override
    /** 注销 */
    public void unregister(String baseName, Class<? extends ServiceResolver> resolverType) {
        Map<String, List<ServiceDefinition>> remove = new HashMap<>(DEFAULT_SIZE);
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
            definitions.get(entry.getKey()).removeAll(entry.getValue());
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
    private void doRegisterRemoveCollection(Map<String, List<ServiceDefinition>> remove, String baseName, Class<? extends ServiceResolver> resolverType, ServiceDefinition serviceDefinition, String key) {
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
    private void doRegisterRemoveCollectionItem(String baseName, String key, Map<String, List<ServiceDefinition>> remove, ServiceDefinition serviceDefinition) {
        if (StringUtils.isBlank(baseName)) {
            remove.computeIfAbsent(key, it -> new LinkedList<>()).add(serviceDefinition);
        }
        if (baseName.equalsIgnoreCase(serviceDefinition.getName())) {
            remove.computeIfAbsent(key, it -> new LinkedList<>()).add(serviceDefinition);
        }
    }

    @Override
    /** 注册 */
    public void register(ServiceDefinition... definitions) {
        registerDefinition(List.of(definitions));
    }

    @Override
    /** 注册 */
    public void register(ServiceResolver resolver) {
        List<ServiceDefinition> resolve = resolver.resolve(type, classLoader);
        //                   
        registerDefinition(resolve);
    }


    @Override
    /** 注册 */
    public void register(String name, Object ref) {
        name = name.toUpperCase();
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setObj(ref);
        serviceDefinition.setType(type);
        serviceDefinition.setImplClass(ref.getClass());
        definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR)).add(serviceDefinition);
    }

    @Override
    /** 注册 */
    public void register(String name, Class<T> ref) {
        name = name.toUpperCase();
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setImplClass(ref);
        serviceDefinition.setType(type);
        definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR)).add(serviceDefinition);
    }

    @Override
    /** 获取对象提供者 */
    public T getObjectProvider(String names, Object... args) {
        List<T> impl = new LinkedList<>();
        for (String name : Splitter.on(',').omitEmptyStrings().trimResults().splitToList(names)) {
            T deepNewExtension = getNewExtension(name, args);
            if (null != deepNewExtension) {
                impl.add(deepNewExtension);
            }
        }
        Class<T> service = type;
        return ProxyUtils.newProxy(service, classLoader, new DelegateMethodIntercept<>(service, new SafeFunction<ProxyMethod, Object>() {
            @Override
            /** Safe应用 */
            public Object safeApply(ProxyMethod proxyMethod) throws Throwable {
                Object rs = null;
                for (T t : impl) {
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

    @Override
    /** 获取Definitions */
    public SortedList<ServiceDefinition> getDefinitions(String name) {
        if (null == name) {
            SortedList<ServiceDefinition> result = new SortedArrayList<>(COMPARATOR);
            definitions.values().forEach(result::addAll);
            return result;
        }
        return definitions.getOrDefault(name.toUpperCase(), SortedList.emptyList());
    }

    @Override
    /** 获取Definition */
    public ServiceDefinition getDefinition(String type) {
        type = type.toUpperCase();
        return CollectionUtils.findFirst(definitions.get(type));
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

    @Override
    /** 校验 */
    public void check() {
        for (String key : keepAlive.asMap().keySet()) {
            keepAlive.getIfPresent(key);
        }
    }

    @Override
    /** collect新 */
    public List<T> collectNew() {
        if (definitions.isEmpty()) {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>(definitions.size());

        for (SortedList<ServiceDefinition> value : this.definitions.values()) {
            ServiceDefinition noneObject = value.first();
            if (null == noneObject) {
                continue;
            }
            noneObject.setObj(null);
            noneObject.setLoaded(false);
            Object obj = noneObject.getObj(serviceAutowire);
            if (null == obj) {
                continue;
            }
            result.add((T) obj);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    /** 监控 */
    public ServiceProvider<T> monitor(boolean open) {
        return this;
    }

    @Override
    /** 是否空 */
    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    @Override
    /** 获取if可用 */
    public T getIfAvailable(String name, Object... args) {
        SortedList<ServiceDefinition> temp = definitionFinder.getDefinitions(name, args);
        for (ServiceDefinition serviceDefinition : temp) {
            Object object = serviceDefinition.newInstance(serviceAutowire, args);
            if (null != object) {
                return (T) object;
            }
        }
        return getDefaultImpl(args);
    }

    @Override
    /** 获取类型 */
    public Class<T> getType() {
        return type;
    }

    @Override
    /** 获取类加载 */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    /** 支持类型 */
    public Set<String> supportedTypes() {
        return definitions.keySet();
    }

    @Override
    /** 获取新默认延伸 */
    public T getNewDefaultExtension(Object... args) {
        return getNewExtension(DEFAULT, args);
    }

    @Override
    /** 获取默认 */
    public T getDefault() {
        return defaultImpl;
    }

    @Override
    /** 获取priority服务definition */
    public ServiceDefinition getPriorityServiceDefinition() {
        Collection<SortedList<ServiceDefinition>> values = definitions.values();
        SortedList<ServiceDefinition> tempList = new SortedArrayList<>(COMPARATOR);
        for (SortedList<ServiceDefinition> value : values) {
            tempList.addAll(value);
        }
        tempList.addAll(defaultDefinitions);
        return tempList.first();
    }

    @Override
    /** 获取Priority */
    public T getPriority() {
        return getPriorityServiceDefinition().newInstance(serviceAutowire);
    }

    @Override
    /** 获取priority服务definitions */
    public List<ServiceDefinition> getPriorityServiceDefinitions() {
        Collection<SortedList<ServiceDefinition>> values = definitions.values();
        SortedList<ServiceDefinition> tempList = new SortedArrayList<>(COMPARATOR);
        for (SortedList<ServiceDefinition> value : values) {
            tempList.addAll(value);
        }
        tempList.addAll(defaultDefinitions);
        return tempList;
    }

    @Override
    /** 名称 */
    public Set<String> names() {
        return definitions.keySet();
    }

    @Override
    /** 获取服务autowire */
    public ServiceAutowire getServiceAutowire() {
        return serviceAutowire;
    }




    @Override
    /** 之后属性设置 */
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
        ClassUtils.isPresent("com.chua.extension.support.spi.resolver.ScriptLoaderServiceResolver", ServiceResolver.class, defaultResolvers::add);

        /**
        * 动态加载 Spring 解析器：spring服务解析器 (utils-support-spring-starter)
        */
        ClassUtils.isPresent("com.chua.spring.support.configuration.spi.SpringServiceResolver", ServiceResolver.class, defaultResolvers::add);

        /**
        * 动态加载 OSGI 解析器：osgi服务解析器 (utils-support-osgi-starter)
        */
        ClassUtils.isPresent("com.chua.common.support.spi.resolver.OsgiServiceResolver", ServiceResolver.class, defaultResolvers::add);

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
            definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR)).add(serviceDefinition);

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
        Map<String, ServiceDefinition> result = new HashMap<>(definitions.size());

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
    * 创建并返回第一个可用的实例。
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

 // 如果所有尝试都失败，返回 空
        return null;
    }
}
