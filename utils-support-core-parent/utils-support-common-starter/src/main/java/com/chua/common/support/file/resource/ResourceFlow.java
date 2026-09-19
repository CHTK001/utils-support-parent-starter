package com.chua.common.support.file.resource;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.chua.common.support.constant.NameConstant.CLASSPATH_URL_ALL_PREFIX;
import static com.chua.common.support.constant.NameConstant.CLASSPATH_URL_PREFIX;

/**
 * 资源查找统一入口。
 *
 * <p>根据资源定位符的协议前缀分发到对应的 {@link ResourceFinder}，并对查找结果进行按类加载器+路径的
 * 二级缓存。当前内置支持两种协议：</p>
 * <ul>
 *   <li>{@code classpath:} —— 仅从首个匹配的类路径位置加载</li>
 *   <li>{@code classpath*:} —— 从所有类路径位置（含父加载器链与 {@code java.class.path}）加载</li>
 * </ul>
 *
 * <p>未显式指定协议时默认按 {@code classpath:} 处理。</p>
 *
 * <p><b>用法示例</b>：</p>
 * <pre>
 * // 扫描 classpath 下首个匹配的 config 目录中的 yml 文件
 * Set&lt;Resource&gt; resources = ResourceFlow.of("classpath:config/*.yml").getResources();
 *
 * // 扫描所有 classpath 中的指定资源
 * Set&lt;Resource&gt; all = ResourceFlow.of("classpath*:META-INF/services/*.xml").getResources();
 *
 * // 自定义配置
 * ResourceConfiguration config = ResourceConfiguration.builder().isParallel(true).build();
 * Set&lt;Resource&gt; custom = ResourceFlow.of("classpath*:com/chua/*.class", config).getResources();
 * </pre>
 *
 * @author CH
 * @since 1.0.0
 */
@Slf4j
public class ResourceFlow {

    /**
     * 默认协议前缀。
     */
    private static final String DEFAULT_PROTOCOL = "classpath:";

    /**
     * 协议分隔符。
     */
    private static final String PROTOCOL_SEPARATOR = ":";

    /**
     * 默认缓存容量。
     */
    private static final int DEFAULT_CACHE_CAPACITY = 128;

    /**
     * ClassLoader → (路径 → ResourceFlow) 二级缓存。
     */
    private static final Map<ClassLoader, Map<String, ResourceFlow>> PROVIDER_CACHE =
            new ConcurrentReferenceHashMap<>(DEFAULT_CACHE_CAPACITY);

    /**
     * 协议 → 查找器实例缓存（查找器为无状态对象，按配置维度复用）。
     */
    private static final Map<String, ResourceFinder> FINDER_CACHE = new ConcurrentHashMap<>(4);

    /**
     * 结果缓存（路径 → 资源集合）。
     */
    private final Map<String, Set<Resource>> storeCache =
            new ConcurrentReferenceHashMap<>(DEFAULT_CACHE_CAPACITY);

    /**
     * 资源路径（已剥离协议前缀）。
     */
    private final String name;

    /**
     * 实际查找器。
     */
    private final ResourceFinder resourceFinder;

    /**
     * 查找配置。
     */
    private final ResourceConfiguration configuration;

    /**
     * 构造资源流。
     *
     * @param name           资源路径（已剥离协议前缀）
     * @param resourceFinder 查找器
     * @param configuration  查找配置
     */
    private ResourceFlow(String name, ResourceFinder resourceFinder, ResourceConfiguration configuration) {
        this.name = name;
        this.resourceFinder = resourceFinder;
        this.configuration = configuration;
    }

    // ================================ 工厂方法 ================================

    /**
     * 使用默认配置创建资源流。
     *
     * <p>未显式包含协议前缀时默认按 {@code classpath:} 处理。</p>
     *
     * @param name 资源定位符，如 {@code classpath:config/*.yml}
     * @return 资源流实例，入参为空时返回 {@code null}
     */
    public static ResourceFlow of(String name) {
        return of(name, ResourceConfiguration.DEFAULT);
    }

    /**
     * 使用指定配置创建资源流，结果按类加载器+路径缓存。
     *
     * @param name          资源定位符
     * @param configuration 查找配置
     * @return 资源流实例，入参为空时返回 {@code null}
     */
    public static ResourceFlow of(String name, ResourceConfiguration configuration) {
        if (StringUtils.isEmpty(name)) {
            log.warn("资源定位符为空，返回 null");
            return null;
        }
        if (configuration == null) {
            configuration = ResourceConfiguration.DEFAULT;
        }
        String normalizedName = normalizeName(name);
        ClassLoader classLoader = configuration.getClassLoader();
        ResourceConfiguration finalConfiguration = configuration;
        return PROVIDER_CACHE
                .computeIfAbsent(classLoader, cl -> new ConcurrentReferenceHashMap<>(DEFAULT_CACHE_CAPACITY))
                .computeIfAbsent(normalizedName, key -> createFlow(key, finalConfiguration));
    }

    /**
     * 创建资源流但不写入缓存。
     *
     * @param name          资源定位符
     * @param configuration 查找配置
     * @return 资源流实例，入参为空时返回 {@code null}
     */
    public static ResourceFlow ofNoCache(String name, ResourceConfiguration configuration) {
        if (StringUtils.isEmpty(name)) {
            return null;
        }
        return createFlow(normalizeName(name),
                configuration != null ? configuration : ResourceConfiguration.DEFAULT);
    }

