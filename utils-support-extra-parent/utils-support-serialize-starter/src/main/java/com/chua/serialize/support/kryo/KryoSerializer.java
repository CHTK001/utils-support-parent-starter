package com.chua.serialize.support.kryo;

import com.chua.common.support.serialize.DeserializationGuard;
import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.spi.annotations.Spi;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultClassResolver;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import com.esotericsoftware.kryo.util.Pool;
import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 Kryo 的高性能序列化器。
 * <p>
 * Kryo 是一个快速的 Java 对象序列化框架，性能通常比 JDK 原生序列化和 JSON 序列化高 2-10 倍。
 * </p>
 *
 * <p>两个关键约束：</p>
 * <ul>
 *   <li><strong>Kryo 实例非线程安全</strong>，因此按实体类型缓存的是
 *       {@link Pool}&lt;{@link Kryo}&gt;，每次编解码从池中借出、用完归还，
 *       而不是把同一个实例交给多个线程并发使用</li>
 *   <li><strong>关闭类注册要求即开放任意类还原</strong>，与 JDK 原生序列化同属反序列化攻击面，
 *       故使用 {@link GuardedClassResolver} 在读类名前套用 {@link DeserializationGuard}</li>
 * </ul>
 *
 * @param <T> 可序列化的目标类型
 * @author CH
 * @since 4.0.0
 */
@Spi("kryo")
@Slf4j
public class KryoSerializer<T extends Serializable> implements Serializer<T> {

    /**
     * 每个实体类型允许驻留的 Kryo 实例上限
     */
    private static final int MAX_POOL_CAPACITY = 32;

    /**
     * Kryo 实例池缓存，按实体类类型缓存对象池
     */
    private static final ConcurrentHashMap<Class<?>, Pool<Kryo>> KRYO_POOL = new ConcurrentHashMap<>();

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
     * 获取或创建指定类型的 Kryo 对象池。
     *
     * @param type 目标实体类类型
     * @return 线程安全的 Kryo 对象池
     */
    private static Pool<Kryo> getPool(Class<?> type) {
        return KRYO_POOL.computeIfAbsent(type, k -> new Pool<Kryo>(true, false, MAX_POOL_CAPACITY) {
            @Override
            protected Kryo create() {
                int id = POOL_COUNTER.incrementAndGet();
                log.info("[KryoPool] Creating Kryo instance #{} for {}", id, k.getName());
                return newKryo();
            }
        });
    }

    /**
     * 构建单个 Kryo 实例：允许循环引用、不要求注册类，并挂上反序列化类名守卫。
     *
     * @return 配置完成的 Kryo 实例
     */
    private static Kryo newKryo() {
        Kryo kryo = new Kryo(new GuardedClassResolver(), new MapReferenceResolver());
        kryo.setReferences(true);
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy());
        return kryo;
    }

    /**
     * 将对象序列化为字节数组。
     *
     * @param object 待序列化的对象，空 返回空字节数组
     * @return 序列化后的字节数组
     */
    @Override
    public byte[] serialize(T object) {
        if (object == null) {
            return new byte[0];
        }
        Pool<Kryo> pool = getPool(clazz);
        Kryo kryo = pool.obtain();
        try {
            Output output = new Output(256, -1);
            try {
                kryo.writeClassAndObject(output, object);
                output.flush();
                return output.toBytes();
            } finally {
                output.close();
            }
        } finally {
            pool.free(kryo);
        }
    }

    /**
     * 将字节数组反序列化为对象。
     *
     * @param bytes 序列化后的字节数组，空 或空数组返回 空
     * @return 反序列化后的对象
     */
    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Pool<Kryo> pool = getPool(clazz);
        Kryo kryo = pool.obtain();
        try {
            Input input = new Input(bytes);
            try {
                return (T) kryo.readClassAndObject(input);
            } finally {
                input.close();
            }
        } finally {
            pool.free(kryo);
        }
    }

    /**
     * 清除所有 Kryo 实例池缓存。
     * 适用于测试环境或需要释放资源的场景。
     */
    public static void clearPool() {
        KRYO_POOL.clear();
        log.info("[KryoPool] All Kryo pools cleared");
    }

    /**
     * 获取当前缓存的对象池数量（即已使用过的实体类型数量）。
     *
     * @return 对象池数量
     */
    public static int getPoolSize() {
        return KRYO_POOL.size();
    }

    /**
     * 带反序列化类名守卫的 Kryo 类解析器。
     *
     * <p>{@code DefaultClassResolver#readName} 在读到报文中的类名后、真正加载类之前必经
     * {@code getTypeByName}，在此处拦截即可覆盖所有按名还原的入口。</p>
     *
     * @since 4.0.0.42
     */
    private static final class GuardedClassResolver extends DefaultClassResolver {

        @Override
        protected Class<?> getTypeByName(String className) {
            DeserializationGuard.requireAllowed(className);
            return super.getTypeByName(className);
        }
    }
}
