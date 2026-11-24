package com.chua.common.support.ai.agent;

/**
 * Agent 内容边界类型。
 *
 * <p>内容护栏在三个边界上检查文本，三个边界的信任等级不同：</p>
 * <ul>
 *   <li>{@link #USER_INPUT} — 用户输入：中等信任，可能夹带注入指令或隐私信息；</li>
 *   <li>{@link #TOOL_RESULT} — 工具返回：低信任。网页、文件、数据库记录等外部
 *       内容可能携带「指令伪装」（提示注入），把工具返回当指令执行是 Agent
 *       最常见的攻击面；</li>
 *   <li>{@link #MODEL_OUTPUT} — 模型输出：回到用户 / 下游系统前的最后一道闸，
 *       重点拦截隐私与凭据泄漏。</li>
 * </ul>
 *
 * <p>指令层级契约：系统提示 &gt; 用户输入 &gt; 工具返回。工具返回中出现的
 * 「指令样式文本」不拥有指令权力，只按数据处理（处理口径见
 * {@link AgentContentGuard}）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public enum AgentContentKind {

    /**
     * 用户输入（发给模型前检查）
     */
    USER_INPUT,

    /**
     * 工具返回（回填给模型前检查，信任等级最低）
     */
    TOOL_RESULT,

    /**
     * 模型输出（返回给用户 / 下游系统前检查）
     */
    MODEL_OUTPUT
}
