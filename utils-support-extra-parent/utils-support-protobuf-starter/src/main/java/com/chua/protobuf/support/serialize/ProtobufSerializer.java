package com.chua.protobuf.support.serialize;

import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.spi.annotations.Spi;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;

/**
* 基于 protostuff 的高性能 Protobuf 序列化器。
*
* <p>protostuff 是活跃维护的 Java Protobuf 编解码库，通过注解驱动的方式定义消息结构，
* 无需 .Proto.io 文件和 protoc 编译器，即可实现 Protobuf 格式的高效序列化与反序列化。</p>
*
* <p>相比已停止维护的 jprotobuf，protostuff 的优势：
* <ul>
*   <li><strong>活跃维护</strong>：持续更新，支持 Java 17+，社区活跃</li>
*   <li><strong>注解驱动</strong>：目标类使用 {@code @Tag(N)} 注解标注字段，无需 .proto 文件</li>
*   <li><strong>零代码生成</strong>：运行时动态生成 Schema，无需 protoc 编译步骤</li>
*   <li><strong>类型缓存</strong>：每个实体类类型对应一个 Schema 实例，线程安全，避免重复构建开销</li>
*   <li><strong>高压缩比</strong>：Protobuf 二进制格式比 JSON 更紧凑，网络传输更高效</li>
*   <li><strong>兼容性</strong>：输出标准 protobuf 二进制格式</li>
* </ul>
*
* <p>使用示例：
* <pre>{@code
* // 定义消息类
* public class User implements Serializable {
     private static final long serialVersionUID = 1L;
 *     @Tag(1)
 *     private String name;
 *     @Tag(2)
 *     private int age;
 * }
 *
 * // 序列化/反序列化
 * Serializer<User> serializer = new ProtobufSerializer<>(User.class);
 * byte[] bytes = serializer.serialize(user);
 * User result = serializer.deserialize(bytes);
 * }</pre>化器.deserialize(bytes);
 * }</pre>
 *
 * @param <T> 可序列化的目标类型（需使用 protostuff @标签 注解标注字段）
 * @author CH
 * @since 4.0.0.42
 */
@Spi("protobuf")
public class ProtobufSerializer<T extends Serializable> implements Serializer<T> {

    /**
    * 模式 实例缓存池，按实体类类型缓存 模式 实例
     */
    private static final ConcurrentHashMap<Class<?>, Schema<?>> SCHEMA_POOL = new ConcurrentHashMap<>();

    /**
    * 链接缓冲 线程局部变量，避免每次序列化都分配新缓冲区
     */
    private static final ThreadLocal<LinkedBuffer> BUFFER_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));

    /**
    * 目标实体类类型
     */
    private final Class<T> clazz;

    /**
    * 创建指定类型的 Protobuf 序列化器。
    *
    * @param clazz 要序列化的目标类型（需使用 @标签 注解标注字段）
     */
    public ProtobufSerializer(Class<T> clazz) {
        this.clazz = clazz;
    }

    /**
    * 获取或创建线程安全的 模式 实例。
    *
    * <p>每个实体类类型对应一个唯一的 Schema 实例，通过 ConcurrentHashMap 缓存。
    * 模式 由 protostuff runtime模式 运行时动态生成，线程安全可复用。</p>
    *
    * @return Schema 实例
     */
    @SuppressWarnings("unchecked")
    private Schema<T> getSchema() {
        return (Schema<T>) SCHEMA_POOL.computeIfAbsent(clazz, RuntimeSchema::getSchema);
    }

    /**
    * 将对象序列化为 Protobuf 二进制字节数组。
    *
    * @param object 待序列化的对象，空 返回空字节数组
    * @return 序列化后的字节数组
     */
    @Override
    public byte[] serialize(T object) {
        if (object == null) {
            return new byte[0];
        }
        LinkedBuffer buffer = BUFFER_THREAD_LOCAL.get();
        try {
            Schema<T> schema = getSchema();
            return ProtostuffIOUtil.toByteArray(object, schema, buffer);
        } catch (Exception e) {
            throw new RuntimeException("Protobuf serialize failed for " + clazz.getName(), e);
        } finally {
            buffer.clear();
        }
    }

    /**
    * 将 Protobuf 二进制字节数组反序列化为对象。
    *
    * @param bytes 序列化后的字节数组，空 或空数组返回 空
    * @return 反序列化后的对象
     */
    @Override
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            Schema<T> schema = getSchema();
            T result = schema.newMessage();
            ProtostuffIOUtil.mergeFrom(bytes, result, schema);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Protobuf deserialize failed for " + clazz.getName(), e);
        }
    }

    /**
    * 清除所有 模式 实例缓存。
    * 适用于测试环境或需要释放资源的场景。
     */
    public static void clearPool() {
        SCHEMA_POOL.clear();
    }

    /**
    * 获取当前缓存的 模式 实例数量。
    *
    * @return Schema 实例数量
     */
    public static int getPoolSize() {
        return SCHEMA_POOL.size();
    }
}