    /**
     * 规范化资源定位符，未含协议前缀时补全默认 {@code classpath:}。
     *
     * @param name 原始定位符
     * @return 规范化后的定位符
     */
    private static String normalizeName(String name) {
        return name.contains(PROTOCOL_SEPARATOR) ? name : DEFAULT_PROTOCOL + name;
    }

    /**
     * 根据协议前缀解析查找器并构造资源流。
     *
     * <p>仅识别 {@code classpath:} 与 {@code classpath*:}，其他协议回退到空查找器。</p>
     *
     * @param name          规范化后的定位符
     * @param configuration 查找配置
     * @return 资源流实例
     */
    private static ResourceFlow createFlow(String name, ResourceConfiguration configuration) {
        int index = name.indexOf(PROTOCOL_SEPARATOR);
        String protocol = name.substring(0, index + 1);
        String resourcePath = name.substring(index + 1);

        ResourceFinder finder = FINDER_CACHE.computeIfAbsent(protocol, key -> createFinder(key, configuration));
        if (finder == null) {
            log.warn("不支持的资源协议: {}，回退到空结果", protocol);
            return new ResourceFlow(resourcePath, EmptyResourceFinder.INSTANCE, configuration);
        }
        return new ResourceFlow(resourcePath, finder, configuration);
    }

    /**
     * 按协议创建查找器。
     *
     * @param protocol      协议前缀（含冒号）
     * @param configuration 查找配置
     * @return 查找器实例，不支持的协议返回 {@code null}
     */
    private static ResourceFinder createFinder(String protocol, ResourceConfiguration configuration) {
        if (CLASSPATH_URL_PREFIX.equals(protocol)) {
            return new ClassPathResourceFinder(configuration);
        }
        if (CLASSPATH_URL_ALL_PREFIX.equals(protocol)) {
            return new ClassPathAnyResourceFinder(configuration);
        }
        return null;
    }

    // ================================ 结果访问 ================================

    /**
     * 获取匹配到的全部资源集合（带结果缓存）。
     *
     * @return 资源集合
     */
    public Set<Resource> getResources() {
        Set<Resource> cached = storeCache.get(name);
        if (cached != null) {
            return cached;
        }
        Set<Resource> resources = resourceFinder.find(name);
        if (resources == null) {
            resources = Collections.emptySet();
        }
        storeCache.put(name, resources);
        return resources;
    }

    /**
     * 获取首个匹配的资源。
     *
     * @return 首个资源，无匹配时返回 {@code null}
     */
    public Resource getResource() {
        return CollectionUtils.findFirst(getResources());
    }

    /**
     * 按过滤器获取匹配资源。
     *
     * @param filter 过滤谓词，为 null 时返回全部
     * @return 过滤后的资源集合
     */
    public Set<Resource> getResources(Predicate<Resource> filter) {
        if (filter == null) {
            return getResources();
        }
        return getResources().stream().filter(filter).collect(Collectors.toSet());
    }

    /**
     * 以流形式访问资源。
     *
     * @return 资源流，按配置决定是否并行
     */
    public Stream<Resource> stream() {
        Set<Resource> resources = getResources();
        return configuration != null && configuration.isParallel()
                ? resources.parallelStream()
                : resources.stream();
    }

    /**
     * 获取首个资源的输入流。
     *
     * @return 输入流，无资源或打开失败时返回 {@code null}
     */
    public InputStream getInputStream() {
        Resource resource = getResource();
        if (resource == null) {
            return null;
        }
        try {
            return resource.openStream();
        } catch (Exception e) {
            log.error("打开资源流失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 判断是否存在匹配资源。
     *
     * @return 存在返回 true
     */
    public boolean exists() {
        return !getResources().isEmpty();
    }

    /**
     * 获取匹配资源数量。
     *
     * @return 资源数量
     */
    public int count() {
        return getResources().size();
    }

    // ================================ 缓存管理 ================================

    /**
     * 清除指定类加载器的所有缓存。
     *
     * @param classLoader 类加载器
     */
    public static void clearCache(ClassLoader classLoader) {
        if (classLoader != null) {
            Map<String, ResourceFlow> removed = PROVIDER_CACHE.remove(classLoader);
            if (removed != null) {
                removed.values().forEach(flow -> flow.storeCache.clear());
            }
        }
    }

    /**
     * 清除全部缓存。
     */
    public static void clearAllCache() {
        PROVIDER_CACHE.values().forEach(map -> map.values().forEach(flow -> flow.storeCache.clear()));
        PROVIDER_CACHE.clear();
    }

    /**
     * 清除当前资源流的结果缓存。
     */
    public void clearStoreCache() {
        storeCache.clear();
    }

    /**
     * 获取资源路径。
     *
     * @return 资源路径
     */
    public String getName() {
        return name;
    }

    /**
     * 获取查找配置。
     *
     * @return 查找配置
     */
    public ResourceConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    /**
     * ToString
    */
    public String toString() {
        return String.format("ResourceFlow{name='%s', finder=%s}",
                name, resourceFinder.getClass().getSimpleName());
    }

    /**
     * 空结果查找器，用于不支持的协议。
     */
    private static final class EmptyResourceFinder implements ResourceFinder {

        /**
         * 单例实例。
         */
        static final EmptyResourceFinder INSTANCE = new EmptyResourceFinder();

        @Override
        /**
         * 查找
        */
        public Set<Resource> find(String name) {
            return Collections.emptySet();
        }
    }
}
