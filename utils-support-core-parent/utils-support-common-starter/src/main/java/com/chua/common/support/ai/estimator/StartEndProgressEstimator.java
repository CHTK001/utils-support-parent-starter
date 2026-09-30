package com.chua.common.support.ai.estimator;

import com.chua.common.support.ai.GenerateProgressEstimator;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 只标「开始」与「接近结束」两档的估算。
 *
 * <p>适用于完全无法判断耗时的场景：与其画一条会被实际耗时否定的曲线，
 * 不如只诚实地表达两件事——「已经开始了」与「快到了」。
 * 中间段停在一个固定值不动，比给出一个持续走偏的百分比更不容易误导。</p>
 *
 * <p>何时算「快到了」由 {@link #nearEndMillis} 决定（默认 20 秒）。
 * 若任务比它更早完成，会直接从起始档跳到 100，不会出现倒退（估算单调不减）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("start-end")
public class StartEndProgressEstimator implements GenerateProgressEstimator {

    /**
     * 起始档：任务已开始
     */
    private static final int START_VALUE = 10;

    /**
     * 末段档：接近完成
     */
    private static final int NEAR_END_VALUE = 90;

    /**
     * 判定「接近结束」的耗时阈值（毫秒）
     */
    private static final long NEAR_END_MILLIS = 20_000L;

    @Override
    public int estimate(Context context) {
        long elapsed = Math.max(0L, context.elapsedMillis());
        return elapsed >= NEAR_END_MILLIS ? NEAR_END_VALUE : START_VALUE;
    }

    /**
     * 判定「接近结束」的耗时阈值（毫秒）。
     *
     * @return 阈值
     */
    public long nearEndMillis() {
        return NEAR_END_MILLIS;
    }

    @Override
    public int order() {
        return -300;
    }
}
