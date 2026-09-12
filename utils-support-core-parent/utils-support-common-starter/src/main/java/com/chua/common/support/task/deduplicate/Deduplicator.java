package com.chua.common.support.task.deduplicate;

import java.util.function.Supplier;

/**
* 去重器接口，提供幂等判断和标记能力。
* <p>
* 支持基于 键 的判重、标记已处理，以及组合的 deduplicate 模板方法。
* SPI 扩展可实现内存、Redis 等不同存储后端。
* </p>
*
* @author CH
* @since 4.0.0.41
 */
public interface Deduplicator {

    /**
    * 判断 键 是否已处理过。
    *
    * @param key 去重 键
    * @return true 表示已处理（重复）
     */
    boolean isDuplicate(String key);

    /**
    * 标记 键 为已处理。
    *
    * @param key 去重 键
     */
    void markProcessed(String key);

    /**
    * 幂等执行：仅当 键 未处理时执行 供应商 并标记。
    *
    * @param key      去重 键
    * @param supplier 待执行逻辑
    * @param <T>      返回值类型
    * @return 执行结果，若重复则返回 空
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
    * 按 客户端id + topic + 追踪id + 偏移量 组装 键 并判重。
    *
    * @param clientId 客户端 标识
    * @param topic    主题
    * @param traceId  追踪 标识
    * @param offset   偏移量
    * @return true 表示已处理
     */
    default boolean isDuplicate(String clientId, String topic, String traceId, long offset) {
        return isDuplicate(buildKey(clientId, topic, traceId, offset));
    }

    /**
    * 按 客户端id + topic + 追踪id + 偏移量 组装 键 并标记。
    *
    * @param clientId 客户端 标识
    * @param topic    主题
    * @param traceId  追踪 标识
    * @param offset   偏移量
     */
    default void markProcessed(String clientId, String topic, String traceId, long offset) {
        markProcessed(buildKey(clientId, topic, traceId, offset));
    }

    /**
    * 组装去重 键，格式为 "客户端id:topic:追踪id:偏移量"。
    *
    * @param clientId 客户端 标识
    * @param topic    主题
    * @param traceId  追踪 标识
    * @param offset   偏移量
    * @return 组装后的 键
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
