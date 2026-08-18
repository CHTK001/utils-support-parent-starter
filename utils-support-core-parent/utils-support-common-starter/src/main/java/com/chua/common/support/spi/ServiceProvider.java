package com.chua.common.support.spi;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.collection.SortedList;
import com.chua.common.support.proxy.intercept.DelegateMethodIntercept;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.ProxyUtils;
import com.chua.common.support.spi.autowire.ServiceAutowire;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.spi.resolver.ServiceResolver;
import com.chua.common.support.utils.ClassUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 服务提供者接口，提供 SPI（Service Provider Interface）机制的核心功能。
 * <p>
 * 该接口是整个 SPI 框架的核心，提供了服务发现、注册、获取、管理等完整功能。
 * 支持通过名称获取服务实例、服务注册、服务发现、条件加载等高级特性。
 * </p>
 * <p>
 * 主要特点：
 * </p>
 * <ul>
 *   <li>支持按名称获取服务实例</li>
 *   <li>支持服务注册和发现</li>
 *   <li>支持条件加载（基于类存在性、属性配置等）</li>
 *   <li>支持服务优先级排序</li>
 *   <li>支持服务监控和生命周期管理</li>
 * </ul>
 * <p>
 * 使用示例：
 * </p>
 * <pre>{@code
 * // 获取服务提供者
 * ServiceProvider<DataProcessor> provider = ServiceProvider.of(DataProcessor.class);
 *
 * // 获取指定名称的服务
 * DataProcessor processor = provider.getExtension("json");
 *
 * // 获取所有服务
 * List<DataProcessor> processors = provider.collect();
 *
 * // 注册新服务
 * provider.register("custom", new CustomDataProcessor());
 * }</pre>
 *
 * @param <T> 服务接口类型
 * @author CH
 * @since 1.0
 * @see DefaultServiceProvider
 * @see ServiceDefinition
 */
@SuppressWarnings({"ALL", "unchecked"})
public interface ServiceProvider<T> {

    /**
     * 空的服务提供者实例。
     * 用于在无法找到有效服务时返回的默认值。
     */
    ServiceProvider<Void> EMPTY = new DefaultServiceProvider<>(Void.class, Thread.currentThread().getContextClassLoader());

    /**
     * 缓存已创建的服务提供者实例。
     * 使用弱引用映射以避免内存泄漏，初始容量为 256。
     */
    Map<Class<?>, ServiceProvider<?>> CACHE = new ConcurrentReferenceHashMap<>(256);

    /**
     * 根据类名字符串获取服务提供者。
     * 如果类不存在，则返回空的提供者。
     *
     * @param value 类的全限定名
     * @param <T>   服务类型
     * @return 服务提供者实例
     */
    @Nonnull
    static <T> ServiceProvider<T> of(@Nullable String value) {
        if (!ClassUtils.isPresent(value)) {
            return (ServiceProvider<T>) EMPTY;
        }
        Class<?> aClass = ClassUtils.forName(value);
        return (ServiceProvider<T>) of(aClass);
    }

    /**
     * 根据服务类型获取服务提供者。
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务提供者实例
     */
    @Nonnull
    static <T> ServiceProvider<T> ofService(@Nonnull Class<T> type) {
        return of(type);
    }

    /**
     * 根据类对象获取服务提供者。
     * 使用当前线程的上下文类加载器。
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务提供者实例
     */
    @Nonnull
    static <T> ServiceProvider<T> of(@Nonnull Class<T> type) {
        return of(type, type.getClassLoader());
    }

    /**
     * 根据类对象和类加载器获取服务提供者。
     * 结果会被缓存以避免重复创建。
     *
     * @param type        服务接口类型
     * @param classLoader 类加载器
     * @param <T>         服务类型
     * @return 服务提供者实例
     */
    @Nonnull
    static <T> ServiceProvider<T> of(@Nonnull Class<T> type, @Nullable ClassLoader classLoader) {
        try {
            return (ServiceProvider<T>) CACHE.computeIfAbsent(type, new Function<Class<?>, ServiceProvider<?>>() {
                @Override
                public ServiceProvider<?> apply(Class<?> aClass) {
                    return new DefaultServiceProvider<>(type, Optional.ofNullable(classLoader).orElse(Thread.currentThread().getContextClassLoader()));
                }
            });
        } catch (Exception e) {
            // 忽略异常，直接返回新的实例
        }
        return new DefaultServiceProvider<>(type, Optional.ofNullable(classLoader).orElse(Thread.currentThread().getContextClassLoader()));
    }

