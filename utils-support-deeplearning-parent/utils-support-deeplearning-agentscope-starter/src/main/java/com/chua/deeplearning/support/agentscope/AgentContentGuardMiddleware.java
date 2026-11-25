// [P3C A20 豁免] A20/A30/A31: 含日志/界面字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.AgentContentGuard;
import com.chua.common.support.ai.agent.AgentContentKind;
import com.chua.common.support.ai.agent.AgentContentVerdict;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Agent 内容护栏中间件（工具返回边界统一脱敏 / 摘除）。
 *
 * <p><b>为什么放在中间件</b>：Harness 内建的 shell / 文件等工具不经过
 * {@code Toolkit.callTool}，工具级包装拦不到它们的返回；{@code onModelCall}
 * 在每次模型调用前拿到完整上下文消息列表，是覆盖全部工具返回（内建工具 +
 * schema 工具）的单点收口。用户输入与模型输出边界由 {@code AgentScopeAgent}
 * 在 run / runStream 入口与输出聚合处检查，不在本中间件职责内。</p>
 *
 * <p><b>处理口径</b>：对上下文里每条 {@link ToolResultBlock} 的文本片段按
 * {@link AgentContentKind#TOOL_RESULT} 检查——命中注入则整段摘除为
 * {@link #BLOCKED_TEXT}，命中隐私 / 凭据则替换为脱敏文本；无命中的消息
 * 原样透传（同一实例），不产生额外拷贝。</p>
 *
 * <p><b>注册顺序</b>：{@code MiddlewareChain.build} 从注册列表尾向头折叠，
 * 先注册者在外层——本中间件注册在工具风险闸门之后、调试中间件之前，
 * 使脱敏后的上下文先于调试轨迹与模型调用生效。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
public class AgentContentGuardMiddleware implements MiddlewareBase {

    /**
     * 工具返回被护栏拒绝时的替换文本（模型下一轮可见，可据此改道），
     * 统一引用契约常量，避免字面量散落。
     */
    public static final String BLOCKED_TEXT = AgentContentGuard.BLOCKED_NOTICE;

    /**
     * 内容护栏（工具返回边界检查）
     */
    private final AgentContentGuard contentGuard;

    /**
     * Agent 标识（审计日志用）
     */
    private final String agentId;

    /**
     * 创建内容护栏中间件。
     *
     * @param contentGuard 内容护栏，不应为 null
     * @param agentId      Agent 标识，可为 null
     */
    public AgentContentGuardMiddleware(AgentContentGuard contentGuard, String agentId) {
        this.contentGuard = contentGuard;
        this.agentId = agentId;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (contentGuard == null || input == null
                || input.messages() == null || input.messages().isEmpty()) {
            return next.apply(input);
        }
        List<Msg> messages = input.messages();
        List<Msg> sanitized = new ArrayList<>(messages.size());
        boolean changed = false;
        for (Msg message : messages) {
            Msg cleaned = sanitizeMessage(message);
            if (cleaned != message) {
                changed = true;
            }
            sanitized.add(cleaned);
        }
        if (!changed) {
            // 无命中：原 input 原样透传，避免无谓的入参重建
            return next.apply(input);
        }
        return next.apply(new ModelCallInput(sanitized, input.tools(), input.options(), input.model()));
    }

    /**
     * 脱敏单条消息：只处理带工具返回块的消息，其余原样返回（同一实例）。
     */
    private Msg sanitizeMessage(Msg message) {
        if (message == null || !message.hasContentBlocks(ToolResultBlock.class)) {
            return message;
        }
        List<ContentBlock> content = message.getContent();
        if (content == null || content.isEmpty()) {
            return message;
        }
        List<ContentBlock> rebuilt = new ArrayList<>(content.size());
        boolean changed = false;
        for (ContentBlock block : content) {
            if (block instanceof ToolResultBlock toolResult) {
                ToolResultBlock cleaned = sanitizeToolResult(toolResult);
                if (cleaned != toolResult) {
                    changed = true;
                }
                rebuilt.add(cleaned);
            } else {
                rebuilt.add(block);
            }
        }
        return changed ? message.withContent(rebuilt) : message;
    }

    /**
     * 脱敏单个工具返回块：只处理文本片段；注入命中整段摘除，隐私 / 凭据命中脱敏替换。
     */
    private ToolResultBlock sanitizeToolResult(ToolResultBlock toolResult) {
        List<ContentBlock> output = toolResult.getOutput();
        if (output == null || output.isEmpty()) {
            return toolResult;
        }
        List<ContentBlock> rebuilt = new ArrayList<>(output.size());
        boolean changed = false;
        for (ContentBlock part : output) {
            if (part instanceof TextBlock textBlock) {
                String original = textBlock.getText();
                if (original == null || original.isBlank()) {
                    rebuilt.add(part);
                    continue;
                }
                AgentContentVerdict verdict = contentGuard.inspect(AgentContentKind.TOOL_RESULT, original);
                String replacement = resolveText(verdict, original, toolResult);
                if (replacement != original) {
                    changed = true;
                    rebuilt.add(TextBlock.builder().text(replacement).build());
                } else {
                    rebuilt.add(part);
                }
            } else {
                rebuilt.add(part);
            }
        }
        if (!changed) {
            return toolResult;
        }
        return new ToolResultBlock(toolResult.getId(), toolResult.getName(), rebuilt,
                toolResult.getMetadata(), toolResult.getState());
    }

    /**
     * 按结论解析工具返回文本：BLOCK 摘除为通知文本；REDACT 用脱敏文本；ALLOW 沿用原文。
     */
    private String resolveText(AgentContentVerdict verdict, String original, ToolResultBlock toolResult) {
        if (verdict == null) {
            return original;
        }
        switch (verdict.action()) {
            case BLOCK:
                log.warn("[AgentScope] 工具返回被内容护栏摘除: agentId={}, tool={}, findings={}",
                        agentId, toolResult.getName(), describeFindings(verdict));
                return BLOCKED_TEXT;
            case REDACT:
                return verdict.content() != null ? verdict.content() : original;
            case ALLOW:
            default:
                return original;
        }
    }

    /**
     * 汇总命中项为简短描述（类型/规则，去重限 5 条）。
     */
    private static String describeFindings(AgentContentVerdict verdict) {
        if (verdict == null || verdict.findings().isEmpty()) {
            return "unspecified";
        }
        return verdict.findings().stream()
                .map(finding -> finding.type()
                        + (finding.rule() == null || finding.rule().isBlank() ? "" : "/" + finding.rule()))
                .distinct()
                .limit(5)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
