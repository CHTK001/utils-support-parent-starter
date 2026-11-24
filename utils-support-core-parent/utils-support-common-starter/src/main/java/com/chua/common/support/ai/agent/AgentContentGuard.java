package com.chua.common.support.ai.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 内容护栏契约。
 *
 * <p>在三个内容边界上检查文本（见 {@link AgentContentKind}）：</p>
 * <ul>
 *   <li>用户输入进入模型前 — 拦截提示注入、脱敏隐私；</li>
 *   <li>工具返回回填模型前 — 摘除「指令伪装」片段（提示注入的主战场）、
 *       脱敏隐私与凭据；</li>
 *   <li>模型输出返回用户 / 下游前 — 脱敏隐私与凭据，防泄漏。</li>
 * </ul>
 *
 * <p>指令层级契约：系统提示 &gt; 用户输入 &gt; 工具返回。护栏负责把
 * 「工具返回里的指令样式文本」降级为数据处理（摘除片段或整体拒绝），
 * 防止外部内容越权驱动 Agent。</p>
 *
 * <p>实现要求：</p>
 * <ul>
 *   <li>纯函数语义：不修改入参，不依赖调用顺序；</li>
 *   <li>不得抛异常打断主流程——实现方应自行兜底（内置的
 *       {@link HeuristicAgentContentGuard} 在构造期预编译全部规则，运行期不抛）；</li>
 *   <li>命中项（findings）供审计 / 评测消费，调用方负责留痕。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.52
 */
@FunctionalInterface
public interface AgentContentGuard {

    /**
     * 检查一段内容。
     *
     * @param request 检查请求，不应为 null
     * @return 结论，不应为 null
     */
    AgentContentVerdict inspect(AgentContentRequest request);

    /**
     * 按最小参数检查。
     *
     * @param kind    内容边界类型
     * @param content 待检查文本
     * @return 结论
     */
    default AgentContentVerdict inspect(AgentContentKind kind, String content) {
        return inspect(AgentContentRequest.of(kind, content));
    }

    /**
     * 空护栏（默认兜底，等价于不启用）。
     *
     * @return 全放行护栏
     */
    static AgentContentGuard noop() {
        return request -> AgentContentVerdict.allow();
    }

    /**
     * 默认启发式护栏（注入检测 + 隐私脱敏 + 凭据拦截，规则可用 Builder 调整）。
     *
     * @return 启发式护栏
     */
    static AgentContentGuard heuristic() {
        return new HeuristicAgentContentGuard();
    }

    /**
     * 组合多个护栏：任一 BLOCK 即拒绝；REDACT 依次叠加（前一个的替换结果
     * 作为后一个的输入）；命中项合并。
     *
     * @param guards 护栏列表
     * @return 组合护栏；空列表返回 {@link #noop()}
     */
    static AgentContentGuard chain(AgentContentGuard... guards) {
        List<AgentContentGuard> list = new ArrayList<>();
        if (guards != null) {
            for (AgentContentGuard guard : guards) {
                if (guard != null) {
                    list.add(guard);
                }
            }
        }
        if (list.isEmpty()) {
            return noop();
        }
        if (list.size() == 1) {
            return list.get(0);
        }
        return request -> {
            AgentContentRequest current = request;
            List<AgentContentFinding> merged = new ArrayList<>();
            for (AgentContentGuard guard : list) {
                AgentContentVerdict verdict = guard.inspect(current);
                if (verdict == null) {
                    continue;
                }
                merged.addAll(verdict.findings());
                if (verdict.action() == AgentContentVerdict.Action.BLOCK) {
                    return AgentContentVerdict.block(merged);
                }
                if (verdict.action() == AgentContentVerdict.Action.REDACT) {
                    current = new AgentContentRequest(current.kind(), current.agentId(),
                            current.sessionId(), verdict.content(), current.attributes());
                }
            }
            if (current != request) {
                return AgentContentVerdict.redact(current.content(), merged);
            }
            return AgentContentVerdict.allow(merged);
        };
    }
}
