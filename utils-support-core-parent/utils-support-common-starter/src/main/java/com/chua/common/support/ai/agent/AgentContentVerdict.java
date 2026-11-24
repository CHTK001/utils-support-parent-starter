package com.chua.common.support.ai.agent;

import java.util.List;

/**
 * 内容护栏结论。
 *
 * <p>三种动作语义：</p>
 * <ul>
 *   <li>{@link Action#ALLOW} — 放行（可携带仅审计用的命中项）；</li>
 *   <li>{@link Action#REDACT} — 放行替换后的内容，原内容不外发；</li>
 *   <li>{@link Action#BLOCK} — 拒绝该段内容进入下一环节（用户输入被拒 /
 *       工具结果不回填 / 输出不返回）。</li>
 * </ul>
 *
 * @param action   结论动作，不应为 null
 * @param content  替换后的内容；{@link Action#ALLOW} / {@link Action#BLOCK}
 *                 时为 null（调用方沿用原内容或按拒绝处理）
 * @param findings 全部命中项（含审计级），不应为 null
 * @author CH
 * @since 4.0.0.52
 */
public record AgentContentVerdict(Action action, String content, List<AgentContentFinding> findings) {

    /**
     * 结论动作
     */
    public enum Action {
        /**
         * 放行
         */
        ALLOW,
        /**
         * 脱敏 / 摘除后放行
         */
        REDACT,
        /**
         * 拒绝该段内容
         */
        BLOCK
    }

    /**
     * 紧凑构造：命中项防御性拷贝。
     */
    public AgentContentVerdict {
        action = action == null ? Action.ALLOW : action;
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * 放行（无命中）。
     *
     * @return 放行结论
     */
    public static AgentContentVerdict allow() {
        return new AgentContentVerdict(Action.ALLOW, null, List.of());
    }

    /**
     * 放行（携带审计用命中项）。
     *
     * @param findings 命中项
     * @return 放行结论
     */
    public static AgentContentVerdict allow(List<AgentContentFinding> findings) {
        return new AgentContentVerdict(Action.ALLOW, null, findings);
    }

    /**
     * 脱敏后放行。
     *
     * @param sanitized 替换后的内容
     * @param findings  命中项
     * @return 脱敏结论
     */
    public static AgentContentVerdict redact(String sanitized, List<AgentContentFinding> findings) {
        return new AgentContentVerdict(Action.REDACT, sanitized, findings);
    }

    /**
     * 拒绝。
     *
     * @param findings 命中项
     * @return 拒绝结论
     */
    public static AgentContentVerdict block(List<AgentContentFinding> findings) {
        return new AgentContentVerdict(Action.BLOCK, null, findings);
    }

    /**
     * 是否有任一命中项。
     *
     * @return true 表示存在命中项
     */
    public boolean hasFindings() {
        return !findings.isEmpty();
    }
}
