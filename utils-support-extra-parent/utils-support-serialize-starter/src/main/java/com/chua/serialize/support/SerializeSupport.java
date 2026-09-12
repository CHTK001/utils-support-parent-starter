package com.chua.serialize.support;

import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.serialize.SerializerFlow;
import com.chua.serialize.support.auto.AutoSerializer;
import com.chua.serialize.support.auto.AutoSerializerProvider;
import com.chua.serialize.support.kryo.KryoSerializer;
import com.chua.serialize.support.pool.KryoPoolManager;

import java.io.Serializable;

/**
 * 序列化支持工具类。
 * <p>
 * 提供多种序列化方式的便捷创建和管理功能，包括：
 * <ul>
 *   <li><strong>Kryo 序列化</strong> — 高性能二进制序列化</li>
 *   <li><strong>自动降级序列化</strong> — Kryo → JSON → Java 三级降级</li>
 *   <li><strong>池化管理</strong> — KryoSerializer 对象池复用</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
public class SerializeSupport {

    /**
     * 自动序列化提供者实例，用于管理序列化器的注册和获取。
     */
    private static final AutoSerializerProvider PROVIDER = new AutoSerializerProvider();

    /**
     * 私有构造函数，防止外部实例化该类。
     */
    private SerializeSupport() {
        // 禁止实例化
    }

    /**
     * 创建一个基于自动序列化的流式处理对象。
     * <p>
      * 默认使用 auto序列化器（Kryo → JSON → Java 降级策略）。
     *
     * @param clazz 要序列化的目标类型
     * @param <T>   泛型类型，必须是 Serializable 接口
     * @return 返回配置好的 序列化器流 对象
     */
    public static <T extends Serializable> SerializerFlow createFlow(Class<T> clazz) {
        AutoSerializer<T> autoSerializer = new AutoSerializer<>(clazz);
        return new SerializerFlow(PROVIDER, autoSerializer);
    }

    /**
     * 创建一个基于 Kryo 的序列化器实例。
     * <p>
     * Kryo 是高性能的二进制序列化框架，序列化速度通常比 JSON 快 2-10 倍。
     *
     * @param clazz 要序列化的目标类型
     * @param <T>   泛型类型，必须是 Serializable 接口
     * @return 返回 kryo序列化器 实例
     */
    public static <T extends Serializable> KryoSerializer<T> createKryo(Class<T> clazz) {
        return new KryoSerializer<>(clazz);
    }

    /**
     * 创建一个指定最大容量的 Kryo 对象池。
     * <p>
     * 对象池可以减少序列化器的创建开销，适合高并发场景。
     *
     * @param clazz  要序列化的目标类型
     * @param maxSize 对象池的最大容量
     * @param <T>    泛型类型，必须是 Serializable 接口
     * @return 返回 kryo游泳池管理器 实例
     */
    public static <T extends Serializable> KryoPoolManager<T> createPool(Class<T> clazz, int maxSize) {
        return KryoPoolManager.getInstance(clazz, maxSize);
    }

    /**
     * 创建一个默认配置的 Kryo 对象池。
     * <p>
     * 默认最大容量为 16。
     *
     * @param clazz 要序列化的目标类型
     * @param <T>   泛型类型，必须是 Serializable 接口
     * @return 返回 kryo游泳池管理器 实例
     */
    public static <T extends Serializable> KryoPoolManager<T> createPool(Class<T> clazz) {
        return KryoPoolManager.getInstance(clazz);
    }

    /**
     * 创建一个自动序列化器实例。
     * <p>
     * 自动序列化器支持三级降级策略：
     * <ol>
     *   <li>Kryo — 高性能二进制序列化</li>
     *   <li>JSON — Jackson 序列化，跨语言兼容</li>
     *   <li>Java — JDK 原生序列化，兜底方案</li>
     * </ol>
     *
     * @param clazz 要序列化的目标类型
     * @param <T>   泛型类型，必须是 Serializable 接口
     * @return 返回 auto序列化器 实例
     */
    public static <T extends Serializable> AutoSerializer<T> createAuto(Class<T> clazz) {
        return new AutoSerializer<>(clazz);
    }

    /**
     * 根据类类型获取对应的序列化器。
     * <p>
      * 内部使用 auto序列化器提供者 缓存序列化器实例。
     *
     * @param clazz 要获取序列化器的目标类型
     * @param <T>   泛型类型，必须是 Serializable 接口
     * @return 返回对应的 序列化器 实例
     */
    public static <T extends Serializable> Serializer<T> getSerializer(Class<T> clazz) {
        return PROVIDER.getSerializer(clazz);
    }

    /**
     * 获取当前的自动序列化提供者实例。
     *
     * @return 返回 auto序列化器提供者 实例
     */
    public static AutoSerializerProvider getProvider() {
        return PROVIDER;
    }

    /**
     * 清除所有缓存的序列化器和对象池资源。
     * <p>
     * 调用此方法后需要重新初始化相关组件。
     * 适用于测试环境或需要释放资源的场景。
     */
    public static void clearAll() {
        PROVIDER.clearCache();
        com.chua.serialize.support.pool.KryoPoolManager.clearAll();
    }
}