    /**
     * 获取所有已注册的扩展名称集合。
     *
     * @return 扩展名称集合
     */
    @Nonnull
    Set<String> getExtensions();

    /**
     * 根据名称创建新的服务实例列表。
     * 支持通过构造函数参数传递依赖。
     *
     * @param name 服务名称
     * @param args 构造参数
     * @return 服务实例列表
     */
    @Nonnull
    List<T> getNewExtensions(@Nullable String name, @Nonnull Object... args);

    /**
     * 根据名称获取单个服务实例。
     *
     * @param name 服务名称
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    T getExtension(@Nullable String name);

    /**
     * 根据多个名称获取服务实例，优先返回第一个匹配项。
     *
     * @param name 服务名称数组
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    T getExtension(@Nonnull String... name);

    /**
     * 根据枚举值获取服务实例。
     *
     * @param name 枚举值
     * @return 服务实例，如果未找到或值为 null 则返回 null
     */
    @Nullable
    default T getExtension(@Nullable Enum name) {
        if (name == null) {
            return null;
        }
        return getExtension(name.name());
    }

    /**
     * 根据名称创建新的服务实例。
     *
     * @param name 服务名称
     * @param args 构造参数
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    T getNewExtension(@Nullable String name, @Nonnull Object... args);

    /**
     * 根据类型创建新的服务实例。
     *
     * @param type 目标类型
     * @param args 构造参数
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    T getNewExtension(@Nonnull Class<?> type, @Nonnull Object... args);

    /**
     * 根据枚举值创建新的服务实例。
     *
     * @param name 枚举值
     * @param args 构造参数
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    default T getNewExtension(@Nullable Enum name, @Nonnull Object... args) {
        if (name == null) {
            return getNewExtension("", args);
        }
        return getNewExtension(name.name(), args);
    }

    /**
     * 创建默认服务实例（无名称）。
     *
     * @param args 构造参数
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    default T newExtension(@Nonnull Object... args) {
        return getNewExtension("", args);
    }

    /**
     * 根据枚举值和参数获取服务实例。
     *
     * @param name 枚举值
     * @param args 构造参数
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    default T getExtension(@Nullable Enum name, @Nonnull Object... args) {
        return getNewExtension(name, args);
    }

    /**
     * 深度获取新的服务实例，可能涉及更复杂的解析逻辑。
     *
     * @param name 服务名称
     * @param args 构造参数
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    T getDeepNewExtension(@Nullable String name, @Nonnull Object... args);

    /**
     * 根据唯一标识符获取或创建服务实例并保留引用。
     *
     * @param uid  唯一标识符
     * @param name 服务名称
     * @param args 构造参数
     * @return 服务实例，如果未找到则返回 null
     */
    @Nullable
    T getKeepExtension(@Nonnull String uid, @Nullable String name, @Nonnull Object... args);

    /**
     * 关闭并清理指定唯一标识符保留的服务实例。
     *
     * @param uid 唯一标识符
     */
    void closeKeepExtension(@Nonnull String uid);

    /**
     * 获取有效的服务提供者实例。
     *
     * @param args 构造参数
     * @return 服务实例，如果无效则返回 null
     */
    @Nullable
    T getValidProvider(@Nonnull Object... args);

    /**
     * 尝试获取指定名称的服务实例，如果存在则返回 Optional。
     *
     * @param name 服务名称
     * @return 包含服务实例的 Optional
     */
    @Nonnull
    Optional<T> getIfPresent(@Nullable String name);

