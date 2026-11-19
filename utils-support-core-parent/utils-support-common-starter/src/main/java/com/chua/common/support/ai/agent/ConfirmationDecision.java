package com.chua.common.support.ai.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户确认决策
 *
 * <p>用户对 {@link ConfirmationItem} 的批准 / 拒绝结果，由前端在用户点击
 * 「批准 / 拒绝」后回传，用于恢复因等待人工确认而暂停的 Agent。
 *
 * <ul>
 *   <li>{@link #toolCallId} — 对应的待确认工具调用 ID</li>
 *   <li>{@link #confirmed} — true 批准（继续执行）；false 拒绝（回到规划修订）</li>
 *   <li>{@link #mode} — 批准方式：{@code once} 单次放行 / {@code always} 记住该工具；
 *       拒绝时忽略。缺省按 once 处理</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationDecision implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 工具调用 ID
     */
    private String toolCallId;

    /**
     * 是否批准
     */
    private boolean confirmed;

    /**
     * 批准方式：{@code once} 单次放行（默认） / {@code always} 记住该工具（后续同类不再询问）。
     *
     * <p>仅 {@code confirmed=true} 时有意义；网关把它映射为 opencode 的
     * {@code once}/{@code always} 应答。拒绝时忽略本字段。</p>
     */
    private String mode;
}
