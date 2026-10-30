package com.chua.common.support.ai.chat;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.decision.DecisionType;

import java.util.List;
import java.util.Map;

/**
 * 决策问题的定义。
 *
 * <p>调用方声明一个或多个 {@link DecisionQuestion}，由 AI 大模型在同一个问题上下文
 * （OpenAI 协议的 {@code input} / SystemOne 协议的 {@code state}）下回答。
 * 一个决策请求内部的问题列表必须保证 {@link #name()} 唯一，它是答案回填的唯一依据。
 *
 * <p>三种基本形态由 {@link DecisionType} 表达，协议的字段名差异由实现类自行翻译：
 * <ul>
 *   <li>{@link DecisionType#NOUL} — 是/否型判断：</li>
 *   <ul>
 *     <li>OpenAI 协议：问题类型 {@code predicate}，无专属入参组件</li>
 *     <li>SystemOne 协议：问题类型 {@code noul}，
 *         {@link #criteria()} 为 {@code true}/{@code false} 的说明表</li>
 *   </ul>
 *   <li>{@link DecisionType#CHOICE} — 多选一型。</li>
 *   <ul>
 *     <li>OpenAI 协议：问题类型 {@code choice}，{@link #criteria()} 转为
 *         {@code choices}（{@code value}/{@code description}）</li>
 *     <li>SystemOne 协议：问题类型 {@code choice}，{@link #criteria()} 原样透传
 *         （选项名转为答案标签 {@code A} ~ {@code Z}）</li>
 *   </ul>
 *   <li>{@link DecisionType#SCORE} — 有序分级型。</li>
 *   <ul>
 *     <li>OpenAI 协议：问题类型 {@code score}，{@link #levels()} 转为
 *         {@code levels}（{@code label}/{@code description}）</li>
 *     <li>SystemOne 协议：问题类型 {@code score}，{@link #levels()} 转为
 *         {@code criteria}（等级描述列表，位置决定等级索引）</li>
 *   </ul>
 * </ul>
 *
 * <p>答案成员：
 * <ul>
 *   <li>{@link DecisionType#NOUL} — 答案标签 {@code yes}/{@code no}，答案值
 *       为 `yes` 的概率（{@link DecisionAnswer#probability()}）</li>
 *   <li>{@link DecisionType#CHOICE} — 答案标签为 {@code A} ~ {@code Z}（或
 *       选项原始名），答案值为用户选中的标签</li>
 *   <li>{@link DecisionType#SCORE} — 答案标签为 {@code 0} ~ {@code 9}，答案值
 *       为等级期望分（概率加权平均索引）</li>
 * </ul>
 *
 * @param type       问题类型，同一个问题一旦声明不可变更
 * @param name       问题标识，同一请求内唯一；答案以它为键回传
 * @param criteria   条件表，仅类型匹配的组件允许非 null：CHOICE 为选项名→说明，
 *                   NOUL 为 true/false→说明，SCORE 不使用
 * @param levels     等级列表，仅 SCORE 使用，从低到高排列，2~10 级
 * @param instructions 题面说明，模型回答时看到的自然语言指令
 * @author CH
 * @since 4.0.0.42
 */
