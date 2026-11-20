package com.chua.common.support.condition;

/**
 * 分支流程中的一步记录，供 {@code print()} / {@code trace()} 渲染。
 *
 * @author CH
 * @since 4.0.0.53
 */
final class Step {

    /**
     * 该步的执行结果状态。
     */
    enum State {

        /**
         * 条件成立 / 分支命中。
         */
        HIT,

        /**
         * 条件已判定但不成立。
         */
        MISS,

        /**
         * 动作已执行。
         */
        RUN,

        /**
         * 前面分支已命中或条件不成立，本步未执行。
         */
        SKIP;

        /**
         * 渲染用展示文本。
         *
         * @return 展示文本
         */
        String display() {
            switch (this) {
                case HIT:
                    return "✓ hit";
                case MISS:
                    return "✗ miss";
                case RUN:
                    return "✓ run";
                case SKIP:
                    return "- skip";
                default:
                    return name();
            }
        }
    }

    /**
     * 步骤类型（if / elseIf / orElse / tryGet …）。
     */
    private final String kind;

    /**
     * 业务标签，可为 null。
     */
    private final String label;

    /**
     * 执行状态。
     */
    private final State state;

    /**
     * 附加信息（产出值或异常摘要），可为 null。
     */
    private final String detail;

    Step(String kind, String label, State state, String detail) {
        this.kind = kind;
        this.label = label;
        this.state = state;
        this.detail = detail;
    }

    /**
     * 步骤类型。
     *
     * @return 类型名
     */
    String kind() {
        return kind;
    }

    /**
     * 业务标签。
     *
     * @return 标签，可能为 null
     */
    String label() {
        return label;
    }

    /**
     * 执行状态。
     *
     * @return 状态
     */
    State state() {
        return state;
    }

    /**
     * 附加信息。
     *
     * @return 附加信息，可能为 null
     */
    String detail() {
        return detail;
    }
}
