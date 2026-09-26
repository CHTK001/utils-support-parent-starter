package com.chua.common.support.serialize;

import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.ObjectInputFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 反序列化统一守卫。
 *
 * <p>所有"按报文中携带的类名还原对象"的序列化实现（JDK 原生 {@link JavaSerializer}、
 * {@code RpcSerialization} 的 JDK 兜底、Fury、Kryo）都必须经本类判定类名，
 * 使黑名单只需要维护一处，且第三方能通过注册
 * {@link DeserializationPolicy} 扩展点零侵入地收紧规则。</p>
 *
 * <p>判定规则：</p>
 * <ul>
 *   <li>类名必须被 classpath 上<strong>全部</strong> {@link DeserializationPolicy} 放行，
 *       任一策略拒绝即拒绝（AND 语义，插件只能收紧不能放宽）</li>
 *   <li>数组维度与 {@code L...;} 描述符会被剥离后再判定，避免 {@code [Lcom.sun.x;} 绕过</li>
 *   <li>JDK 路径额外限制对象图深度与数组长度，防止恶意报文耗尽内存</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class DeserializationGuard {

    /**
     * 允许的对象图最大深度
     */
    public static final int MAX_DEPTH = 64;

    /**
     * 允许的单数组最大长度
     */
    public static final long MAX_ARRAY_LENGTH = 100_000L;

    /**
     * 类名判定结果缓存上限，超出后不再写入，避免恶意报文用随机类名耗尽内存
     */
    private static final int DECISION_CACHE_LIMIT = 4096;

    /**
     * 类名判定结果缓存
     */
    private static final ConcurrentMap<String, Boolean> DECISIONS = new ConcurrentHashMap<>();

    /**
     * 已加载的策略实例，延迟初始化
     */
    private static volatile List<DeserializationPolicy> policies;

    /**
     * 工具类，禁止实例化
     */
    private DeserializationGuard() {
    }

    /**
     * 判断类名能否被反序列化。
     *
     * @param className 报文中携带的类名，可能是数组描述符
     * @return 全部策略放行时返回 {@code true}
     */
    public static boolean isAllowed(String className) {
        String normalized = normalize(className);
        if (normalized.isEmpty()) {
            return true;
        }
        Boolean cached = DECISIONS.get(normalized);
        if (cached != null) {
            return cached;
        }
        boolean allowed = computeAllowed(normalized);
        if (DECISIONS.size() < DECISION_CACHE_LIMIT) {
            DECISIONS.put(normalized, allowed);
        }
        return allowed;
    }

    /**
     * 断言类名允许反序列化，拒绝时抛出异常。
     *
     * @param className 报文中携带的类名
     * @throws SecurityException 命中任一策略的拒绝规则
     */
    public static void requireAllowed(String className) {
        if (!isAllowed(className)) {
            throw new SecurityException("反序列化被拒绝，类名命中安全策略: " + className);
        }
    }

    /**
     * 构造 JDK 反序列化过滤器，供 {@code ObjectInputStream#setObjectInputFilter} 安装。
     *
     * <p>除类名策略外，同时限制对象图深度与数组长度。</p>
     *
     * @return 反序列化过滤器
     */
    public static ObjectInputFilter objectInputFilter() {
        return info -> {
            if (info.depth() > MAX_DEPTH) {
                return ObjectInputFilter.Status.REJECTED;
            }
            if (info.arrayLength() >= 0 && info.arrayLength() > MAX_ARRAY_LENGTH) {
                return ObjectInputFilter.Status.REJECTED;
            }
            Class<?> serialClass = info.serialClass();
            if (serialClass == null) {
                return ObjectInputFilter.Status.UNDECIDED;
            }
            return isAllowed(serialClass.getName())
                    ? ObjectInputFilter.Status.ALLOWED
                    : ObjectInputFilter.Status.REJECTED;
        };
    }

    /**
     * 当前生效的策略列表，用于自检与观测。
     *
     * @return 不可变策略列表
     */
    public static List<DeserializationPolicy> policies() {
        List<DeserializationPolicy> loaded = policies;
        if (loaded == null) {
            loaded = loadPolicies();
            policies = loaded;
        }
        return loaded;
    }

    /**
     * 清空策略与判定缓存。仅面向测试与热替换场景。
     */
    public static void reset() {
        policies = null;
        DECISIONS.clear();
    }

    /**
     * 逐个策略判定类名。
     *
     * @param className 已规范化的类名
     * @return 全部策略放行时返回 {@code true}
     */
    private static boolean computeAllowed(String className) {
        for (DeserializationPolicy policy : policies()) {
            if (!policy.allows(className)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 通过 SPI 加载全部策略实现。
     *
     * <p>{@link ServiceProvider#collect()} 对同一实现可能因"文件登记名 + 注解别名"给出两个
     * 实例，故按实现类去重。</p>
     *
     * @return 不可变策略列表，SPI 不可用时回退到内置黑名单
     */
    private static List<DeserializationPolicy> loadPolicies() {
        List<DeserializationPolicy> result = new ArrayList<>();
        try {
            List<DeserializationPolicy> discovered = ServiceProvider.of(DeserializationPolicy.class).collect();
            List<Class<?>> seen = new ArrayList<>(discovered.size());
            for (DeserializationPolicy policy : discovered) {
                if (policy == null) {
                    continue;
                }
                if (seen.add(policy.getClass())) {
                    result.add(policy);
                }
            }
        } catch (Throwable e) {
            log.warn("加载反序列化策略失败，回退到内置黑名单", e);
        }
        if (result.isEmpty()) {
            result.add(new DenyListDeserializationPolicy());
        }
        if (log.isDebugEnabled()) {
            log.debug("已加载 {} 个反序列化策略", result.size());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 剥离数组维度与 {@code L...;} 包装，得到可比较的类全名。
     *
     * @param className 原始类名或 JVM 描述符
     * @return 规范化类名，入参为空时返回空串
     */
    private static String normalize(String className) {
        if (className == null) {
            return "";
        }
        String name = className.trim();
        if (name.isEmpty()) {
            return "";
        }
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";") && name.length() > 2) {
            name = name.substring(1, name.length() - 1);
        }
        return name;
    }
}
