package com.chua.common.support.ai.agent;

import reactor.core.publisher.Flux;

/**
 * 多 Agent 编排器契约。
 *
 * <p>把多个 {@link Agent} 按 {@link AgentOrchestrationRequest.Mode} 组合执行：</p>
 * <ul>
 *   <li>PIPELINE — 串行链：前一个 Agent 的输出作为后一个的输入；</li>
 *   <li>FAN_OUT — 并行扇出：所有 Agent 执行同一输入，结果按声明顺序聚合。</li>
 * </ul>
 *
 * <p>同步入口返回聚合后的 {@link AgentResponse}；流式入口转发各 Agent 的
 * 增量/工具事件，吞掉每个 Agent 各自的 COMPLETE，最后只发一个聚合 COMPLETE。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface AgentOrchestrator {

    /**
     * 同步编排执行。
     *
     * @param request 编排请求，不应为 null
     * @return 聚合响应（per-Agent 输出与用量在 metadata 中保留）
     */
    AgentResponse orchestrate(AgentOrchestrationRequest request);

    /**
     * 流式编排执行。
     *
     * <p>转发各 Agent 的 REASONING/THINKING/TOOL 等事件；每个 Agent 自己的
     * COMPLETE 被吞掉，最后只发出一个聚合 COMPLETE（usage 不做跨 Agent 聚合，
     * 需要精确用量时请用 {@link #orchestrate} 并读取 metadata）。</p>
     *
     * @param request 编排请求，不应为 null
     * @return 事件流
     */
    Flux<AgentStreamEvent> orchestrateStream(AgentOrchestrationRequest request);
}
