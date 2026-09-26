package com.chua.serialize.support.auto;

import com.chua.common.support.serialize.JavaSerializer;
import com.chua.common.support.serialize.JsonSerializer;
import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.serialize.support.kryo.KryoSerializer;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * 自动降级序列化器。
 * <p>
 * 按固定优先级依次尝试：
 * <ol>
 *   <li><strong>Kryo</strong> — 高性能二进制序列化（首选）</li>
 *   <li><strong>JSON (Jackson)</strong> — 跨语言兼容（次选）</li>
 *   <li><strong>Java 原生</strong> — JDK ObjectStream（兜底）</li>
 * </ol>
 * 首选实现抛异常时降级到下一方案，全部失败才抛出异常。
 * </p>
 *
 * <p>产出字节流带 3 字节头：{@code 'A' 'S' <编解码编号>}。反序列化只按编号分派到写出该数据的
 * 那个实现，<strong>不再逐个尝试</strong>——Kryo / JSON / JDK 的字节流之间没有互斥保证，
 * 猜错实现可能不抛异常却还原出错误对象，静默污染数据。</p>
 *
 * <p>跨进程读写两端必须以同样的顺序构造降级链（自定义序列化器的编号按加入先后递增），
 * 否则编号对不上会直接抛异常。</p>
 *
 * @param <T> 可序列化的目标类型
 * @author CH
 * @since 4.0.0
 */
@Spi("auto")
@Slf4j
public class AutoSerializer<T extends Serializable> implements Serializer<T> {

    /**
     * 魔数字节 0
     */
    private static final byte MAGIC_0 = (byte) 'A';

    /**
     * 魔数字节 1
     */
    private static final byte MAGIC_1 = (byte) 'S';

    /**
     * 字节头长度
     */
    private static final int HEADER_LENGTH = 3;

    /**
     * Kryo 编解码编号
     */
    public static final byte CODE_KRYO = 1;

    /**
     * JSON 编解码编号
     */
    public static final byte CODE_JSON = 2;

    /**
     * JDK 原生编解码编号
     */
    public static final byte CODE_JAVA = 3;

    /**
     * 自定义序列化器的起始编号
     */
    private static final byte CODE_USER_FIRST = 4;

    /**
     * 序列化器降级链（按优先级排列）
     */
    private final List<Serializer<T>> serializers;

    /**
     * 编解码编号到序列化器的索引
     */
    private final ConcurrentMap<Byte, Serializer<T>> byCode;

    /**
     * 下一个可分配的自定义编解码编号
     */
    private final AtomicInteger nextCode = new AtomicInteger(CODE_USER_FIRST);

    /**
     * 目标实体类类型
     */
    private final Class<T> clazz;

