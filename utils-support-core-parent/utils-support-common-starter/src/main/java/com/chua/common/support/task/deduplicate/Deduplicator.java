package com.chua.common.support.task.deduplicate;

import java.util.function.Supplier;
import org.jspecify.annotations.NullUnmarked;

/**
 * 去重器接口，提供幂等判断和标记能力。
 * <p>
 * 支持基于 key 的判重、标记已处理，以及组合的 deduplicate 模板方法。
 * SPI 扩展可实现内存、Redis 等不同存储后端。
 * </p>
 *
 * @author CH
 * @since 4.0.0.41
 */
@NullUnmarked
public interface Deduplicator {

    /**
     * 判断 key 是否已处理过。
     *
     * @param key 去重 key
     * @return true 表示已处理（重复）
     */
    boolean isDuplicate(String key);

    /**
     * 标记 key 为已处理。
     *
     * @param key 去重 key
     */
    void markProcessed(String key);

    /**
     * 幂等执行：仅当 key 未处理时执行 supplier 并标记。
     *
     * @param key      去重 key
     * @param supplier 待执行逻辑
     * @param <T>      返回值类型
     * @return 执行结果，若重复则返回 null
     */
    default <T> T deduplicate(String key, Supplier<T> supplier) {
        if (isDuplicate(key)) {
            return null;
        }
        T result = supplier.get();
        markProcessed(key);
        return result;
    }

    /**
     * 按 clientId + topic + traceId + offset 组装 key 并判重。
     *
     * @param clientId 客户端 ID
     * @param topic    主题
     * @param traceId  追踪 ID
     * @param offset   偏移量
     * @return true 表示已处理
     */
    default boolean isDuplicate(String clientId, String topic, String traceId, long offset) {
        return isDuplicate(buildKey(clientId, topic, traceId, offset));
    }

    /**
     * 按 clientId + topic + traceId + offset 组装 key 并标记。
     *
     * @param clientId 客户端 ID
     * @param topic    主题
     * @param traceId  追踪 ID
     * @param offset   偏移量
     */
    default void markProcessed(String clientId, String topic, String traceId, long offset) {
        markProcessed(buildKey(clientId, topic, traceId, offset));
    }

    /**
     * 组装去重 key，格式为 "clientId:topic:traceId:offset"。
     *
     * @param clientId 客户端 ID
     * @param topic    主题
     * @param traceId  追踪 ID
     * @param offset   偏移量
     * @return 组装后的 key
     */
    static String buildKey(String clientId, String topic, String traceId, long offset) {
        StringBuilder sb = new StringBuilder();
        if (clientId != null) { sb.append(clientId).append(':'); }
        if (topic != null) { sb.append(topic).append(':'); }
        sb.append(traceId != null ? traceId : "").append(':').append(offset);
        return sb.toString();
    }

    /**
     * 清空所有处理记录。
     */
    void clear();

    /**
     * 获取当前记录数。
     *
     * @return 记录数
     */
    int size();
}
