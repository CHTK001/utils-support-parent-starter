package com.chua.common.support.ai.chat;

import com.chua.common.support.ai.AiUsage;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一次决策请求的完整返回。
 *
 * <p>两种协议的返回都有 {@code answers}：
 * <ul>
 *   <li>OpenAI 协议返回 {@code answers} 数组，每项携带 {@code name}，
 *       调用方按 {@code name} 与问题对照</li>
 *   <li>SystemOne 协议返回按问题名分发的 {@code answers} 对象</li>
 * </ul>
 * 本对象统一收敛为按问题名称分发的 {@code Map}，并携带用量信息
 * {@link #usage()}。
 *
 * @param model    完成决策的模型名称（OpenAI 协议为 gpt-6-luna，SystemOne 协议为 jev-latest）
 * @param protocol 使用的决策协议（OPENAI / SYSTEMONE）
 * @param answers  问题名 → 答案，如果问题被拒绝则元素为拒绝型 {@link DecisionAnswer}
 * @param usage    本次请求的 Token 用量
 * @author CH
 * @since 4.0.0.42
 */
public record DecisionsResult(
        String model,
        DecisionsProtocol protocol,
        Map<String, DecisionAnswer> answers,
        AiUsage usage) {

    /**
     * 空结果。
     *
     * @return 空结果
     */
    public static DecisionsResult empty() {
        return new DecisionsResult(null, DecisionsProtocol.OPENAI, Collections.emptyMap(), null);
    }

    /**
     * 是否被全部问题拒绝。
     *
     * @return true 表示所有问题均未返回结论
     */
    public boolean allRefused() {
        if (answers == null || answers.isEmpty()) {
            return true;
        }
        return answers.values().stream().allMatch(DecisionAnswer::isRefusal);
    }
}
