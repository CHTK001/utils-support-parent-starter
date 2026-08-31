package com.chua.serialize.support.pool;

import com.chua.common.support.serialize.Serializer;
import com.chua.serialize.support.kryo.KryoSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kryo 序列化器对象池管理器。
 * <p>
 * 维护一个 KryoSerializer 对象池，通过 acquire/release 模式复用序列化器实例，
 * 避免频繁创建和销毁 Kryo 实例带来的性能开销。适合高并发序列化场景。
 * </p>
 *
 * @param <T> 可序列化的目标类型
 * @author CH
 */
@Slf4j
public class KryoPoolManager<T extends Serializable> {
    private static final long serialVersionUID = 1L;

    /**
     * 全局管理器缓存，按实体类类型缓存管理器实例
     */
    private static final Map<Class<?>, KryoPoolManager<?>> MANAGERS = new ConcurrentHashMap<>();
    /**
     * 序列化器对象池（双端队列）
     */
    private final Deque<KryoSerializer<T>> pool;
    /**
     * 目标实体类类型
     */
    private final Class<T> clazz;
    /**
     * 对象池最大容量
     */
    private final int maxSize;
    /**
     * 当前活跃序列化器数量
     */
    private final AtomicInteger activeCount = new AtomicInteger(0);
    /**
     * 累计创建的序列化器总数
     */
    private final AtomicInteger totalCreated = new AtomicInteger(0);

    /**
     * 私有构造函数，通过 getInstance() 静态方法获取实例。
     *
     * @param clazz  目标实体类类型
     * @param maxSize 对象池最大容量
     */
    private KryoPoolManager(Class<T> clazz, int maxSize) {
        this.clazz = clazz;
        this.maxSize = maxSize;
        this.pool = new ArrayDeque<>(maxSize);
    }

    /**
     * 获取指定类型和容量的 Kryo 池管理器单例。
     *
     * @param clazz  目标实体类类型
     * @param maxSize 对象池最大容量
     * @param <S>    泛型类型
     * @return KryoPoolManager 实例
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <S extends Serializable> KryoPoolManager<S> getInstance(Class<S> clazz, int maxSize) {
        return (KryoPoolManager<S>) MANAGERS.computeIfAbsent(clazz, k -> new KryoPoolManager(k, maxSize));
    }

    /**
     * 获取指定类型的默认 Kryo 池管理器（默认容量 16）。
     *
     * @param clazz 目标实体类类型
     * @param <S>   泛型类型
     * @return KryoPoolManager 实例
     */
    public static <S extends Serializable> KryoPoolManager<S> getInstance(Class<S> clazz) {
        return getInstance(clazz, 16);
    }

    /**
     * 从池中获取一个可用的 KryoSerializer。
     * <p>
     * 如果池中有空闲实例则直接复用，否则创建新实例。
     * </p>
     *
     * @return KryoSerializer 实例
     */
    public synchronized KryoSerializer<T> acquire() {
        KryoSerializer<T> serializer = pool.pollFirst();
        if (serializer != null) {
            activeCount.incrementAndGet();
            return serializer;
        }
        int created = totalCreated.incrementAndGet();
        log.info("[KryoPoolManager] Creating new serializer (total created: {})", created);
        KryoSerializer<T> newSerializer = new KryoSerializer<>(clazz);
        activeCount.incrementAndGet();
        return newSerializer;
    }

    /**
     * 将序列化器释放回池中。
     * <p>
     * 如果池未满则回收复用，否则丢弃该实例。
     * </p>
     *
     * @param serializer 待释放的序列化器
     */
    public void release(KryoSerializer<T> serializer) {
        if (serializer == null) {
            return;
        }
        activeCount.decrementAndGet();
        if (pool.size() < maxSize) {
            pool.addLast(serializer);
        } else {
            log.info("[KryoPoolManager] Pool full, discarding serializer");
        }
    }

    /**
     * 获取池中当前空闲的序列化器数量。
     *
     * @return 空闲序列化器数量
     */
    public int getPoolSize() {
        return pool.size();
    }

    /**
     * 获取当前活跃的序列化器数量。
     *
     * @return 活跃序列化器数量
     */
    public int getActiveCount() {
        return activeCount.get();
    }

    /**
     * 获取累计创建的序列化器总数。
     *
     * @return 累计创建总数
     */
    public int getTotalCreated() {
        return totalCreated.get();
    }

    /**
     * 获取对象池的最大容量。
     *
     * @return 最大容量
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * 获取管理的实体类类型。
     *
     * @return 实体类类型
     */
    public Class<T> getClazz() {
        return clazz;
    }

    /**
     * 清空池中的所有序列化器并重置计数器。
     */
    public void clear() {
        pool.clear();
        activeCount.set(0);
        totalCreated.set(0);
    }

    /**
     * 清除所有缓存的池管理器和 Kryo 实例。
     * 适用于测试环境或需要释放资源的场景。
     */
    public static void clearAll() {
        MANAGERS.clear();
        KryoSerializer.clearPool();
    }

    /**
     * 获取当前池管理器的统计信息字符串。
     *
     * @return 统计信息字符串
     */
    public String stats() {
        return String.format("KryoPoolManager{clazz=%s, pool=%d, active=%d, totalCreated=%d, max=%d}",
                clazz.getSimpleName(), pool.size(), activeCount.get(), totalCreated.get(), maxSize);
    }
}
