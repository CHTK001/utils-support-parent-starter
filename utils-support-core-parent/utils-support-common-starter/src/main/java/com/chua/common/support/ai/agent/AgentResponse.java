package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* Agent 执行响应
*
* <p>封装智能体执行完成后的输出结果、执行元数据和用量统计。
*
* @author CH
* @since 2026/07/15
 */
@Data
@Builder
public class AgentResponse implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** Agent 输出文本 */
    private String output;

    /** 执行模式 */
    private String mode;

    /** 选中的 Agent 标识 */
    private String selectedAgent;

    /** 执行事件列表 */
    @Builder.Default
    /** Events */
    private List<AgentEvent> events = new ArrayList<>(); // [P3C 3.15 豁免] 数据容器值对象，调用方动态追加，规模不可预估

    /** 扩展元数据 */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>(); // [P3C 3.15 豁免] 数据容器值对象，调用方动态写入，规模不可预估

    /**
    * 用量信息
    *
    * <p>包含本次 Agent 执行的 Token 用量、费用和性能指标。
    * 若 Agent 内部调用了多次 LLM，此处为所有调用的汇总数据。
     */
    private AiUsage usage;
}