    /**
     * 收集所有可用的服务实例。
     *
     * @return 服务实例列表
     */
    @Nonnull
    List<T> collect();

    /**
     * 遍历所有可用的服务实例并执行操作。
     *
     * @param consumer 消费函数
     */
    default void collect(@Nonnull Consumer<T> consumer) {
        List<T> list = collect();
        for (T item : list) {
            consumer.accept(item);
        }
    }

    /**
     * 获取服务名称到类型的映射关系。
     *
     * @return 映射表
     */
    @Nonnull
    default Map<String, Class<T>> mapping() {
        return listType();
    }

    /**
     * 获取服务名称到类型的详细映射关系。
     *
     * @return 映射表
     */
    @Nonnull
    Map<String, Class<T>> listType();

    /**
     * 根据参数收集所有可用的服务实例。
     *
     * @param args 构造参数
     * @return 服务实例列表
     */
    @Nonnull
    List<T> collect(@Nonnull Object... args);

    /**
     * 获取所有已注册服务的名称到实例的映射。
     *
     * @return 映射表
     */
    @Nonnull
    Map<String, T> list();

    /**
     * 根据参数获取所有已注册服务的名称到实例的映射。
     *
     * @param args 构造参数
     * @return 映射表
     */
    @Nonnull
    Map<String, T> list(@Nonnull Object... args);

    /**
     * 获取默认的 SPI 服务实例。
     *
     * @return SPI 服务实例
     */
    @Nullable
    T getSpiService();

    /**
     * 检查是否支持指定名称的服务。
     *
     * @param name 服务名称
     * @return true 表示支持，false 表示不支持
     */
    boolean isSupport(@Nullable String name);

    /**
     * 检查是否包含指定扩展名的服务。
     *
     * @param extension 扩展名
     * @return true 表示包含，false 表示不包含
     */
    default boolean has(@Nullable String extension) {
        return isSupport(extension);
    }

    /**
     * 获取可用的选项列表。
     *
     * @return 选项列表
     */
    @Nonnull
    default List<?> options() {
        return Collections.emptyList();
    }

    /**
     * 遍历所有服务实例并执行操作。
     *
     * @param consumer 消费函数，接收名称和实例
     * @param args     构造参数
     */
    void forEach(@Nonnull BiConsumer<String, T> consumer, @Nonnull Object... args);

    /**
     * 遍历所有服务定义并执行操作。
     *
     * @param consumer 消费函数
     */
    void forDefinitionEach(@Nonnull Consumer<ServiceDefinition> consumer);

    /**
     * 遍历任意服务定义并执行操作。
     *
     * @param consumer 消费函数
     */
    void forAnyDefinitionEach(@Nonnull Consumer<ServiceDefinition> consumer);

    /**
     * 更多遍历操作。
     *
     * @param consumer 消费函数
     */
    void moreEach(@Nonnull BiConsumer<String, T> consumer);

    /**
     * 获取显示所有选项的列表。
     *
     * @param showAll 是否显示所有选项
     * @return 选项列表
     */
    @Nonnull
    List<?> options(boolean showAll);

    /**
     * 注销指定的服务解析器。
     *
     * @param resolverType 解析器类型
     */
    default void unregister(@Nonnull Class<? extends ServiceResolver> resolverType) {
        unregister(null, resolverType);
    }

    /**
     * 根据基础名称注销指定的服务解析器。
     *
     * @param baseName     基础名称
     * @param resolverType 解析器类型
     */
    void unregister(@Nullable String baseName, @Nonnull Class<? extends ServiceResolver> resolverType);

    /**
     * 注册服务定义。
     *
     * @param definitions 服务定义数组
     */
    void register(@Nonnull ServiceDefinition... definitions);

    /**
     * 注册服务解析器。
     *
     * @param resolver 服务解析器
     */
    void register(@Nonnull ServiceResolver resolver);

    /**
     * 注册名称到对象的映射。
     *
     * @param name 名称
     * @param ref  引用对象
     */
    void register(@Nonnull String name, @Nonnull Object ref);

