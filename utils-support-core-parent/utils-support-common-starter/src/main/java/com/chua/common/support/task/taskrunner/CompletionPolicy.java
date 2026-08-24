package com.chua.common.support.task.taskrunner;

/**
 * 完成策略 — 定义"多少任务成功/失败才算整体成功"的判定规则。
 *
 * <p>策略按层评估：每一层并行批次执行完毕后，统计成功数与失败数，
 * 由 {@link #evaluate(int, int, int)} 判定该层是否达标；任一层不达标即整体失败。</p>
 *
 * <p>五种内置模式覆盖全部常见场景：</p>
 * <ul>
 *   <li>{@link #allSuccess()} — 全成功才成功（等价于失败 1 个即失败）</li>
 *   <li>{@link #anySuccess()} — 成功 1 个即成功（等价于全部失败才失败）</li>
 *   <li>{@link #successAtLeast(int)} — 成功数 ≥ N 才算成功</li>
 *   <li>{@link #failAtLeast(int)} — 失败数 ≥ N 即判失败</li>
 *   <li>{@link #successRate(double)} — 成功率 ≥ 比率才算成功，如 {@code successRate(0.8)}</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * TaskRunner.of("demo")
 *         .policy(CompletionPolicy.successRate(0.8))   // 80% 成功即整体通过
 *         .task("a", ctx -> callA())
 *         .task("b", ctx -> callB())
 *         .execute(input);
 * }</pre>
 *
 * <p>实例为不可变 record，可安全跨线程共享。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public record CompletionPolicy(Mode mode, int n, double rate) {

    /**
     * 策略模式枚举。
     */
    public enum Mode {
        /**
         * 全成功才成功。
         */
        ALL_SUCCESS,

        /**
         * 任一成功即成功。
         */
        ANY_SUCCESS,

        /**
         * 成功数达到阈值 N 才成功。
         */
        SUCCESS_AT_LEAST,

        /**
         * 失败数达到阈值 N 即失败。
         */
        FAIL_AT_LEAST,

        /**
         * 成功率达到比率 rate 才成功。
         */
        SUCCESS_RATE
    }

    /**
     * 创建完成策略（内部工厂入口）。
     *
     * @param mode 策略模式
     * @param n    阈值参数（仅阈值型模式使用，其余传 0）
     * @param rate 成功率参数（仅成功率模式使用，其余传 0）
     */
    private CompletionPolicy {
    }

    /**
     * 全成功策略：所有任务成功才算整体成功，任一失败即整体失败。
     *
     * @return 完成策略
     */
    public static CompletionPolicy allSuccess() {
        return new CompletionPolicy(Mode.ALL_SUCCESS, 0, 0);
    }

    /**
     * 任一成功策略：至少一个任务成功即整体成功，全部失败才判整体失败。
     *
     * @return 完成策略
     */
    public static CompletionPolicy anySuccess() {
        return new CompletionPolicy(Mode.ANY_SUCCESS, 1, 0);
    }

    /**
     * 成功阈值策略：成功数 ≥ n 才算整体成功。
     *
     * @param n 要求的最小成功数，必须 ≥ 1
     * @return 完成策略
     * @throws IllegalArgumentException 当 n &lt; 1 时
     */
    public static CompletionPolicy successAtLeast(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("成功阈值必须 >= 1, got: " + n);
        }
        return new CompletionPolicy(Mode.SUCCESS_AT_LEAST, n, 0);
    }

    /**
     * 失败阈值策略：失败数 ≥ n 即判整体失败。
     *
     * @param n 允许的最大容忍边界（失败数达到 n 立即判败），必须 ≥ 1
     * @return 完成策略
     * @throws IllegalArgumentException 当 n &lt; 1 时
     */
    public static CompletionPolicy failAtLeast(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("失败阈值必须 >= 1, got: " + n);
        }
        return new CompletionPolicy(Mode.FAIL_AT_LEAST, n, 0);
    }

    /**
     * 成功率策略：成功率 ≥ rate 才算整体成功。
     *
     * <p>判定基于整数运算避免浮点误差：成功数 ≥ ceil(rate × 总数) 即达标，
     * 例如总数 10、rate=0.8 时需要 8 个成功；总数 3、rate=0.8 时需要 ceil(2.4)=3 个成功。</p>
     *
     * @param rate 成功率阈值，取值区间 (0, 1]
     * @return 完成策略
     * @throws IllegalArgumentException 当 rate 不在 (0, 1] 区间时
     */
    public static CompletionPolicy successRate(double rate) {
        if (rate <= 0 || rate > 1) {
            throw new IllegalArgumentException("成功率必须在 (0, 1] 区间, got: " + rate);
        }
        return new CompletionPolicy(Mode.SUCCESS_RATE, 0, rate);
    }

    /**
     * 评估一层执行结果是否达标。
     *
     * <p>SKIPPED（被跳过）的节点不计入 total 分母。</p>
     *
     * @param successCount 本层成功节点数
     * @param failedCount  本层失败节点数
     * @param totalCount   本层实际参与执行的节点数（成功 + 失败）
     * @return true 表示本层达标
     */
    public boolean evaluate(int successCount, int failedCount, int totalCount) {
        return switch (mode) {
            case ALL_SUCCESS -> failedCount == 0 && successCount == totalCount;
            case ANY_SUCCESS -> successCount >= 1;
            case SUCCESS_AT_LEAST -> successCount >= n;
            case FAIL_AT_LEAST -> failedCount < n;
            case SUCCESS_RATE -> successCount >= requiredSuccessCount(totalCount);
        };
    }

    /**
     * 计算达标所需的最少成功数（供调度器提前终止判断使用）。
     *
     * @param totalCount 参与执行的节点总数
     * @return 最少成功数
     */
    public int requiredSuccessCount(int totalCount) {
        if (mode == Mode.SUCCESS_RATE) {
            // 整数上取整：ceil(rate * total)，避免浮点比较误差
            var exact = rate * totalCount;
            var floor = (int) exact;
            return (exact > floor) ? floor + 1 : floor;
        }
        return switch (mode) {
            case ALL_SUCCESS -> totalCount;
            case ANY_SUCCESS, SUCCESS_AT_LEAST -> Math.min(n, totalCount);
            default -> Integer.MAX_VALUE;
        };
    }

    /**
     * 计算允许的最大失败数（供调度器提前终止判断使用）。
     *
     * @param totalCount 参与执行的节点总数
     * @return 最大允许失败数，超过即不可能达标
     */
    public int allowedFailCount(int totalCount) {
        return switch (mode) {
            case FAIL_AT_LEAST -> n - 1;
            case SUCCESS_RATE -> totalCount - requiredSuccessCount(totalCount);
            case ALL_SUCCESS -> 0;
            case ANY_SUCCESS -> totalCount - 1;
            case SUCCESS_AT_LEAST -> totalCount - Math.min(n, totalCount);
        };
    }
}
