package com.chua.common.support.ai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行计划。
 *
 * <p>计划确认（plan confirmation）链路中的计划载体：Agent 在正式执行前产出
 * 步骤清单，交由 {@link AgentPlanConfirmation} 审批。结构化步骤缺失时
 * 可只携带原始文本（{@code rawText}）。</p>
 *
 * @param planId    计划标识，可为 null
 * @param summary   计划摘要，可为 null
 * @param steps     有序步骤列表，可为空列表
 * @param rawText   原始计划文本，可为 null
 * @param metadata  扩展信息，可为 null
 * @author CH
 * @since 4.0.0.52
 */
public record AgentPlan(
        String planId,
        String summary,
        List<String> steps,
        String rawText,
        Map<String, Object> metadata) {

    /**
     * 构造计划（steps/metadata 做防御性拷贝）。
     */
    public AgentPlan {
        steps = steps == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(steps));
        metadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(metadata);
    }

    /**
     * 构造最小计划。
     *
     * @param summary 计划摘要
     * @param steps   有序步骤
     * @return 计划
     */
    public static AgentPlan of(String summary, List<String> steps) {
        return new AgentPlan(null, summary, steps, null, null);
    }
}