    /**
     * 创建自动降级序列化器。
     * <p>
     * 默认降级链：Kryo → 自定义兜底 → JSON → Java。
     * 可通过 降级序列化器 参数插入额外的降级序列化器。
     * </p>
     *
     * @param clazz               目标实体类类型
     * @param fallbackSerializers 额外的降级序列化器（可选）
     */
    public AutoSerializer(Class<T> clazz, Serializer<T>... fallbackSerializers) {
        this.clazz = clazz;
        this.serializers = new CopyOnWriteArrayList<>();
        this.byCode = new ConcurrentHashMap<>();

        addWithCode(new KryoSerializer<>(clazz), CODE_KRYO);
        if (fallbackSerializers != null) {
            for (Serializer<T> fallback : fallbackSerializers) {
                if (fallback != null) {
                    addWithCode(fallback, (byte) nextCode.getAndIncrement());
                }
            }
        }
        addWithCode(new JsonSerializer<>(clazz), CODE_JSON);
        addWithCode(new JavaSerializer<>(), CODE_JAVA);
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
     * 按降级链的固定顺序尝试，第一个成功的实现负责写出，并在结果前拼接编解码编号。
     * 所有序列化器均失败时抛出 runtime异常。
     * </p>
     *
     * @param object 待序列化的对象
     * @return 带 3 字节头的序列化结果，入参为 空 时返回空数组
     */
    @Override
    public byte[] serialize(T object) {
        if (object == null) {
            return new byte[0];
        }
        List<Serializer<T>> chain = new ArrayList<>(serializers);
        for (int i = 0; i < chain.size(); i++) {
            Serializer<T> serializer = chain.get(i);
            try {
                byte[] body = serializer.serialize(object);
                if (body == null) {
                    continue;
                }
                return withHeader(serializer, body);
            } catch (Exception e) {
                log.warn("[AutoSerializer] Serializer #{} ({}) failed: {}", i,
                        serializer.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
        throw new RuntimeException("All serializers failed");
    }

    /**
     * 将字节数组反序列化为对象。
     * <p>
     * 只使用字节头中标注的编解码实现，不做猜测式降级。
     * </p>
     *
     * @param bytes 由 {@link #serialize(Serializable)} 产出的字节数组
     * @return 反序列化后的对象，入参为 空 或空数组时返回 空
     * @throws IllegalArgumentException 字节头缺失、被篡改或编号未登记
     */
    @Override
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length < HEADER_LENGTH || bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) {
            throw new IllegalArgumentException("数据缺少 AutoSerializer 字节头，无法确定应由哪个序列化实现解码");
        }
        byte code = bytes[2];
        Serializer<T> serializer = byCode.get(code);
        if (serializer == null) {
            throw new IllegalArgumentException("未知的编解码编号 " + code + "，当前可用: " + byCode.keySet());
        }
        byte[] body = Arrays.copyOfRange(bytes, HEADER_LENGTH, bytes.length);
        return serializer.deserialize(body);
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
     * 向降级链末尾添加一个自定义序列化器。
     *
     * @param serializer 自定义序列化器
     * @return 其被分配的编解码编号；已存在同类型实现时返回 空
     */
    public Byte addSerializer(Serializer<T> serializer) {
        if (serializer == null) {
            return null;
        }
        byte code = (byte) nextCode.getAndIncrement();
        return addWithCode(serializer, code) ? code : null;
    }

    /**
     * 从降级链中移除一个自定义序列化器。
     *
     * @param serializer 要移除的序列化器
     * @return 是否移除成功
     */
    public boolean removeSerializer(Serializer<T> serializer) {
        if (serializer == null) {
            return false;
        }
        boolean removed = serializers.removeIf(s -> s == serializer);
        byCode.values().removeIf(s -> s == serializer);
        return removed;
    }

    /**
     * 登记一个序列化器，重复类型会被忽略。
     *
     * @param serializer 序列化器
     * @param code       编解码编号
     * @return 是否登记成功
     */
    private boolean addWithCode(Serializer<T> serializer, byte code) {
        for (Serializer<T> existing : serializers) {
            if (existing.getClass() == serializer.getClass()) {
                return false;
            }
        }
        serializers.add(serializer);
        byCode.put(code, serializer);
        return true;
    }

    /**
     * 给序列化结果拼上 {@code 'A' 'S' <编号>} 字节头。
     *
     * @param serializer 实际产出字节的序列化器
     * @param body       编解码正文
     * @return 带头的完整字节数组
     */
    private byte[] withHeader(Serializer<T> serializer, byte[] body) {
        byte code = codeOf(serializer);
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LENGTH + body.length);
        out.write(MAGIC_0);
        out.write(MAGIC_1);
        out.write(code);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /**
     * 查询序列化器登记的编解码编号。
     *
     * @param serializer 序列化器
     * @return 编解码编号
     * @throws IllegalStateException 该实例未登记编号
     */
    private byte codeOf(Serializer<T> serializer) {
        for (Map.Entry<Byte, Serializer<T>> entry : byCode.entrySet()) {
            if (entry.getValue() == serializer) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("序列化器未登记编解码编号: " + serializer.getClass().getName());
    }
}
