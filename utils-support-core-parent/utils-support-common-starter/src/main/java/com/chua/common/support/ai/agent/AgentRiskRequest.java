package com.chua.common.support.ai.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.chua.common.support.utils.StringUtils;

/**
 * 风险裁决请求。
 *
 * <p>一次「工具调用是否放行」的裁决上下文：工具名 + 入参 + 预分级结果，
 * 外加调用方附带的会话信息。风险等级由调用方预先按 {@link ToolRiskPolicy#classify}
 * 分级后传入；裁决器可信任该等级，也可按需重新分级。</p>
 *
 * @param agentId    发起调用的 Agent 标识，可为 null
 * @param sessionId  业务会话标识，可为 null
 * @param toolName   工具名称，不应为 null
 * @param toolInput  工具入参原文（命令行、路径等），可为 null
 * @param riskLevel  预分级结果（{@link ToolRiskPolicy#RISK_LOW} 等），可为 null 表示未分级
 * @param attributes 扩展信息，可为 null
 * @author CH
 * @since 4.0.0.52
 */
public record AgentRiskRequest(
        String agentId,
        String sessionId,
        String toolName,
        String toolInput,
        String riskLevel,
        Map<String, Object> attributes) {

    /**
     * 构造裁决请求（attributes 做防御性拷贝）。
     */
    public AgentRiskRequest {
        attributes = attributes == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    /**
     * 创建最小裁决请求（无会话信息、无扩展属性）。
     *
     * @param agentId   Agent 标识，可为 null
     * @param toolName  工具名称
     * @param toolInput 工具入参，可为 null
     * @return 裁决请求
     */
    public static AgentRiskRequest of(String agentId, String toolName, String toolInput) {
        return new AgentRiskRequest(agentId, null, toolName, toolInput, null, null);
    }

    /**
     * 携带预分级结果创建裁决请求。
     *
     * @param agentId   Agent 标识，可为 null
     * @param toolName  工具名称
     * @param toolInput 工具入参，可为 null
     * @param riskLevel 预分级结果
     * @return 裁决请求
     */
    public static AgentRiskRequest of(String agentId, String toolName, String toolInput, String riskLevel) {
        return new AgentRiskRequest(agentId, null, toolName, toolInput, riskLevel, null);
    }

    /**
     * 有效的风险等级：未预分级时按 {@link ToolRiskPolicy#classify} 现场分级。
     *
     * @return 风险等级（low / medium / high / dangerous）
     */
    public String effectiveRiskLevel() {
        return StringUtils.isNotEmpty(riskLevel)
                ? riskLevel
                : ToolRiskPolicy.classify(toolName, toolInput);
    }
}
