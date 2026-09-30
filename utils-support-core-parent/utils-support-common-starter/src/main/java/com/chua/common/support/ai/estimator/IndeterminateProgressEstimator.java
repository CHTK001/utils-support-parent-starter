package com.chua.common.support.ai.estimator;

import com.chua.common.support.ai.GenerateProgressEstimator;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 不确定型估算：快速爬到 99 后一直卡住等真实完成。
 *
 * <p>这是最保守的一种。它的立场是：既然完全不知道要多久，就不要用一个持续变化的
 * 百分比去暗示「快好了」——那会让用户按错误预期等待。做法是尽早把进度推到 99，
 * 之后不再变化，靠界面上的「已用时」表达「它还在跑」，直到真实完成把 100 写上。</p>
 *
 * <p>换句话说，它把「进度」降级成一个状态标记：99 等于「仍在进行」。这与
 * {@link TimeBasedProgressEstimator} 的选择相反，两者适用于不同偏好，
 * 由调用方通过 {@code order()} 选型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("indeterminate")
public class IndeterminateProgressEstimator implements GenerateProgressEstimator {

    /**
     * 停住的位置：卡在 99 等真实完成
     */
    private static final int HOLD_VALUE = 99;

    /**
     * 爬到 99 所需的耗时（毫秒）
     */
    private static final long RAMP_MILLIS = 5_000L;

    @Override
    public int estimate(Context context) {
        long elapsed = Math.max(0L, context.elapsedMillis());
        if (elapsed >= RAMP_MILLIS) {
            return HOLD_VALUE;
        }
        // 起步就给出接近一半的值：既然不表达「还剩多少」，就先表达「已开始」
        double ratio = (double) elapsed / RAMP_MILLIS;
        return 1 + (int) Math.round((HOLD_VALUE - 1) * ratio);
    }

    /**
     * 爬到 99 所需的耗时（毫秒）。
     *
     * @return 爬升时长
     */
    public long rampMillis() {
        return RAMP_MILLIS;
    }

    @Override
    public int order() {
        return -400;
    }
}
