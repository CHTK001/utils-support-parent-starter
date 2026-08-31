package com.chua.serialize.support.kryo;

import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.spi.annotations.Spi;
import com.esotericsoftware.kryo.Kryo;
import lombok.extern.slf4j.Slf4j;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Kryo 的高性能序列化器。
 * <p>
 * Kryo 是一个快速的 Java 对象序列化框架，性能通常比 JDK 原生序列化和 JSON 序列化高 2-10 倍。
 * 内部使用线程安全的 Kryo 实例池（按类型缓存），避免重复创建开销。
 * </p>
 *
 * @param <T> 可序列化的目标类型
 * @author CH
 */
@Spi("kryo")
@Slf4j
public class KryoSerializer<T extends Serializable> implements Serializer<T> {
    private static final long serialVersionUID = 1L;

    /**
     * Kryo 实例缓存池，按实体类类型缓存 Kryo 实例
     */
    private static final ConcurrentHashMap<Class<?>, Kryo> KRYO_POOL = new ConcurrentHashMap<>();
    /**
     * 全局 Kryo 实例计数器
     */
    private static final AtomicInteger POOL_COUNTER = new AtomicInteger(0);

    /**
     * 目标实体类类型
     */
    private final Class<T> clazz;

    /**
     * 创建指定类型的 Kryo 序列化器。
     *
     * @param clazz 要序列化的目标类型
     */
    public KryoSerializer(Class<T> clazz) {
        this.clazz = clazz;
    }

    /**
     * 获取或创建线程安全的 Kryo 实例。
     * <p>
     * 每个实体类类型对应一个唯一的 Kryo 实例，通过 ConcurrentHashMap 缓存。
     * Kryo 配置：允许循环引用、不要求注册类、使用默认 InstantiatorStrategy。
     * </p>
     *
     * @return Kryo 实例
     */
    private Kryo getKryo() {
        return KRYO_POOL.computeIfAbsent(clazz, k -> {
            Kryo kryo = new Kryo();
            kryo.setReferences(true);
            kryo.setRegistrationRequired(false);
            kryo.setInstantiatorStrategy(new com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy());
            int id = POOL_COUNTER.incrementAndGet();
            log.info("[KryoPool] Created Kryo instance #{} for {}", id, k.getName());
            return kryo;
        });
    }

    /**
     * 将对象序列化为字节数组。
     *
     * @param object 待序列化的对象，null 返回空字节数组
     * @return 序列化后的字节数组
     */
    @Override
    @SuppressWarnings("unchecked")
    public byte[] serialize(T object) {
        if (object == null) {
            return new byte[0];
        }
        Kryo kryo = getKryo();
        Output output = null;
        try {
            output = new Output(256, -1);
            kryo.writeClassAndObject(output, object);
            output.flush();
            return output.toBytes();
        } finally {
            if (output != null) {
                try { output.close(); } catch (Exception ignored) {}
            }
        }
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
        Kryo kryo = getKryo();
        Input input = null;
        try {
            input = new Input(bytes);
            Object obj = kryo.readClassAndObject(input);
            return (T) obj;
        } finally {
            if (input != null) {
                try { input.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 清除所有 Kryo 实例缓存。
     * 适用于测试环境或需要释放资源的场景。
     */
    public static void clearPool() {
        KRYO_POOL.clear();
        log.info("[KryoPool] All Kryo instances cleared");
    }

    /**
     * 获取当前缓存的 Kryo 实例数量。
     *
     * @return Kryo 实例数量
     */
    public static int getPoolSize() {
        return KRYO_POOL.size();
    }
}
