package com.chua.common.support.ai.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 待用户确认项
 *
 * <p>当 Agent 运行到需要人工决策的工具（如规划模式的 {@code plan_exit}）时，
 * 由 {@link AgentStreamEvent}（类型 {@code CONFIRMATION_REQUEST}）携带，
 * 供前端渲染「批准 / 拒绝」卡片。
 *
 * <ul>
 *   <li>{@link #toolCallId} — 触发确认的工具调用 ID（回传决策时用于匹配）</li>
 *   <li>{@link #toolName} — 工具名称（如 plan_exit）</li>
 *   <li>{@link #title} — 确认卡片标题</li>
 *   <li>{@link #description} — 详细说明（如计划摘要）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationItem implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 工具调用 ID
     */
    private String toolCallId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 卡片标题
     */
    private String title;

    /**
     * 详细说明
     */
    private String description;
}
