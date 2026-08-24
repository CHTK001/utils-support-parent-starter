package com.chua.serialize.support.auto;

import com.chua.common.support.serialize.JavaSerializer;
import com.chua.common.support.serialize.JsonSerializer;
import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.serialize.support.kryo.KryoSerializer;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自动降级序列化器。
 * <p>
 * 支持多级序列化策略，按优先级依次尝试：
 * <ol>
 *   <li><strong>Kryo</strong> — 高性能二进制序列化（首选）</li>
 *   <li><strong>JSON (Jackson)</strong> — 跨语言兼容（次选）</li>
 *   <li><strong>Java 原生</strong> — JDK ObjectStream（兜底）</li>
 * </ol>
 * <p>
 * 当首选序列化失败时自动降级到下一方案，确保序列化操作的可靠性。
 * 同时支持自定义额外的降级序列化器。
 * </p>
 *
 * @param <T> 可序列化的目标类型
 * @author CH
 */
@Spi("auto")
public class AutoSerializer<T extends Serializable> implements Serializer<T> {
    private static final long serialVersionUID = 1L;

    /**
     * 序列化器降级链
     */
    private final List<Serializer<T>> serializers;
    /**
     * 当前使用的序列化器索引（用于轮询均衡负载）
     */
    private final AtomicReference<Integer> currentIndex = new AtomicReference<>(0);
    /**
     * 目标实体类类型
     */
    private final Class<T> clazz;

    /**
     * 创建自动降级序列化器。
     * <p>
     * 默认降级链：Kryo → JSON → Java。
     * 可通过 fallbackSerializers 参数添加额外的降级序列化器。
     * </p>
     *
     * @param clazz              目标实体类类型
     * @param fallbackSerializers 额外的降级序列化器（可选）
     */
    public AutoSerializer(Class<T> clazz, Serializer<T>... fallbackSerializers) {
        this.clazz = clazz;
        this.serializers = new CopyOnWriteArrayList<>();
        
        KryoSerializer<T> kryoSerializer = new KryoSerializer<>(clazz);
        this.serializers.add(kryoSerializer);
        
        if (fallbackSerializers != null) {
            this.serializers.addAll(Arrays.asList(fallbackSerializers));
        }
        
        JsonSerializer<T> jsonSerializer = new JsonSerializer<>(clazz);
        if (!this.serializers.contains(jsonSerializer)) {
            this.serializers.add(jsonSerializer);
        }
        
        JavaSerializer<T> javaSerializer = new JavaSerializer<>();
        if (!this.serializers.contains(javaSerializer)) {
            this.serializers.add(javaSerializer);
        }
    }

    /**
     * 创建默认降级链的自动序列化器。
     *
     * @param clazz 目标实体类类型
     */
    public AutoSerializer(Class<T> clazz) {
        this(clazz, null);
    }

    /**
     * 序列化对象为字节数组。
     * <p>
     * 按降级链依次尝试，第一个成功的序列化器返回结果。
     * 所有序列化器均失败时抛出 RuntimeException。
     * </p>
     *
     * @param object 待序列化的对象
     * @return 序列化后的字节数组
     */
    @Override
    public byte[] serialize(T object) {
        int start = currentIndex.get();
        for (int i = 0; i < serializers.size(); i++) {
            int idx = (start + i) % serializers.size();
            try {
                byte[] result = serializers.get(idx).serialize(object);
                if (idx != start) {
                    currentIndex.set(idx);
                }
                return result;
            } catch (Exception e) {
                System.err.println("[AutoSerializer] Serializer #" + idx + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("All serializers failed");
    }

    /**
     * 将字节数组反序列化为对象。
     * <p>
     * 按降级链依次尝试，第一个成功的反序列化器返回结果。
     * 所有反序列化器均失败时抛出 RuntimeException。
     * </p>
     *
     * @param bytes 序列化后的字节数组
     * @return 反序列化后的对象
     */
    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] bytes) {
        int start = currentIndex.get();
        for (int i = 0; i < serializers.size(); i++) {
            int idx = (start + i) % serializers.size();
            try {
                T result = serializers.get(idx).deserialize(bytes);
                if (idx != start) {
                    currentIndex.set(idx);
                }
                return result;
            } catch (Exception e) {
                System.err.println("[AutoSerializer] Deserialize with serializer #" + idx + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("All deserializers failed");
    }

    /**
     * 获取当前降级链中的序列化器数量。
     *
     * @return 序列化器数量
     */
    public int getSerializerCount() {
        return serializers.size();
    }

    /**
     * 获取所有降级序列化器的名称列表。
     *
     * @return 序列化器名称列表
     */
    public List<String> getSerializerNames() {
        return serializers.stream()
                .map(s -> s.getClass().getSimpleName())
                .toList();
    }

    /**
     * 向降级链中添加一个自定义序列化器。
     *
     * @param serializer 自定义序列化器
     * @return 是否添加成功
     */
    public boolean addSerializer(Serializer<T> serializer) {
        return serializers.add(serializer);
    }

    /**
     * 从降级链中移除一个自定义序列化器。
     *
     * @param serializer 要移除的序列化器
     * @return 是否移除成功
     */
    public boolean removeSerializer(Serializer<T> serializer) {
        return serializers.remove(serializer);
    }
}
