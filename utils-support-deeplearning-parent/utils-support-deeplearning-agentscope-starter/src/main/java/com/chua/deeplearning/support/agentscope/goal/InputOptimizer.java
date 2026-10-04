package com.chua.deeplearning.support.agentscope.goal;

import com.chua.common.support.ai.chat.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 输入优化器：在用户发送前，结合会话上下文与当前目标，把原始输入改写成更明确的指令。
 *
 * <p>对应需求「输入信息发送，后台 AI 结合上下文优化输入的信息」。
 *
 * <p><b>失败必须回到原输入</b>：优化是增益功能，不是必经链路。模型不可用、超时、
 * 返回空串时一律原样返回用户输入；把用户的输入弄丢，比不优化严重得多。
 *
 * <p><b>有界</b>：只做一次模型调用，不重试、不循环；上下文按字符数截断，避免长会话把提示词撑爆。
 *
 * @author CH
 */
public final class InputOptimizer {

    private static final Logger log = LoggerFactory.getLogger(InputOptimizer.class);

    /**
     * 上下文最多带入的字符数（超出部分从最旧开始丢弃）。
     */
    private static final int MAX_CONTEXT_CHARS = 2000;

    /**
     * 目标标题/说明拼进提示词时的最大字符数。
     */
    private static final int MAX_GOAL_CHARS = 800;

    private final ChatClient chatClient;

    /**
     * @param chatClient 用于改写输入的对话客户端，不能为 null
     */
    public InputOptimizer(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient 不能为 null");
    }

    /**
     * 结合目标与上下文优化用户输入。
     *
     * @param rawInput      用户原始输入，可为 null/空白
     * @param goal          当前目标，可为 null（无目标时仅按上下文优化）
     * @param recentContext 最近的会话上下文（由旧到新），可为 null
     * @return 优化后的输入；任何异常或空结果都回退为原输入
     */
    public String optimize(String rawInput, SessionGoal goal, List<String> recentContext) {
        if (rawInput == null || rawInput.isBlank()) {
            return rawInput;
        }
        String prompt = buildPrompt(rawInput, goal, recentContext);
        try {
            String optimized = chatClient.chatSync(prompt);
            if (optimized == null || optimized.isBlank()) {
                log.warn("[InputOptimizer] 模型返回空结果，回退原输入");
                return rawInput;
            }
            return optimized.trim();
        } catch (RuntimeException e) {
            log.warn("[InputOptimizer] 优化失败，回退原输入: {}", e.getMessage());
            return rawInput;
        }
    }

    /**
     * 组装优化提示词。
     *
     * @param rawInput      原始输入
     * @param goal          当前目标，可为 null
     * @param recentContext 最近上下文，可为 null
     * @return 提示词
     */
    static String buildPrompt(String rawInput, SessionGoal goal, List<String> recentContext) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("你是输入改写助手。请把【用户输入】改写成一条清晰、具体、可直接执行的指令，")
                .append("保留原意与关键约束，不要添加用户没提的需求。\n");
        sb.append("只输出改写后的指令本身，不要解释、不要加引号、不要输出多行标题。\n\n");
        if (goal != null) {
            sb.append("【当前目标】").append(truncate(goal.title(), MAX_GOAL_CHARS)).append('\n');
            if (!goal.detail().isBlank()) {
                sb.append("【目标说明】").append(truncate(goal.detail(), MAX_GOAL_CHARS)).append('\n');
            }
            sb.append("改写时请让输入服务于该目标。\n\n");
        }
        String context = joinContext(recentContext);
        if (!context.isBlank()) {
            sb.append("【最近上下文】\n").append(context).append("\n\n");
        }
        sb.append("【用户输入】\n").append(rawInput);
        return sb.toString();
    }

    /**
     * 把最近上下文拼成文本，并按字符上限从最旧开始裁剪。
     *
     * @param recentContext 最近上下文，可为 null
     * @return 拼接后的文本（可能为空串）
     */
    private static String joinContext(List<String> recentContext) {
        if (recentContext == null || recentContext.isEmpty()) {
            return "";
        }
        StringBuilder reversed = new StringBuilder(256);
        int used = 0;
        for (int i = recentContext.size() - 1; i >= 0; i--) {
            String line = recentContext.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            int cost = line.length() + 1;
            if (used + cost > MAX_CONTEXT_CHARS) {
                break;
            }
            reversed.insert(0, line + "\n");
            used += cost;
        }
        return reversed.toString().trim();
    }

    /**
     * 截断文本到指定字符数。
     *
     * @param text  原文本
     * @param limit 上限
     * @return 截断后的文本
     */
    private static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text == null ? "" : text;
        }
        return text.substring(0, limit) + "…";
    }
}
