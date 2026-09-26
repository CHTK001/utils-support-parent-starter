package com.chua.common.support.ai.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 人工确认请求
 *
 * <p>聚合一次需要人工审批（human-in-the-loop）的请求：replyId 与待确认项列表。
 * 由 {@link AgentStreamEvent}（CONFIRMATION_REQUEST）产生，经对话响应透传给前端。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationRequest implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 待确认请求回复 ID
     */
    private String replyId;

    /**
     * 待确认项列表
     */
    private List<ConfirmationItem> items;
}