public record DecisionQuestion(
        DecisionType type,
        String name,
        Map<String, String> criteria,
        List<String> levels,
        String instructions) {

    /**
     * 选项数量上限
     */
    public static final int MAX_OPTIONS = 255;

    /**
     * 等级数量下限
     */
    public static final int MIN_LEVELS = 2;

    /**
     * 等级数量上限
     */
    public static final int MAX_LEVELS = 10;

    /**
     * 构造单个决策问题。
     *
     * <p>value class 前置条件——{@code criteria} 与 {@code levels} 用
     * {@link Map#copyOf(Map)}、{@link List#copyOf(Collection)} 做防御性拷贝，
     * 挡住「构造后外部改写集合」这一类污染。按类型生效的组件为 null 时保留
     * null 语义，不清空。</p>
     *
     * @param type        问题类型
     * @param name        问题标识，同一请求内唯一
     * @param criteria    条件表，按类型生效
     * @param levels      等级列表，按类型生效
     * @param instructions 题面说明
     */
    public DecisionQuestion(DecisionType type, String name, Map<String, String> criteria, List<String> levels,
                            String instructions) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白");
        }
        if (type == null) {
            throw new NullPointerException("type 不能为 null");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions 不能为 null 或空白");
        }
        if (type.isChoice()) {
            rejectLevels(type, levels);
            criteria = copyCriteria(type, criteria);
        } else if (type.isScore()) {
            rejectOptions(type, criteria);
            levels = copyLevels(levels);
        } else {
            rejectOptions(type, criteria);
            rejectLevels(type, levels);
            criteria = copyNoulCriteria(type, criteria);
        }
        this.type = type;
        this.name = name;
        this.criteria = criteria;
        this.levels = levels;
        this.instructions = instructions;
    }

    /**
     * 构造是 / 否型问题。
     *
     * @param name        问题标识
     * @param instructions 题面说明
     * @return 是 / 否型问题
     */
    public static DecisionQuestion noul(String name, String instructions) {
        return new DecisionQuestion(DecisionType.NOUL, name, null, null, instructions);
    }

    /**
     * 构造带结论说明的是 / 否型问题。
     *
     * @param name         问题标识
     * @param instructions 题面说明
     * @param criteria     结论说明表，键为 true / false
     * @return 是 / 否型问题
     */
    public static DecisionQuestion noul(String name, String instructions, Map<String, String> criteria) {
        return new DecisionQuestion(DecisionType.NOUL, name, criteria, null, instructions);
    }

    /**
     * 构造多选一型问题。
     *
     * @param name         问题标识
     * @param instructions 题面说明
     * @param criteria     选项表，键为答案标签、值为选项说明
     * @return 多选一型问题
     */
    public static DecisionQuestion choice(String name, String instructions, Map<String, String> criteria) {
        return new DecisionQuestion(DecisionType.CHOICE, name, criteria, null, instructions);
    }

    /**
     * 构造有序分级型问题。
     *
     * @param name         问题标识
     * @param instructions 题面说明
     * @param levels       有序等级列表，2~10 级
     * @return 有序分级型问题
     */
    public static DecisionQuestion score(String name, String instructions, List<String> levels) {
        return new DecisionQuestion(DecisionType.SCORE, name, null, levels, instructions);
    }

    /**
     * 拷贝并校验选项表。
     * @param Map<String 参数
     * @param criteria 选项表
     * @param type 参数
     * @return 不可变选项表
    */
    private static Map<String, String> copyCriteria(DecisionType type, Map<String, String> criteria) {
        if (criteria == null) {
            throw new IllegalArgumentException(type + " 类型必须提供 criteria 选项表");
        }
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException("options 不能为空");
        }
        if (criteria.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("options 最多 " + MAX_OPTIONS + " 项，实际 " + criteria.size());
        }
        return Map.copyOf(criteria);
    }

    /**
     * 拷贝并校验结论说明表。 <p>键<b>必须</b>取自 {@link DecisionType#TRUE_LABEL} / {@link DecisionType#FALSE_LABEL}。这里刻意不接受自定义键：{@code criteria} 的角色是「给 true / false 两端配说明文字」，而不是重新定义答案标签。</p>
     * @param Map<String 参数
     * @param criteria 结论说明表，可为 null
     * @param type 参数
     * @return 不可变结论说明表，或 null
    */
    private static Map<String, String> copyNoulCriteria(DecisionType type, Map<String, String> criteria) {
        if (criteria == null) {
            return null;
        }
        for (String key : criteria.keySet()) {
            if (!DecisionType.TRUE_LABEL.equals(key) && !DecisionType.FALSE_LABEL.equals(key)) {
                throw new IllegalArgumentException(type + " 的 criteria 键只能是 "
                        + DecisionType.TRUE_LABEL + " / " + DecisionType.FALSE_LABEL + "，实际: " + key);
            }
        }
        return Map.copyOf(criteria);
    }

    /**
     * 拷贝并校验等级列表。
     *
     * @param levels 等级列表
     * @return 不可变等级列表
     */
    private static List<String> copyLevels(List<String> levels) {
        if (levels == null) {
            throw new IllegalArgumentException("SCORE 类型必须提供 levels");
        }
        if (levels.size() < MIN_LEVELS || levels.size() > MAX_LEVELS) {
            throw new IllegalArgumentException(
                    "levels 必须为 " + MIN_LEVELS + "~" + MAX_LEVELS + " 级，实际 " + levels.size());
        }
        return List.copyOf(levels);
    }

    /**
     * 拒绝与 {@code type} 不匹配的等级列表。
     *
     * @param type   当前问题类型，用于异常消息
     * @param levels 等级列表
     */
    private static void rejectLevels(DecisionType type, List<String> levels) {
        if (levels != null && !levels.isEmpty()) {
            throw new IllegalArgumentException(type + " 类型不应携带 levels");
        }
    }

    /**
     * 拒绝与 {@code type} 不匹配的选项表。
     *
     * @param type     当前问题类型，用于异常消息
     * @param criteria 选项表
     */
    private static void rejectOptions(DecisionType type, Map<String, String> criteria) {
        if (criteria != null && !criteria.isEmpty()) {
            throw new IllegalArgumentException(type + " 类型不应携带 criteria");
        }
    }
}
