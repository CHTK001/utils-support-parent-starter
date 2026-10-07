package com.chua.common.support.collection.wrap;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集合后端解析器：负责按 SPI 选择 {@link CollectionBackend} 并缓存结果。
 *
 * <p>选择顺序：</p>
 * <ol>
 *   <li>系统属性 {@value #PROPERTY} 指定名称（如 {@code jdk}）时按名称精确匹配；</li>
 *   <li>否则默认 {@link EclipseCollectionBackend}（Eclipse Collections）；</li>
 *   <li>Eclipse Collections 不在 classpath 或无任何可用后端时，回退 {@link JdkCollectionBackend}。</li>
 * </ol>
 *
 * <p>候选后端来自两处（按名称去重，先注册者优先）：</p>
 * <ul>
 *   <li>内置后端 {@link EclipseCollectionBackend}（仅当 EC 在 classpath）与 {@link JdkCollectionBackend}；</li>
 *   <li>{@code META-INF/services/com.chua.common.support.collection.wrap.CollectionBackend}
 *       注册的第三方后端。</li>
 * </ul>
 *
 * <p><b>为什么用轻量 {@link ServiceLoader} 而不是项目的 {@code ServiceProvider} 框架：</b>
 * 本类处于集合创建的底层路径，项目 SPI 框架的静态缓存初始化本身会创建集合，
 * 若在解析阶段回调框架，会在类初始化期间形成环。这里仅做一次文件级 SPI 载入，
 * 结果缓存于 {@link #backend}，开销可忽略。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see CollectionBackend
 * @see Wraps
 */
@Slf4j
public final class CollectionBackends {

    /**
     * 后端选择系统属性名。
     */
    public static final String PROPERTY = "utils.support.collection.backend";

    /**
     * 连续 SPI 载入失败上限：达到后放弃继续枚举，避免坏 provider 导致的死循环。
     */
    private static final int MAX_FAILURES = 5;

    /**
     * 解析重入保护：解析过程中（如日志初始化）再次触发集合创建时，直接给 JDK 后端，避免递归。
     */
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();

    /**
     * 已解析的后端（双检锁缓存）。
     */
    private static volatile CollectionBackend backend;

    /**
     * 内部工具类，禁止实例化。
     */
    private CollectionBackends() {
    }

    /**
     * 获取当前后端（首次调用时解析并缓存）。
     *
     * @return 集合后端，永不为 {@code null}
     */
    public static CollectionBackend get() {
        CollectionBackend local = backend;
        if (local == null) {
            synchronized (CollectionBackends.class) {
                local = backend;
                if (local == null) {
                    local = doResolve();
                    backend = local;
                }
            }
        }
        return local;
    }

    /**
     * 当前后端名称。
     *
     * @return 后端名称，如 {@code eclipse} / {@code jdk}
     */
    public static String name() {
        return get().name();
    }

    /**
     * 显式切换后端（按名称），并持久化为当前后端。
     *
     * <p>主要供启动期配置与测试使用；未知名称直接抛出异常（编程错误应快速失败），
     * 而系统属性指向未知名称时仅告警并走默认策略。</p>
     *
     * @param name 后端名称，如 {@code eclipse} / {@code jdk} / 第三方注册名
     * @return 切换后的后端
     * @throws IllegalArgumentException 名称为空或未注册
     */
    public static CollectionBackend use(String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("集合后端名称不能为空");
        }
        CollectionBackend chosen = candidates().get(key);
        if (chosen == null) {
            throw new IllegalArgumentException(
                    "未注册的集合后端: " + name + "，可用: " + candidates().keySet());
        }
        synchronized (CollectionBackends.class) {
            backend = chosen;
        }
        log.info("集合后端已切换为: {}", key);
        return chosen;
    }

    /**
     * 清空缓存并按当前系统属性重新解析（供测试与动态配置变更后调用）。
     *
     * @return 重新解析后的后端
     */
    public static CollectionBackend reload() {
        synchronized (CollectionBackends.class) {
            backend = null;
        }
        return get();
    }

    /**
     * 解析后端：系统属性 → 默认 eclipse → jdk 兜底。
     *
     * @return 解析出的后端
     */
    private static CollectionBackend doResolve() {
        if (Boolean.TRUE.equals(RESOLVING.get())) {
            // 解析过程中重入（典型：日志实现初始化时创建集合），先给 JDK 后端打断递归。
            return new JdkCollectionBackend();
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            Map<String, CollectionBackend> candidates = candidates();
            String want = normalize(System.getProperty(PROPERTY, ""));
            CollectionBackend resolved = null;
            if (!want.isEmpty()) {
                resolved = candidates.get(want);
                if (resolved == null) {
                    log.warn("系统属性 {}={} 未匹配任何已注册后端，可用: {}，将使用默认策略",
                            PROPERTY, want, candidates.keySet());
                }
            }
            if (resolved == null) {
                // 默认 eclipse；EC 缺失时内置候选里没有 eclipse，自然落到 jdk。
                resolved = candidates.get(EclipseCollectionBackend.NAME);
            }
            if (resolved == null) {
                resolved = candidates.get(JdkCollectionBackend.NAME);
            }
            if (resolved == null) {
                // 理论不可达：内置 jdk 兜底一定存在；防御性再建一次。
                resolved = new JdkCollectionBackend();
            }
            log.debug("集合后端解析完成: name={}, 属性指定={}", resolved.name(), want);
            return resolved;
        } finally {
            RESOLVING.remove();
        }
    }

    /**
     * 收集候选后端：内置后端 + SPI 注册的第三方后端，按名称去重。
     *
     * @return 名称（小写）到后端的有序映射
     */
    private static Map<String, CollectionBackend> candidates() {
        Map<String, CollectionBackend> map = new LinkedHashMap<>();
        // 1. 内置：Eclipse 仅在 classpath 存在时注册，避免触发构造失败。
        // 直接用后端自带的无缓存探测：ClassUtils.isPresent 以类名为唯一键做跨
        // ClassLoader 缓存，一次误判会毒化整个 JVM，这里不能用它。
        if (EclipseCollectionBackend.isPresent("org.eclipse.collections.impl.map.mutable.UnifiedMap")) {
            register(map, new EclipseCollectionBackend());
        }
        register(map, new JdkCollectionBackend());
        // 2. SPI 注册文件中的第三方/内置 provider（内置重名者被 putIfAbsent 去重）。
        ClassLoader own = CollectionBackends.class.getClassLoader();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        collectFromServiceLoader(map, tccl);
        if (tccl != own) {
            collectFromServiceLoader(map, own);
        }
        return map;
    }

    /**
     * 用指定 ClassLoader 载入 SPI provider 并登记（坏 provider 跳过）。
     *
     * @param map        候选映射
     * @param classLoader 类加载器
     */
    private static void collectFromServiceLoader(Map<String, CollectionBackend> map, ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        Iterator<CollectionBackend> it;
        try {
            it = ServiceLoader.load(CollectionBackend.class, classLoader).iterator();
        } catch (Throwable t) {
            log.debug("SPI 载入集合后端失败，跳过该 classLoader", t);
            return;
        }
        int failures = 0;
        while (failures < MAX_FAILURES) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                register(map, it.next());
            } catch (Throwable t) {
                failures++;
                log.debug("SPI 集合后端 provider 不可用（第 {} 次）", failures, t);
            }
        }
    }

    /**
     * 登记后端（按名称去重，忽略名字为空的 provider），并同步登记 {@code @Spi} 声明的别名。
     *
     * @param map 候选映射
     * @param candidate 后端实例
     */
    private static void register(Map<String, CollectionBackend> map, CollectionBackend candidate) {
        if (candidate == null) {
            return;
        }
        String key = normalize(candidate.name());
        if (key.isEmpty()) {
            log.warn("忽略未命名的集合后端: {}", candidate.getClass().getName());
            return;
        }
        map.putIfAbsent(key, candidate);
        // @Spi(value = ...) 声明的别名同样可作为切换名（如 eclipse 的别名 ec）。
        Spi spi = candidate.getClass().getAnnotation(Spi.class);
        if (spi != null) {
            for (String alias : spi.value()) {
                String aliasKey = normalize(alias);
                if (!aliasKey.isEmpty()) {
                    map.putIfAbsent(aliasKey, candidate);
                }
            }
        }
    }

    /**
     * 名称归一化：去空白 + 小写。
     *
     * @param name 原始名称
     * @return 归一化后的名称
     */
    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
