package com.chua.common.support.ai.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 挂起状态条目。
 *
 * <p>一次运行因「等待人工确认 / 结构化提问 / 风险确认」而暂停时，实现把恢复所需的
 * 上下文（如待确认工具调用块、暂停快照）连同元数据一起存入 {@link AgentSuspendStore}，
 * 由本对象承载。{@code payload} 为实现自定义的载荷（对 store 不透明），
 * {@code attributes} 携带跨实现可读的元信息（如执行模式、触发原因）。</p>
 *
 * <p>条目带 TTL：超过 {@code ttlMillis}（从 {@code createdAt} 起算）即视为过期，
 * 读取方应按「不存在」处理——用户中途放弃或决策迟迟未送达时，条目不能无限残留。</p>
 *
 * @param key        恢复键（通常为 replyId），store 内唯一
 * @param agentId    发起挂起的 Agent 标识，可为 null
 * @param payload    实现自定义载荷（不透明），可为 null
 * @param attributes 元信息表，可为 null
 * @param createdAt  创建时间戳（毫秒）；非正值按当前时间回填
 * @param ttlMillis  存活时长（毫秒）；0 表示不过期
 * @author CH
 * @since 4.0.0.52
 */
public record AgentSuspendState(
        String key,
        String agentId,
        Object payload,
        Map<String, Object> attributes,
        long createdAt,
        long ttlMillis) {

    /**
     * 构造挂起状态条目。
     *
     * <p>attributes 做防御性拷贝（保留 null 值语义）；createdAt 非正时回填当前时间；
     * ttlMillis 负值归零（不过期）。</p>
     */
    public AgentSuspendState {
        attributes = attributes == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(attributes));
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
        if (ttlMillis < 0) {
            ttlMillis = 0;
        }
    }

    /**
     * 是否已按当前时钟过期。
     *
     * @return true 表示已过期
     */
    public boolean isExpired() {
        return isExpired(System.currentTimeMillis());
    }

    /**
     * 是否已按指定时刻过期。
     *
     * @param now 当前时间戳（毫秒）
     * @return true 表示已过期
     */
    public boolean isExpired(long now) {
        return ttlMillis > 0 && now - createdAt >= ttlMillis;
    }

    /**
     * 过期时间戳。
     *
     * @return 过期时刻（毫秒）；0 表示永不过期
     */
    public long expireAt() {
        return ttlMillis > 0 ? createdAt + ttlMillis : 0L;
    }

    /**
     * 创建一条带 TTL 的挂起状态。
     *
     * @param key       恢复键
     * @param agentId   Agent 标识，可为 null
     * @param payload   载荷，可为 null
     * @param ttlMillis 存活时长（毫秒）；0 表示不过期
     * @return 挂起状态条目
     */
    public static AgentSuspendState of(String key, String agentId, Object payload, long ttlMillis) {
        return new AgentSuspendState(key, agentId, payload, null, System.currentTimeMillis(), ttlMillis);
    }

    /**
     * 创建一条带 TTL 与元信息的挂起状态。
     *
     * @param key        恢复键
     * @param agentId    Agent 标识，可为 null
     * @param payload    载荷，可为 null
     * @param attributes 元信息表，可为 null
     * @param ttlMillis  存活时长（毫秒）；0 表示不过期
     * @return 挂起状态条目
     */
    public static AgentSuspendState of(String key, String agentId, Object payload,
                                       Map<String, Object> attributes, long ttlMillis) {
        return new AgentSuspendState(key, agentId, payload, attributes, System.currentTimeMillis(), ttlMillis);
    }
}
