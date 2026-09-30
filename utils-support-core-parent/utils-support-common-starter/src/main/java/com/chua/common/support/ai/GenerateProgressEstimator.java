package com.chua.common.support.ai;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Comparator;
import java.util.List;

/**
 * 生成进度估算 SPI。
 *
 * <p><b>为什么需要它。</b>不少生成接口是一次阻塞调用：提交出去之后上游不给任何中间进度，
 * 直到返回结果才有下文。实测 Agnes 图像生成耗时约 13 秒，这 13 秒里后端能报的进度
 * 只有一个阶段值（「已发出请求」），前端看到的就是一个十秒不动的百分比，
 * 与「卡死」在观感上无法区分。</p>
 *
 * <p><b>口径。</b>本 SPI 只负责「在没有真实进度时给出一个合理的估算」，
 * 且估算值<b>封顶 99、永不返回 100</b>——100 由真实完成事件写入。
 * 这样既不会出现「显示 100% 但其实还在跑」，也不会让用户对着一个死数字发呆。
 * 真实进度一旦可用（例如上游回报），调用方应优先用它而不是估算值。</p>
 *
 * <p><b>扩展方式。</b>实现类用 {@code @Spi("名称")} 标注并由 {@link ServiceProvider}
 * 发现；同一批实现里 {@link #order()} 大的优先。选择逻辑见 {@link #estimateWith}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface GenerateProgressEstimator {

    /**
     * 估算进度上下文。
     *
     * <p>只带估算需要的输入，不含任务主键或用户信息——估算不该依赖它们的实现细节，
     * 也避免实现方顺手去改数据。</p>
     *
     * @param type            生成类型（image / video）
     * @param model           模型名，实现方可据此区分不同供应商的耗时特征
     * @param size            分辨率，如 1024x1024；实现方可用它粗略修正耗时
     * @param count           单次张数，视频恒为 1
     * @param durationSeconds 视频时长（秒），图像为 null；视频耗时与它强相关
     * @param elapsedMillis   从提交至今的毫秒数
     */
    record Context(String type, String model, String size, Integer count, Integer durationSeconds, long elapsedMillis) {
    }

    /**
     * 估算当前进度。
     *
     * <p>实现必须满足：同一任务的返回值随 elapsedMillis 单调不减、落在 1..99 之内。
     * 返回 100 是不允许的——那会与「真实完成」混淆。</p>
     *
     * @param context 估算上下文
     * @return 1..99 的估算进度
     */
    int estimate(Context context);

    /**
     * 是否受理该上下文。
     *
     * <p>不需要挑类型的实现直接沿用默认值即可；只为某一种类型提供曲线时才覆盖。</p>
     *
     * @param context 估算上下文
     * @return true 表示由本实现估算
     */
    default boolean supports(Context context) {
        return true;
    }

    /**
     * 同一批实现里的优先级，数值越大越优先被采用。
     *
     * @return 优先级，默认 0
     */
    default int order() {
        return 0;
    }

    /**
     * 按 SPI 选出最合适的实现并估算进度。
     *
     * <p>取所有实现中 {@code supports} 通过且 {@code order} 最大者；
     * 一个都没有时回落为按耗时线性推进，保证调用方永远拿得到一个值。</p>
     *
     * @param context 估算上下文
     * @return 1..99 的估算进度
     */
    static int estimateWith(Context context) {
        return estimateWith(context, null);
    }

    /**
     * 按指定实现名估算；名字为空时取 order 最大者。
     *
     * <p>新增多个实现后，「谁生效」必须显式可控：若只按 order 取最大，
     * 那么加入一个新实现就会静默改掉默认行为（实测那会把 12.3s 处的值从 49 变成 59）。
     * 故这里保留「按名选择」这条路径，默认实现的名字由调用方给出。</p>
     *
     * @param context   估算上下文
     * @param preferred 期望的实现名（{@code @Spi} 的值），为空则按 order 取最大
     * @return 1..99 的估算进度
     */
    static int estimateWith(Context context, String preferred) {
        if (context == null) {
            return 1;
        }
        try {
            ServiceProvider<GenerateProgressEstimator> provider = ServiceProvider.of(GenerateProgressEstimator.class);
            if (preferred != null && !preferred.isBlank()) {
                GenerateProgressEstimator named = provider.getExtension(preferred);
                if (named != null && named.supports(context)) {
                    return clamp(named.estimate(context));
                }
            }
            List<GenerateProgressEstimator> candidates = provider.collect().stream()
                    .filter(item -> item.supports(context))
                    .sorted(Comparator.comparingInt(GenerateProgressEstimator::order).reversed())
                    .toList();
            if (!candidates.isEmpty()) {
                return clamp(candidates.get(0).estimate(context));
            }
        } catch (Exception ignored) {
            // SPI 发现失败不该让任务挂掉：估算只是展示用的糖，回落即可
        }
        return clamp(fallback(context));
    }

    /**
     * 无任何实现时的兜底曲线：30 秒内线性推进到 99。
     *
     * @param context 估算上下文
     * @return 估算进度
     */
    private static int fallback(Context context) {
        long expected = 30_000L;
        double ratio = Math.min(1.0, (double) Math.max(0L, context.elapsedMillis()) / expected);
        return 1 + (int) Math.round(98 * ratio);
    }

    /**
     * 把估算值夹到 1..99。
     *
     * @param value 原始值
     * @return 夹取后的值
     */
    private static int clamp(int value) {
        return Math.max(1, Math.min(99, value));
    }
}
