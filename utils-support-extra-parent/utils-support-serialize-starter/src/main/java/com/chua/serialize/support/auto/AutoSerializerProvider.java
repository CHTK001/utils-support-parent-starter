package com.chua.serialize.support.auto;

import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.serialize.SerializerProvider;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* 自动序列化器提供者。
* <p>
* 基于 并发哈希映射 实现线程安全的序列化器缓存，
* 按实体类类型缓存 auto序列化器 实例，避免重复创建开销。
* </p>
*
* @author CH
* @since 4.0.0
 */
public class AutoSerializerProvider implements SerializerProvider {

    /**
    * 序列化器缓存，键 为实体类类型，值 为对应的 auto序列化器 实例
     */
    private final Map<Class<?>, AutoSerializer<?>> serializerCache = new ConcurrentHashMap<>();

    /**
    * 获取指定类型的序列化器。
    * <p>
    * 如果缓存中不存在则创建新的 auto序列化器 实例并缓存。
    * </p>
    *
    * @param type 目标实体类类型
    * @param <T>  泛型类型
    * @return 对应的 序列化器 实例
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends Serializable> Serializer<T> getSerializer(Class<T> type) {
        return (Serializer<T>) serializerCache.computeIfAbsent(type, t -> new AutoSerializer(t));
    }

    /**
    * 获取指定类型的 auto序列化器 实例。
    *
    * @param type 目标实体类类型
    * @param <T>  泛型类型
    * @return 对应的 auto序列化器 实例
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends Serializable> AutoSerializer<T> getAutoSerializer(Class<T> type) {
        return (AutoSerializer<T>) serializerCache.computeIfAbsent(type, t -> new AutoSerializer(t));
    }

    /**
    * 清除所有缓存的序列化器实例。
    * 适用于测试环境或需要释放资源的场景。
     */
    public void clearCache() {
        serializerCache.clear();
    }

    /**
    * 获取当前缓存的序列化器数量。
    *
    * @return 缓存中的序列化器数量
     */
    public int getCacheSize() {
        return serializerCache.size();
    }
}
