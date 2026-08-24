package com.chua.fory.support.serialize;

import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.fury.Fury;
import org.apache.fury.config.Language;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Apache Fory (Fury) 的高性能序列化器。
 *
 * <p>Apache Fory 是 Apache 顶级项目的高性能多语言序列化框架（前身 Apache Fury），
 * 采用编译期代码生成替代运行时反射，支持对象引用跟踪（循环引用）、跨语言互操作与模式演化。
 *
 * <p>实现要点：
 * <ul>
 *   <li><strong>零反射</strong>：Fury 通过 JIT 生成序列化代码，性能远高于 JDK 原生与 JSON</li>
 *   <li><strong>引用跟踪</strong>：{@link #REF_TRACKING} 开启后支持共享引用与循环引用对象图</li>
 *   <li><strong>类型缓存</strong>：每个实体类类型对应一个 Fury 实例，线程安全，避免重复构建开销</li>
 * </ul>
 *
 * @param <T> 可序列化的目标类型
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"fory", "fury"})
public class ForySerializer<T extends Serializable> implements Serializer<T> {

    /**
     * 是否启用引用跟踪（支持循环引用 / 共享引用对象图）
     */
    private static final boolean REF_TRACKING = true;

    /**
     * Fury 实例缓存池，按实体类类型缓存 Fury 实例
     */
    private static final ConcurrentHashMap<Class<?>, Fury> FURY_POOL = new ConcurrentHashMap<>();

    /**
     * 全局 Fury 实例计数器
     */
    private static final AtomicInteger POOL_COUNTER = new AtomicInteger(0);

    /**
     * 目标实体类类型
     */
    private final Class<T> clazz;

    /**
     * 创建指定类型的 Fory 序列化器。
     *
     * @param clazz 要序列化的目标类型
     */
    public ForySerializer(Class<T> clazz) {
        this.clazz = clazz;
    }

    /**
     * 获取或创建线程安全的 Fury 实例。
     *
     * <p>每个实体类类型对应一个唯一的 Fury 实例，通过 ConcurrentHashMap 缓存。
     * Fury 配置：Java 原生语言模式、允许循环引用、不强制要求注册类（动态序列化更灵活）。
     * </p>
     *
     * @return Fury 实例
     */
    private Fury getFury() {
        return FURY_POOL.computeIfAbsent(clazz, k -> {
            Fury fury = Fury.builder()
                    .withLanguage(Language.JAVA)
                    .withRefTracking(REF_TRACKING)
                    .requireClassRegistration(false)
                    .build();
            int id = POOL_COUNTER.incrementAndGet();
            System.out.println("[ForyPool] Created Fury instance #" + id + " for " + k.getName());
            return fury;
        });
    }

    /**
     * 将对象序列化为字节数组。
     *
     * @param object 待序列化的对象，null 返回空字节数组
     * @return 序列化后的字节数组
     */
    @Override
    public byte[] serialize(T object) {
        if (object == null) {
            return new byte[0];
        }
        return getFury().serialize(object);
    }

    /**
     * 将字节数组反序列化为对象。
     *
     * @param bytes 序列化后的字节数组，null 或空数组返回 null
     * @return 反序列化后的对象
     */
    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return (T) getFury().deserialize(bytes);
    }

    /**
     * 清除所有 Fury 实例缓存。
     * 适用于测试环境或需要释放资源的场景。
     */
    public static void clearPool() {
        FURY_POOL.clear();
        System.out.println("[ForyPool] All Fury instances cleared");
    }

    /**
     * 获取当前缓存的 Fury 实例数量。
     *
     * @return Fury 实例数量
     */
    public static int getPoolSize() {
        return FURY_POOL.size();
    }
}
