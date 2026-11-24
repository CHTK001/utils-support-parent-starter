// [P3C A20 豁免] A20/A30/A31: 含风险等级词表等字符串常量场景，豁免。
package com.chua.common.support.ai.agent;

/**
 * 人工审批门（Agent 审批闭环的裁决契约）。
 *
 * <p>在 {@link AgentRiskAdjudicator} 之上做一个标记性细化：实现方表示
 * 「高风险工具调用一律转人工审批（{@link AgentRiskDecision.Verdict#CONFIRM_REQUIRED}），
 * 由业务编排层把待确认项落库为审批单、推送确认卡，并在用户决议后恢复执行」。</p>
 *
 * <p><b>职责分工</b>（与 {@link AgentRiskAdjudicator} 的其它实现区分）：</p>
 * <ul>
 *   <li>本接口实现只做<b>无状态裁决</b>：按 {@link AgentRiskRequest#riskLevel()}
 *       与工具名给出 ALLOW / DENY / CONFIRM_REQUIRED，不落库、不广播；</li>
 *   <li><b>审批单持久化、SSE 通知、决议回写、过期清扫</b>属于业务编排层
 *       （ai-starter 的确认转发点与审批服务），不放进裁决器——裁决器运行在
 *       流式 reactor 线程，拿不到完整会话上下文（userId / replyId / toolCallId）。</li>
 * </ul>
 *
 * <p>裁决口径（引擎侧兜底不变）：低/中风险放行；高/危险级转人工确认；
 * 破坏性 shell 尽力拒绝；裁决异常按 CONFIRM_REQUIRED 兜底（fail-close）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface AgentApprovalGate extends AgentRiskAdjudicator {
}
