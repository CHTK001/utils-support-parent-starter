package com.chua.common.support.ai.agent;

import lombok.Builder;

import java.io.Serializable;
import java.util.Objects;

/**
 * Agent 执行事件。
 *
 * <p>记录 Agent 执行过程中的事件信息，用于追踪和监控。
 *
 * <p>当前唯一生产者是 {@code OpencodeAgent}：逐行解析 {@code opencode run --format json}
 * 输出的 NDJSON，把每行转成本 record，收集到 {@code AgentResponse} 的事件列表。
 * 本 record 实现 {@link Serializable}，跨进程传递依赖 {@code serialVersionUID = 1L}。</p>
 *
 * @param type      事件类型，原样取自 NDJSON 行的 {@code type} 字段，不做枚举收敛；
 *                  已知取值包括 {@code text}（文本增量）、{@code step_finish}（单步结束）、
 *                  {@code tool}（工具调用），未识别类型原样保留便于排障；
 *                  不允许为 {@code null}（紧凑构造器 {@code requireNonNull} 强校验），
 *                  解析不出时上游会先归一成空串
 * @param message   事件描述（人读摘要），由 {@code summarize(type, part)} 按类型提取：
 *                  {@code text} 取 {@code part.text}、{@code step_finish} 取 {@code reason=}、
 *                  {@code tool} 取工具名，其余类型回落为 {@link #type} 本身。
 *                  允许为 {@code null}（紧凑构造器刻意不校验），展示层需容忍空串与空值
 * @param agentId   事件关联的 Agent 标识，取自 NDJSON 行的 {@code sessionID} 字段（即会话 ID）；
 *                  允许为 {@code null}——该字段在部分事件行中缺失，解析不到时业务上就是空。
 *                  注意它是会话级标识而非单个 Agent 的定义 ID，不要与 {@code AgentDefinition} 的 id 混用
 * @param timestamp 事件时间戳，Unix 纪元毫秒数（{@code long} 基本类型，故无需空值校验）；
 *                  优先取 NDJSON 行的 {@code timestamp} 字段，缺失时回落 {@code System.currentTimeMillis()}；
 *                  可直接与 {@link System#currentTimeMillis()} 比较做耗时统计，
 *                  注意不要与「秒」混用导致耗时被放大 1000 倍
 * @author CH
 * @since 2026/07/15
 */
@Builder
public record AgentEvent(
        /**
         * 事件类型
         */
        String type,
        /**
         * 事件描述
         */
        String message,
        /**
         * 事件关联的 Agent 标识
         */
        String agentId,
        /**
         * 事件时间戳
         */
        long timestamp
) implements Serializable {

    /**
     * 规范构造器：对语义必填组件做空值校验。
     *
     * <p>value class 前置条件——规范构造器必须空值敌对。
     * {@code timestamp} 为基本类型，无需校验；
     * {@code agentId} 解析自事件流的 sessionID，缺失时业务上就是 null，故保留 null 语义。</p>
     *
     * @param type      事件类型，不允许为 null
     * @param message   事件描述，允许为 null
     * @param agentId   事件关联的 Agent 标识，允许为 null
     * @param timestamp 事件时间戳
     */
    public AgentEvent {
        type = Objects.requireNonNull(type, "type 不能为 null");
    }

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;
}
