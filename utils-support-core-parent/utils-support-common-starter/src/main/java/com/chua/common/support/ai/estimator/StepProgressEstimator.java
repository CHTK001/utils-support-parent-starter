package com.chua.common.support.ai.estimator;

import com.chua.common.support.ai.GenerateProgressEstimator;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 按固定步长估算进度。
 *
 * <p>把整个生成过程切成固定步数，每过一步加一档，走完步数即封顶 99 等真实完成。
 * 与时间曲线相比，它不假设「多久该好」，只表达「已经推进了几格」，
 * 因而在耗时方差很大、或用户更在意「有没有在动」而不是「还剩几成」时更合适。</p>
 *
 * <p>步长由 {@link #step} 决定（默认 20 步，每步约 5%）。步数取小值的另一层考虑：
 * 前端每秒轮询一次，步长过细时相邻两次采样看到的常常是同一档，反而显得没动。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("step")
public class StepProgressEstimator implements GenerateProgressEstimator {

    /**
     * 估算上限：估算永不返回 100
     */
    private static final int ESTIMATED_MAX = 99;

    /**
     * 总步数
     */
    private static final int TOTAL_STEPS = 20;

    /**
     * 每步的时长（毫秒）
     */
    private static final long STEP_MILLIS = 1000L;

    @Override
    public int estimate(Context context) {
        long elapsed = Math.max(0L, context.elapsedMillis());
        long steps = elapsed / STEP_MILLIS;
        int span = ESTIMATED_MAX - 1;
        int value = 1 + (int) Math.min(span, steps * span / TOTAL_STEPS);
        return Math.max(1, Math.min(ESTIMATED_MAX, value));
    }

    /**
     * 本实现的步长（毫秒）。
     *
     * @return 每步时长
     */
    public long step() {
        return STEP_MILLIS;
    }

    @Override
    public int order() {
        return -100;
    }
}
