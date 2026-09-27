package com.chua.common.support.ai.decision;

/**
 * 概率决策的问题类型。
 *
 * <p>这是规则条件层的通用抽象，<b>不绑定任何具体实现</b>。当前三种形态覆盖了
 * 概率决策的全部实际用法：</p>
 * <ul>
 *   <li>{@link #NOUL} —— 是 / 否型判断，答案是「成立」的概率</li>
 *   <li>{@link #CHOICE} —— 从一组给定选项中选一个</li>
 *   <li>{@link #SCORE} —— 从一组有序等级中选一个（可排序的 CHOICE）</li>
 * </ul>
 *
 * <p><b>为什么不做成开放集合</b>：闭集让 {@code when} 阈值判断在编译期即可确定
 * 取值形态（布尔概率 / 枚举项 / 有序等级），避免规则作者写出无法静态推断的比较。
 * 若某类决策无法归入这三种，实现方应<b>直接实现 {@link DecisionProvider}</b>
 * 并自定义 {@link Decision} 的 {@code probabilities} 键，而不是往本枚举里塞特例。</p>
 *
 * <p><b>实现方映射约定</b>：实现 {@link DecisionProvider} 时，
 * 各外部服务的问题类型按语义归入这三类即可（布尔型判断 → {@link #NOUL}，
 * 无序单选 → {@link #CHOICE}，有序分级 → {@link #SCORE}）。
 * 若外部服务用数字下标而非标签表达分级，由实现方负责映射成本枚举的等级标签，
 * SPI 不约束外部协议形态。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum DecisionType {

    /**
     * 是 / 否型判断。
     *
     * <p>答案标签只可能是 {@link #TRUE_LABEL} 或 {@link #FALSE_LABEL}，
     * {@link Decision#confidence()} 即「成立」的概率。</p>
     */
    NOUL,

    /**
     * 多选一。
     *
     * <p>答案标签必须是 {@link DecisionQuery#options()} 中的某个键，
     * 因此可直接与字符串常量做等值比较。</p>
     */
    CHOICE,

    /**
     * 有序分级。
     *
     * <p>答案标签必须是 {@link DecisionQuery#levels()} 中的某一级。
     * 与 {@link #CHOICE} 的区别是等级<b>有业务先后</b>（低 → 中 → 高），
     * 规则里可据此做「不低于中」这类区间判断。</p>
     */
    SCORE;

    /**
     * {@link #NOUL} 的「成立」标签
     */
    public static final String TRUE_LABEL = "true";

    /**
     * {@link #NOUL} 的「不成立」标签
     */
    public static final String FALSE_LABEL = "false";

    /**
     * 是否为是 / 否型
     *
     * @return true 表示 {@link #NOUL}
     */
    public boolean isNoul() {
        return this == NOUL;
    }

    /**
     * 是否为多选一型
     *
     * @return true 表示 {@link #CHOICE}
     */
    public boolean isChoice() {
        return this == CHOICE;
    }

    /**
     * 是否为有序分级型
     *
     * @return true 表示 {@link #SCORE}
     */
    public boolean isScore() {
        return this == SCORE;
    }
}
