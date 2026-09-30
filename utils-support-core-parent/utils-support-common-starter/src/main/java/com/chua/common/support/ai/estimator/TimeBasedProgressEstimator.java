package com.chua.common.support.ai.estimator;

import com.chua.common.support.ai.GenerateProgressEstimator;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 按耗时估算进度（默认实现）。
 *
 * <p><b>曲线为什么是指数渐近而不是线性。</b>线性推进会在预期耗时到点时正好走到 99，
 * 之后每多等一秒都显得「已经该好了却还没好」；而生成耗时的分布是长尾的
 * （偶尔慢一倍很常见），线性会让这种正常波动看起来像卡住。
 * 指数渐近一开始走得快、后段越来越慢，长尾时仍然在动，观感更接近真实。</p>
 *
 * <p><b>为什么封顶 99。</b>估算永远不能声称完成。一旦显示 100%，用户会认为任务已好，
 * 而实际结果可能还要几秒甚至失败；把 100 留给真实完成事件，语义才不含糊。</p>
 *
 * <p><b>预期耗时怎么定。</b>按类型给经验值：图像单张约 15 秒，视频按秒数折算。
 * 这只是「曲线的形状参数」，估偏了也只影响推进快慢，不影响正确性。
 * 需要更准的实现（例如按历史耗时学习）可以另写一个 {@code @Spi} 实现并给更高
 * {@code @SpiOrder}，无需改动调用方。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("time")
public class TimeBasedProgressEstimator implements GenerateProgressEstimator {

    /**
     * 估算上限：估算永远不返回 100，100 只由真实完成事件写入
     */
    private static final int ESTIMATED_MAX = 99;

    /**
     * 图像生成的预期耗时基准（毫秒）
     */
    private static final long IMAGE_EXPECTED_MILLIS = 15_000L;

    /**
     * 视频生成的基础预期耗时（毫秒），实际再按时长叠加
     */
    private static final long VIDEO_BASE_MILLIS = 20_000L;

    /**
     * 视频每秒钟时长折算的额外预期耗时（毫秒）
     */
    private static final long VIDEO_PER_SECOND_MILLIS = 8_000L;

    /**
     * 视频按秒数折算时的兜底秒数
     */
    private static final int VIDEO_DEFAULT_SECONDS = 5;

    @Override
    public int estimate(Context context) {
        long expected = expectedMillis(context);
        long elapsed = Math.max(0L, context.elapsedMillis());
        // 指数渐近：1 + 98 * (1 - e^(-elapsed/expected))
        // 起步就有 1%（不是 0，0 与「还没开始」混淆），之后单调不减并渐近 99
        double ratio = 1.0D - Math.exp(-(double) elapsed / (double) expected);
        int estimated = 1 + (int) Math.round((ESTIMATED_MAX - 1) * ratio);
        return Math.max(1, Math.min(ESTIMATED_MAX, estimated));
    }

    @Override
    public int order() {
        // 内置实现里优先级最高：本类是「默认实现」，加入更多实现时不应因此被换掉。
        // 自定义实现只要给更大的 order 就能盖过它，或由调用方按 @Spi 名显式指定。
        return 0;
    }

    /**
     * 由上下文推出预期耗时。
     *
     * @param context 估算上下文
     * @return 预期耗时（毫秒），至少 1 秒
     */
    private long expectedMillis(Context context) {
        String type = context.type() == null ? "" : context.type();
        if ("video".equalsIgnoreCase(type)) {
            int seconds = context.durationSeconds() == null
                    ? VIDEO_DEFAULT_SECONDS : Math.max(1, context.durationSeconds());
            return VIDEO_BASE_MILLIS + VIDEO_PER_SECOND_MILLIS * seconds;
        }
        // 多张图在多数实现里是并行的，故不按张数线性放大，只做轻微上浮
        int count = context.count() == null ? 1 : Math.max(1, context.count());
        long base = IMAGE_EXPECTED_MILLIS;
        return Math.max(1000L, count > 1 ? base + base / 4 : base);
    }
}
