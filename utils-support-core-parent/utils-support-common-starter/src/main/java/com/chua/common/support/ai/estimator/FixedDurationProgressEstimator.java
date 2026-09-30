package com.chua.common.support.ai.estimator;

import com.chua.common.support.ai.GenerateProgressEstimator;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 按「设定时长」线性估算进度。
 *
 * <p>与 {@link TimeBasedProgressEstimator} 的区别是刻意不要渐近曲线：
 * 这里假设调用方（或运维）对某类任务的耗时心里有数，于是按设定值走直线，
 * 到点即 99 并停住等真实完成。</p>
 *
 * <p>设定时长取 {@link #expectedMillis}：默认图像 30 秒、视频 180 秒。
 * 需要固定值时可以另写一个实现或直接调整常量——曲线形状参数估偏只影响推进快慢，
 * 不影响正确性（100 仍由真实完成写入）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("fixed")
public class FixedDurationProgressEstimator implements GenerateProgressEstimator {

    /**
     * 估算上限：估算永不返回 100
     */
    private static final int ESTIMATED_MAX = 99;

    /**
     * 图像的默认设定时长（毫秒）
     */
    private static final long IMAGE_EXPECTED_MILLIS = 30_000L;

    /**
     * 视频的默认设定时长（毫秒）
     */
    private static final long VIDEO_EXPECTED_MILLIS = 180_000L;

    @Override
    public int estimate(Context context) {
        long expected = expectedMillis(context);
        long elapsed = Math.max(0L, context.elapsedMillis());
        double ratio = Math.min(1.0D, (double) elapsed / (double) expected);
        int value = 1 + (int) Math.round((ESTIMATED_MAX - 1) * ratio);
        return Math.max(1, Math.min(ESTIMATED_MAX, value));
    }

    /**
     * 设定时长。
     *
     * @param context 估算上下文
     * @return 设定时长（毫秒）
     */
    protected long expectedMillis(Context context) {
        String type = context.type() == null ? "" : context.type();
        return "video".equalsIgnoreCase(type) ? VIDEO_EXPECTED_MILLIS : IMAGE_EXPECTED_MILLIS;
    }

    @Override
    public int order() {
        return -200;
    }
}
