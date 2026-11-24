// [P3C A20 豁免] A20/A30/A31: 含日志/界面字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.AgentBudgetTracker;
import com.chua.common.support.ai.agent.AgentRiskAdjudicator;
import com.chua.common.support.ai.agent.AgentRiskDecision;
import com.chua.common.support.ai.agent.AgentRiskRequest;
import com.chua.common.support.ai.agent.ToolRiskPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.util.MessageUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Agent 工具风险闸门中间件（acting 阶段统一裁决）。
 *
 * <p><b>为什么放在中间件</b>：Harness 内建的 shell / 文件等工具不经过
 * {@code Toolkit.callTool}（{@code Toolkit.callTools} 直接走
 * {@code ToolExecutor.executeAll}），包装工具级的闸门拦不到它们；
 * {@code onActing} 由 ReActAgent 在 acting 阶段对「本轮整批工具调用」统一调用，
 * 是唯一覆盖全部工具（含内建工具与 ask_user 之外的 schema 工具）的缝。</p>
 *
 * <p><b>注册顺序</b>：{@code MiddlewareChain.build} 从注册列表尾向头折叠，
 * 先注册者在外层——本中间件必须先于 {@link AgentDebugMiddleware} 注册，
 * 才能赶在调试 / 执行链之前完成裁决。</p>
 *
 * <p><b>裁决口径</b>：入参序列化为资源文本后按 {@link ToolRiskPolicy#classify} 预分级，
 * 交 {@link AgentRiskAdjudicator} 裁决；裁决器异常按拒绝兜底（fail-close）；
 * 危险操作即使裁决放行也拦截（fail-safe）；{@code CONFIRM_REQUIRED} 不挂起等待
 * 人工确认，直接拒绝并把原因回给模型改道。{@code exemptTools} 中的工具
 * （如 ask_user 这类纯交互 / 挂起型 schema 工具）只记账、不做风险裁决。</p>
 *
 * <p><b>拒绝语义</b>：对齐 SDK 自身拒绝路径——被拒调用补一条
 * {@link ToolResultState#DENIED} 的工具结果消息进上下文（模型下一轮可见，
 * SDK 的 pending / 全拒判定也依赖它），并发 ToolResult 三件套事件；
 * 全部拒绝时不再进入执行链。<b>注意不要自己发 {@code AllToolsDeniedEvent}</b>：
 * SDK 在 post-acting 检测到全部拒绝后，会经
 * {@code emitAllToolsDeniedThroughMiddleware} 携同一批调用二次进入本中间件，
 * 由链终端补发一次 {@code AllToolsDeniedEvent}——本中间件此时用
 * {@link MessageUtils#allToolCallsDenied} 识别幂等：直接透传结束，
 * 不重复记账、不重复补消息；若这里也发一次，订阅方会收到两个全拒事件。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
public class AgentToolGateMiddleware implements MiddlewareBase {

    /**
     * 工具入参序列化器（资源文本用；writeValueAsString 线程安全）
     */
    private static final ObjectMapper INPUT_MAPPER = new ObjectMapper();

    /**
     * 风险裁决器（null 表示不做风险裁决，仅记账）
     */
    private final AgentRiskAdjudicator riskAdjudicator;

    /**
     * Agent 标识（风险请求与审计日志用）
     */
    private final String agentId;

    /**
     * 预算记账器（null 表示不记账）
     */
    private final AgentBudgetTracker tracker;

    /**
     * 豁免风险裁决的工具名（如 ask_user 等纯交互 / 挂起型 schema 工具），可为 null
     */
    private final Set<String> exemptTools;

    /**
     * 创建工具风险闸门中间件。
     *
     * @param riskAdjudicator 风险裁决器，可为 null
     * @param agentId         Agent 标识，可为 null
     * @param tracker         预算记账器，可为 null
     * @param exemptTools     豁免风险裁决的工具名，可为 null
     */
    public AgentToolGateMiddleware(AgentRiskAdjudicator riskAdjudicator, String agentId,
                                   AgentBudgetTracker tracker, Set<String> exemptTools) {
        this.riskAdjudicator = riskAdjudicator;
        this.agentId = agentId;
        this.tracker = tracker;
        this.exemptTools = exemptTools;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input == null ? null : input.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return next.apply(input);
        }
        // 幂等：全拒后 SDK 携同一批调用二次进入，这批调用已带 DENIED 结果——
        // 透传给 SDK 的收口逻辑（发 AllToolsDeniedEvent 并继续下一轮），不重复记账 / 补消息
        if (alreadyAllDenied(ctx, toolCalls)) {
            return next.apply(input);
        }

        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (ToolUseBlock call : toolCalls) {
            // 预算记账：工具调用次数在闸门入口单点计数（同步 / 流式同一口径，
            // 豁免工具也计数——调用轮次本身消耗预算）
            if (tracker != null) {
                tracker.recordToolCall();
            }
            if (exemptTools != null && exemptTools.contains(call.getName())) {
                // 纯交互 / 挂起型 schema 工具（如 ask_user）不做风险裁决
                allowed.add(call);
                continue;
            }
            String reason = adjudicate(call);
            if (reason == null) {
                allowed.add(call);
            } else {
                denied.add(call);
                reasons.add(reason);
            }
        }
        if (denied.isEmpty()) {
            // 全部放行：原样透传（保持 input 与调用列表同一实例）
            return next.apply(input);
        }

        // 拒绝结果落上下文：模型下一轮可见；SDK 的 pending / 全拒判定同样依赖它
        appendDeniedResults(ctx, agent, denied, reasons);

        if (allowed.isEmpty()) {
            // 全拒：不进入执行链，只发三件套。AllToolsDeniedEvent 不要在这里发——
            // SDK 收口时会经 emitAllToolsDeniedThroughMiddleware 重入本中间件，
            // 由链终端补发一次（这里再发订阅方会收到两个全拒事件）；
            // 随后 SDK 继续下一轮迭代，模型凭上下文里的拒绝原因改道
            String replyId = newReplyId();
            List<AgentEvent> events = new ArrayList<>();
            for (int i = 0; i < denied.size(); i++) {
                events.addAll(deniedResultEvents(denied.get(i), reasons.get(i), replyId));
            }
            return Flux.fromIterable(events);
        }

        // 部分拒绝：放行子集进下游；replyId 从下游首个工具事件捕获（acting 每轮新生成，
        // 中间件入参拿不到），拒绝事件在下游完成后补发，避免抢占真实执行的事件顺序
        String fallbackReplyId = newReplyId();
        AtomicReference<String> replyIdRef = new AtomicReference<>(null);
        return next.apply(new ActingInput(allowed))
                .doOnNext(event -> captureReplyId(event, replyIdRef))
                .concatWith(Flux.defer(() -> {
                    String replyId = replyIdRef.get() != null ? replyIdRef.get() : fallbackReplyId;
                    List<AgentEvent> events = new ArrayList<>();
                    for (int i = 0; i < denied.size(); i++) {
                        events.addAll(deniedResultEvents(denied.get(i), reasons.get(i), replyId));
                    }
                    return Flux.fromIterable(events);
                }));
    }

    /**
     * 判断这批调用是否已全部带 DENIED 结果（SDK 全拒后二次进入的幂等标记）。
     */
    private boolean alreadyAllDenied(RuntimeContext ctx, List<ToolUseBlock> toolCalls) {
        try {
            return MessageUtils.allToolCallsDenied(ctx.getAgentState().contextMutable(), toolCalls);
        } catch (Exception e) {
            // 状态未就绪等异常不阻断正常裁决
            return false;
        }
    }

    /**
     * 裁决单个工具调用。
     *
     * @param call 待裁决的工具调用
     * @return null 表示放行；否则返回应回给模型的拒绝原因
     */
    private String adjudicate(ToolUseBlock call) {
        if (riskAdjudicator == null) {
            return null;
        }
        String toolName = call.getName();
        Map<String, Object> input = call.getInput();
        String resourcesText;
        try {
            resourcesText = INPUT_MAPPER.writeValueAsString(input);
        } catch (Exception e) {
            // 序列化失败退化为 toString：闸门不能因展示层问题放行或崩溃
            resourcesText = String.valueOf(input);
        }
        String riskLevel = ToolRiskPolicy.classify(toolName, resourcesText);
        AgentRiskRequest request = AgentRiskRequest.of(agentId, toolName, resourcesText, riskLevel);
        AgentRiskDecision decision;
        try {
            decision = riskAdjudicator.adjudicate(request);
        } catch (Exception e) {
            // 裁决器异常按拒绝兜底（fail-close）
            log.warn("[AgentScope] 工具风险裁决异常，按拒绝处理: agentId={}, tool={}",
                    agentId, toolName, e);
            decision = AgentRiskDecision.deny("风险裁决器异常: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        if (decision == null || decision.verdict() == null) {
            decision = AgentRiskDecision.deny("风险裁决器未给出结论");
        }
        String effectiveLevel = decision.riskLevel() != null && !decision.riskLevel().isBlank()
                ? decision.riskLevel() : riskLevel;
        String reason = decision.reason() != null && !decision.reason().isBlank()
                ? decision.reason() : null;
        switch (decision.verdict()) {
            case ALLOW:
                if (ToolRiskPolicy.isDangerous(effectiveLevel)) {
                    // 危险操作即使裁决放行也拦截（fail-safe：必须走人工强确认链路）
                    log.warn("[AgentScope] 危险操作被风险闸门拦截: agentId={}, tool={}, risk={}",
                            agentId, toolName, effectiveLevel);
                    return "危险操作已拦截（风险等级 " + effectiveLevel + "），需人工确认后重试";
                }
                return null;
            case DENY:
                log.info("[AgentScope] 工具调用被风险裁决拒绝: agentId={}, tool={}, reason={}",
                        agentId, toolName, reason);
                return "工具调用被风险裁决拒绝: " + (reason != null ? reason : "不允许执行该操作");
            case CONFIRM_REQUIRED:
            default:
                // 不挂起等待人工确认：fail-safe 跳过执行，把原因回给模型改道
                log.info("[AgentScope] 工具调用需人工确认，本次跳过执行: agentId={}, tool={}, risk={}, reason={}",
                        agentId, toolName, effectiveLevel, reason);
                return "工具调用需人工确认，本次已跳过执行（风险等级 "
                        + effectiveLevel + (reason != null ? "：" + reason : "") + "）";
        }
    }

    /**
     * 把被拒调用的 DENIED 结果消息追加进上下文。
     */
    private void appendDeniedResults(RuntimeContext ctx, Agent agent,
                                     List<ToolUseBlock> denied, List<String> reasons) {
        try {
            List<io.agentscope.core.message.Msg> context = ctx.getAgentState().contextMutable();
            for (int i = 0; i < denied.size(); i++) {
                ToolUseBlock call = denied.get(i);
                ToolResultBlock block = ToolResultBlock.text(reasons.get(i))
                        .withIdAndName(call.getId(), call.getName())
                        .withState(ToolResultState.DENIED);
                context.add(ToolResultMessageBuilder.buildToolResultMsg(block, call, agent.getName()));
            }
        } catch (Exception e) {
            log.warn("[AgentScope] 补写工具拒绝结果失败: agentId={}", agentId, e);
        }
    }

    /**
     * 构造单个被拒调用的 ToolResult 三件套事件（Start / TextDelta / End(DENIED)）。
     */
    private List<AgentEvent> deniedResultEvents(ToolUseBlock call, String reason, String replyId) {
        return Arrays.asList(
                new ToolResultStartEvent(replyId, call.getId(), call.getName()),
                new ToolResultTextDeltaEvent(replyId, call.getId(), call.getName(), reason),
                new ToolResultEndEvent(replyId, call.getId(), call.getName(), ToolResultState.DENIED));
    }

    /**
     * 从事件里捕获本轮 replyId（只记首个，后续覆盖无效）。
     */
    private void captureReplyId(AgentEvent event, AtomicReference<String> ref) {
        if (event == null || ref.get() != null) {
            return;
        }
        if (event instanceof ToolCallStartEvent start) {
            ref.compareAndSet(null, start.getReplyId());
        } else if (event instanceof ToolResultStartEvent result) {
            ref.compareAndSet(null, result.getReplyId());
        }
    }

    /**
     * 生成与 SDK 同格式的 replyId（去横线 UUID）。acting 每轮新生成且不落 AgentState，
     * 全拒分支拿不到本轮真实值，这里按同格式补一个（仅用于事件关联展示）。
     */
    private String newReplyId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