    /**
     * 注册名称到类的映射。
     *
     * @param name 名称
     * @param ref  引用类
     */
    void register(@Nonnull String name, @Nonnull Class<T> ref);

    /**
     * 获取对象提供者。
     *
     * @param names 名称
     * @param args  构造参数
     * @return 服务实例
     */
    @Nullable
    T getObjectProvider(@Nullable String names, @Nonnull Object... args);

    /**
     * 获取指定名称的服务定义列表。
     *
     * @param name 服务名称
     * @return 排序后的服务定义列表
     */
    @Nonnull
    SortedList<ServiceDefinition> getDefinitions(@Nullable String name);

    /**
     * 获取指定类型的服务定义。
     *
     * @param type 类型
     * @return 服务定义，如果未找到则返回 null
     */
    @Nullable
    ServiceDefinition getDefinition(@Nullable String type);

    /**
     * 检查服务状态是否正常。
     */
    void check();

    /**
     * 收集所有新的服务实例。
     *
     * @return 服务实例列表
     */
    @Nonnull
    List<T> collectNew();

    /**
     * 启用或禁用监控功能。
     *
     * @param open 是否开启监控
     * @return 当前服务提供者实例
     */
    @Nonnull
    ServiceProvider<T> monitor(boolean open);

    /**
     * 检查服务提供者是否为空。
     *
     * @return true 表示为空，false 表示不为空
     */
    boolean isEmpty();

    /**
     * 获取可用服务实例，如果未指定名称则尝试获取默认实例。
     *
     * @param name 服务名称
     * @param args 构造参数
     * @return 服务实例
     */
    @Nullable
    T getIfAvailable(@Nullable String name, @Nonnull Object... args);

    /**
     * 获取可用服务实例（重载方法）。
     *
     * @param args 构造参数
     * @return 服务实例
     */
    @Nullable
    default T getIfAvailable(@Nonnull Object... args) {
        return getIfAvailable(null, args);
    }

    /**
     * 获取服务接口的类型。
     *
     * @return 服务类型
     */
    @Nonnull
    Class<T> getType();

    /**
     * 获取服务提供者使用的类加载器。
     *
     * @return 类加载器
     */
    @Nonnull
    ClassLoader getClassLoader();

    /**
     * 获取所有可用服务的代理实例。
     * 当调用代理方法时，会依次调用所有服务实例的方法，直到有一个成功返回结果。
     *
     * @param args 构造参数
     * @return 代理对象
     */
    @Nullable
    default T getAllVailable(@Nonnull Object... args) {
        List<T> collect = collect(args);
        if (collect.isEmpty()) {
            return null;
        }
        return ProxyUtils.newProxy(getType(), getClassLoader(), new DelegateMethodIntercept<>(getType(), new Function<ProxyMethod, Object>() {
            @Override
            public Object apply(ProxyMethod proxyMethod) {
                Object result = null;
                for (T t : collect) {
                    try {
                        result = proxyMethod.invoke(t);
                        if (result != null) {
                            break;
                        }
                    } catch (Exception e) {
                        // 捕获异常并继续尝试下一个实例
                    }
                }
                return result;
            }
        }));
    }

    /**
     * 获取按优先级自动降级的服务代理实例（复用已注册实例）。
     *
     * <p>返回的代理对象实现了服务接口，调用任一方法时按优先级（{@link SpiOrder} 大者优先）
     * 依次尝试已注册的实现；当前实现失败（抛出异常）时自动降级到下一优先级实现。
     * 全部失败则抛出最后一个异常。</p>
     *
     * @param name 服务名称
     * @return 服务代理，未找到任何实现时返回 null
     */
    @Nullable
    default T getExtensionFactory(@Nullable String name) {
        List<T> instances = (name == null || name.isEmpty())
                ? collect()
                : getNewExtensions(name);
        return proxyFactory(instances);
    }

