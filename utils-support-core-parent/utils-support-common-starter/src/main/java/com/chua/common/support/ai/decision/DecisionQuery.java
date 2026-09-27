package com.chua.common.support.ai.decision;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 单个概率决策问题。
 *
 * <p>由规则定义层在 {@code .decision(queryId, ...)} 时前置声明，
 * 执行期由 {@link DecisionProvider} 批量求值。一个 {@link DecisionRequest}
 * 内的 {@code id} 必须唯一——它是答案回填与上下文变量展平的唯一依据。</p>
 *
 * <p><b>按类型生效的组件</b>：三个形态专属组件互斥，且只有与 {@link #type}
 * 匹配的那个才允许非 null：</p>
 * <table border="1">
 *   <caption>类型与组件对应关系</caption>
 *   <tr><th>type</th><th>必填组件</th><th>可选组件</th><th>禁止出现</th></tr>
 *   <tr><td>{@link DecisionType#NOUL}</td><td>无</td><td>{@code criteria}</td>
 *       <td>{@code options}、{@code levels}</td></tr>
 *   <tr><td>{@link DecisionType#CHOICE}</td><td>{@code options}</td><td>无</td>
 *       <td>{@code levels}、{@code criteria}</td></tr>
 *   <tr><td>{@link DecisionType#SCORE}</td><td>{@code levels}</td><td>无</td>
 *       <td>{@code options}、{@code criteria}</td></tr>
 * </table>
 *
 * <p>不匹配的类型组件保持为 {@code null}（而非空集合）：这样实现方
 * 转成自己的问题对象时可以靠「为 null 就别输出」直接复用
 * 仓库统一的 JSON 空值策略，避免把不需要的字段发出去触发服务端校验失败。</p>
 *
 * <p><b>取值上限</b>：{@code options} 最多 255 项、{@code levels} 为 2~10 级。
 * 这两个上限与主流概率决策服务的请求体限制一致，在<b>构造期</b>就拦住，
 * 比等到 HTTP 422 再回来定位省事得多。</p>
 *
 * @param id       问题标识，同一请求内唯一；答案与上下文变量均以此为键
 * @param type     问题类型
 * @param question 自然语言问题
 * @param options  选项表（键为答案标签、值为选项说明），仅 {@link DecisionType#CHOICE} 使用
 * @param levels   有序等级列表，仅 {@link DecisionType#SCORE} 使用
 * @param criteria 结论说明表（键为 true / false、值为成立或不成立的说明），仅 {@link DecisionType#NOUL} 使用
 * @author CH
 * @since 4.0.0.42
 */
public record DecisionQuery(
        String id,
        DecisionType type,
        String question,
        Map<String, String> options,
        List<String> levels,
        Map<String, String> criteria) {

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
     * 构造单个概率决策问题。
     *
     * <p>value class 前置条件——{@code options} 与 {@code criteria} 用
     * {@link Map#copyOf(Map)}、{@code levels} 用 {@link List#copyOf(Collection)}
     * 做防御性拷贝，挡住「构造后外部改写集合」这一类污染。
     * 三个组件都允许为 null（按类型生效），此时保留 null 语义不清空。</p>
     *
     * <p>刻意<b>不</b>对三个组件加 {@code requireNonNull}：它们只在与
     * {@code type} 匹配时才语义必填，加了会把「类型不匹配」这种明确的
     * 调用错误变成 {@link NullPointerException}，反而丢失诊断信息。
     * 必填性由下面的按类型校验负责。</p>
     *
     * @param id       问题标识，同一请求内唯一
     * @param type     问题类型
     * @param question 自然语言问题
     * @param options  选项表，仅 CHOICE 使用
     * @param levels   有序等级列表，仅 SCORE 使用
     * @param criteria 结论说明表，仅 NOUL 使用
     * @throws NullPointerException     {@code type} 为 null 时
     * @throws IllegalArgumentException 标识或问题为空白、类型专属组件不匹配或超出取值上限时
     */
    public DecisionQuery {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为 null 或空白");
        }
        if (type == null) {
            throw new NullPointerException("type 不能为 null");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为 null 或空白");
        }
        if (type.isChoice()) {
            rejectLevels(type, levels);
            rejectCriteria(type, criteria);
            options = copyOptions(options);
        } else if (type.isScore()) {
            rejectOptions(type, options);
            rejectCriteria(type, criteria);
            levels = copyLevels(levels);
        } else {
            rejectOptions(type, options);
            rejectLevels(type, levels);
            criteria = copyCriteria(criteria);
        }
    }

    /**
     * 构造是 / 否型问题。
     *
     * @param id       问题标识
     * @param question 自然语言问题
     * @return 是 / 否型问题
     */
    public static DecisionQuery noul(String id, String question) {
        return new DecisionQuery(id, DecisionType.NOUL, question, null, null, null);
    }

    /**
     * 构造带结论说明的是 / 否型问题。
     *
     * @param id       问题标识
     * @param question 自然语言问题
     * @param criteria 结论说明表，键为 true / false
     * @return 是 / 否型问题
     */
    public static DecisionQuery noul(String id, String question, Map<String, String> criteria) {
        return new DecisionQuery(id, DecisionType.NOUL, question, null, null, criteria);
    }

    /**
     * 构造多选一型问题。
     *
     * @param id       问题标识
     * @param question 自然语言问题
     * @param options  选项表，键为答案标签、值为选项说明
     * @return 多选一型问题
     */
    public static DecisionQuery choice(String id, String question, Map<String, String> options) {
        return new DecisionQuery(id, DecisionType.CHOICE, question, options, null, null);
    }

    /**
     * 构造有序分级型问题。
     *
     * @param id       问题标识
     * @param question 自然语言问题
     * @param levels   有序等级列表，2~10 项
     * @return 有序分级型问题
     */
    public static DecisionQuery score(String id, String question, List<String> levels) {
        return new DecisionQuery(id, DecisionType.SCORE, question, null, levels, null);
    }

    /**
     * 拷贝并校验选项表。
     *
     * @param options 选项表
     * @return 不可变选项表
     */
    private static Map<String, String> copyOptions(Map<String, String> options) {
        if (options == null) {
            throw new IllegalArgumentException("CHOICE 类型必须提供 options");
        }
        if (options.isEmpty()) {
            throw new IllegalArgumentException("options 不能为空");
        }
        if (options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("options 最多 " + MAX_OPTIONS + " 项，实际 " + options.size());
        }
        return Map.copyOf(options);
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
     * 拷贝并校验结论说明表。
     *
     * <p>键<b>必须</b>取自 {@link DecisionType#TRUE_LABEL} /
     * {@link DecisionType#FALSE_LABEL}。这里刻意不接受自定义键：{@code criteria}
     * 的角色是「给 true / false 两端配说明文字」，而不是重新定义答案标签。
     * 是 / 否型问题在协议层就是二值的，放任自定义键会让适配器产出的标签
     * 永远落在候选集外，错误要等到执行期交叉校验才暴露，且难以定位。
     * 允许只提供其中一端的说明。</p>
     *
     * @param criteria 结论说明表，可为 null
     * @return 不可变结论说明表，或 null
     * @throws IllegalArgumentException 键不是 true / false 时
     */
    private static Map<String, String> copyCriteria(Map<String, String> criteria) {
        if (criteria == null) {
            return null;
        }
        for (String key : criteria.keySet()) {
            if (!DecisionType.TRUE_LABEL.equals(key) && !DecisionType.FALSE_LABEL.equals(key)) {
                throw new IllegalArgumentException("NOUL 的 criteria 键只能是 "
                        + DecisionType.TRUE_LABEL + " / " + DecisionType.FALSE_LABEL + "，实际: " + key);
            }
        }
        return Map.copyOf(criteria);
    }

    /**
     * 拒绝与 {@code type} 不匹配的选项表。
     *
     * @param type    当前问题类型，用于异常消息
     * @param options 选项表
     */
    private static void rejectOptions(DecisionType type, Map<String, String> options) {
        if (options != null) {
            throw new IllegalArgumentException(type + " 类型不应携带 options");
        }
    }

    /**
     * 拒绝与 {@code type} 不匹配的等级列表。
     *
     * @param type   当前问题类型，用于异常消息
     * @param levels 等级列表
     */
    private static void rejectLevels(DecisionType type, List<String> levels) {
        if (levels != null) {
            throw new IllegalArgumentException(type + " 类型不应携带 levels");
        }
    }

    /**
     * 拒绝与 {@code type} 不匹配的结论说明表。
     *
     * @param type     当前问题类型，用于异常消息
     * @param criteria 结论说明表
     */
    private static void rejectCriteria(DecisionType type, Map<String, String> criteria) {
        if (criteria != null) {
            throw new IllegalArgumentException(type + " 类型不应携带 criteria");
        }
    }
}
