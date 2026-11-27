package com.chua.common.support.ai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.chua.common.support.utils.CollectionUtils;

/**
 * 多 Agent 编排请求。
 *
 * <p>两种编排模式：</p>
 * <ul>
 *   <li>{@link Mode#PIPELINE} — 流水线：Agent 按顺序串行执行，
 *       前一个的输出文本作为后一个的输入；</li>
 *   <li>{@link Mode#FAN_OUT} — 扇出：所有 Agent 并行执行同一输入，
 *       结果按声明顺序收集后交给聚合器合并。</li>
 * </ul>
 *
 * @param mode       编排模式，不应为 null
 * @param agents     参与编排的 Agent 列表（按执行/收集顺序），不应为空
 * @param input      初始输入文本，可为 null（按空串处理）
 * @param aggregator 聚合器：把各 Agent 的输出按顺序合并为最终输出；
 *                   PIPELINE 模式可为 null（默认取最后一个 Agent 的输出），
 *                   FAN_OUT 模式可为 null（默认按双换行拼接）
 * @author CH
 * @since 4.0.0.52
 */
public record AgentOrchestrationRequest(
        Mode mode,
        List<Agent> agents,
        String input,
        java.util.function.Function<List<String>, String> aggregator) {

    /**
     * 编排模式
     */
    public enum Mode {
        /**
         * 流水线：串行链式执行，前序输出作为后序输入
         */
        PIPELINE,
        /**
         * 扇出：并行执行同一输入，结果按声明顺序聚合
         */
        FAN_OUT
    }

    /**
     * 构造编排请求（agents 做防御性拷贝）。
     */
    public AgentOrchestrationRequest {
        if (mode == null) {
            throw new NullPointerException("mode 不能为 null");
        }
        if (CollectionUtils.isEmpty(agents)) {
            throw new IllegalArgumentException("agents 不能为空");
        }
        agents = Collections.unmodifiableList(new ArrayList<>(agents));
    }

    /**
     * 构造流水线编排请求。
     *
     * @param agents Agent 列表（按执行顺序）
     * @param input  初始输入
     * @return 编排请求
     */
    public static AgentOrchestrationRequest pipeline(List<Agent> agents, String input) {
        return new AgentOrchestrationRequest(Mode.PIPELINE, agents, input, null);
    }

    /**
     * 构造流水线编排请求（自定义聚合器）。
     *
     * @param agents     Agent 列表（按执行顺序）
     * @param input      初始输入
     * @param aggregator 聚合器
     * @return 编排请求
     */
    public static AgentOrchestrationRequest pipeline(List<Agent> agents, String input,
                                                     java.util.function.Function<List<String>, String> aggregator) {
        return new AgentOrchestrationRequest(Mode.PIPELINE, agents, input, aggregator);
    }

    /**
     * 构造扇出编排请求（默认按双换行拼接）。
     *
     * @param agents Agent 列表（并行执行，按声明顺序收集）
     * @param input  共同输入
     * @return 编排请求
     */
    public static AgentOrchestrationRequest fanOut(List<Agent> agents, String input) {
        return new AgentOrchestrationRequest(Mode.FAN_OUT, agents, input, null);
    }

    /**
     * 构造扇出编排请求（自定义聚合器）。
     *
     * @param agents     Agent 列表（并行执行，按声明顺序收集）
     * @param input      共同输入
     * @param aggregator 聚合器
     * @return 编排请求
     */
    public static AgentOrchestrationRequest fanOut(List<Agent> agents, String input,
                                                   java.util.function.Function<List<String>, String> aggregator) {
        return new AgentOrchestrationRequest(Mode.FAN_OUT, agents, input, aggregator);
    }
}