    /**
     * 获取按优先级自动降级的服务代理实例（每次新建实例）。
     *
     * <p>与 {@link #getExtensionFactory(String)} 语义一致，区别在于实例通过构造参数新建，
     * 不缓存引用。调用任一方法时按优先级依次尝试，失败自动降级到下一实现。</p>
     *
     * @param name 服务名称
     * @param args 构造参数
     * @return 服务代理，未找到任何实现时返回 null
     */
    @Nullable
    default T getNewExtensionFactory(@Nullable String name, @Nonnull Object... args) {
        List<T> instances = getNewExtensions(name, args);
        return proxyFactory(instances);
    }

    /**
     * 构建按优先级自动降级的服务代理。
     *
     * @param instances 按优先级排序的服务实例列表（高优先级在前）
     * @return 服务代理，列表为空时返回 null
     */
    @Nullable
    default T proxyFactory(@Nonnull List<T> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        List<T> unique = new ArrayList<>(instances.size());
        Set<Class<?>> seen = new HashSet<>();
        for (T t : instances) {
            Class<?> implClass = t.getClass();
            if (seen.add(implClass)) {
                unique.add(t);
            }
        }
        return ProxyUtils.newProxy(getType(), getClassLoader(), new DelegateMethodIntercept<>(getType(), new Function<ProxyMethod, Object>() {
            @Override
            public Object apply(ProxyMethod proxyMethod) {
                Exception last = null;
                for (T t : unique) {
                    if (!isAvailable(t, proxyMethod)) {
                        continue;
                    }
                    try {
                        Object result = proxyMethod.invoke(t);
                        if (result != null) {
                            return result;
                        }
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (last != null) {
                    throw new IllegalStateException("所有服务实例均执行失败", last);
                }
                return null;
            }
        }));
    }

    /**
     * 判断服务实例是否可用。
     *
     * <p>优先调用实例的 {@code available()} 方法（存在时）；不可用则跳过，实现自动降级。
     * 代理拦截到调用目标方法时需排除 {@code available}/{@code name} 等元信息方法。</p>
     *
     * @param instance 服务实例
     * @param proxyMethod 当前代理调用
     * @return true 表示可用
     */
    default boolean isAvailable(@Nonnull T instance, @Nonnull ProxyMethod proxyMethod) {
        String methodName = proxyMethod.getMethodName();
        if (methodName == null || "available".equals(methodName)) {
            return true;
        }
        try {
            Method available = instance.getClass().getMethod("available");
            if (available.getReturnType() == boolean.class) {
                return (boolean) available.invoke(instance);
            }
        } catch (Exception ignored) {
            // 无 available() 方法的实例视为可用
        }
        return true;
    }

    /**
     * 获取支持的类型集合。
     *
     * @return 支持的类型集合
     */
    @Nonnull
    Set<String> supportedTypes();

    /**
     * 获取默认的新扩展实例。
     *
     * @param args 构造参数
     * @return 服务实例
     */
    @Nullable
    T getNewDefaultExtension(@Nonnull Object... args);

    /**
     * 获取默认服务实例。
     *
     * @return 服务实例
     */
    @Nullable
    T getDefault();

    /**
     * 获取最高优先级的服务定义。
     *
     * @return 服务定义
     */
    @Nullable
    ServiceDefinition getPriorityServiceDefinition();

    /**
     * 获取最高优先级的服务实例。
     *
     * @return 服务实例
     */
    @Nullable
    T getPriority();

    /**
     * 获取所有按优先级排序的服务定义。
     *
     * @return 服务定义列表
     */
    @Nonnull
    List<ServiceDefinition> getPriorityServiceDefinitions();

    /**
     * 获取高优先级服务实例（别名方法）。
     *
     * @return 服务实例
     */
    @Nullable
    default T getHighPriority() {
        return getPriority();
    }

    /**
     * 获取排名第一的服务实例（别名方法）。
     *
     * @return 服务实例
     */
    @Nullable
    default T top1() {
        return getPriority();
    }

    /**
     * 获取所有服务的名称集合。
     *
     * @return 名称集合
     */
    @Nonnull
    Set<String> names();

    /**
     * 获取服务自动注入器。
     *
     * @return 服务自动注入器
     */
    @Nonnull
    ServiceAutowire getServiceAutowire();
}
