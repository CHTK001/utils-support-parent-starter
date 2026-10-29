package com.chua.common.support.ai.chat;

import com.chua.common.support.collection.wrap.Wraps;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 一次决策请求的完整参数。
 *
 * <p>OpenAI 协议将 {@link #input()} 作为共享证据（questions 的 {@code input}）、
 * 把 {@link #questions()} 排列为列表；SystemOne 协议将 {@link #state()} 作为
 * 所有问题引用的上下文（{"model","state","questions"}）、并把 {@link #questions()}
 * 按问题名排列为键值对。两种协议各自取自己需要的字段，另一个字段如果
 * 非空则由实现类回填，保持协议变更后只需换协议，不需换请求。
 *
 * @param model      模型名称（OpenAI 默认 {@code gpt-6-luna}，SystemOne 默认 {@code jev-latest}）
 * @param input      OpenAI 协议的共享证据
 * @param state      SystemOne 协议的问题上下文
 * @param questions  问题列表，各问题 {@link DecisionQuestion#name()} 必须唯一
 * @param extraBody  透传协议的额外请求体参数
 * @author CH
 * @since 4.0.0.42
 */
public record DecisionsRequest(
        String model,
        String input,
        String state,
        List<DecisionQuestion> questions,
        Map<String, Object> extraBody) {

    /**
     * 构造决策请求（共享证据 + 问题列表）。
     *
     * @param input    共享证据
     * @param questions 问题列表
     * @return 决策请求
     */
    public static DecisionsRequest create(String input, List<DecisionQuestion> questions) {
        return new DecisionsRequest(null, input, input, questions, null);
    }

    /**
     * 构造决策请求（携带模型 + 共享证据 + 问题列表）。
     *
     * @param model    模型名称
     * @param input    共享证据
     * @param questions 问题列表
     * @return 决策请求
     */
    public static DecisionsRequest create(String model, String input, List<DecisionQuestion> questions) {
        return new DecisionsRequest(model, input, input, questions, null);
    }

    /**
     * 获取全部问题标识。
     *
     * @return 问题标识列表
     */
    public List<String> questionNames() {
        if (questions == null || questions.isEmpty()) {
            return Wraps.list();
        }
        return questions.stream().map(DecisionQuestion::name).toList();
    }

    /**
     * 询问问题是否含有不合法的重复标识。
     *
     * @return true 表示有重复的问题标识
     */
    public boolean hasDuplicatedQuestion() {
        if (questions == null) {
            return false;
        }
        List<String> names = questionNames();
        int size = names.size();
        return size != new LinkedHashSet<>(names).size();
    }

    /**
     * 按问题名称查找问题定义。
     *
     * @param name 问题标识
     * @return 对应的问题定义；不存在时返回 null
     */
    public DecisionQuestion question(String name) {
        if (questions == null) {
            return null;
        }
        return questions.stream().filter(q -> name.equals(q.name())).findFirst().orElse(null);
    }
}
