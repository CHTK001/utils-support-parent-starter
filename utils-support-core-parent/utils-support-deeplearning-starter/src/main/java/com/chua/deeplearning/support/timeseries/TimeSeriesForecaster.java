package com.chua.deeplearning.support.timeseries;

/**
 * 单变量时间序列预测能力。
 *
 * <p>接收历史观测序列，输出固定预测步长内的点预测值与分位数区间。
 * 实现类由 ONNX 引擎等翻译器通过能力接口注册。</p>
 *
 * <p><b>示例：预测某地未来 6 小时气温</b>（数值为示意，非真实模型输出）</p>
 * <pre>{@code
 * // 最近 24 小时逐小时气温（单位：℃），最后一个元素是最新观测
 * double[] context = {8.1, 7.6, 7.2, 6.9, 6.8, 7.1, 8.3, 10.2,
 *                     12.6, 15.1, 17.4, 19.2, 20.5, 21.0, 20.6, 19.4,
 *                     17.8, 16.1, 14.3, 12.7, 11.4, 10.3, 9.4, 8.7};
 *
 * // 1) 只要点预测：未来 6 小时气温（℃）
 * double[] point = forecaster.pointForecast(context, 6);
 * // -> point ≈ {8.2, 7.6, 7.1, 6.8, 6.9, 7.4}
 *
 * // 2) 要不确定区间：未来 6 小时 10% / 50% / 90% 分位（℃）
 * double[][] band = forecaster.quantileForecast(context, 6, new double[]{0.1, 0.5, 0.9});
 * // -> band[0] = 10% 下界, band[1] = 50% 中位（≈点预测）, band[2] = 90% 上界
 * //    例：第 1 小时 [7.5, 8.2, 8.9]℃，越往后区间越宽（不确定性随时间增大）
 * }</pre>
 *
 * <p>说明：context 等间隔、按时间升序；不足模型上下文长度会自动左补齐，
 * 超出则取最近的窗口。返回数组内层均按时间升序（第 0 个 = 下一时刻）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TimeSeriesForecaster {

    /**
     * 执行预测：基于历史观测序列输出点预测与可选分位数区间。
     *
     * @param request 预测请求（上下文 + 预测步长 + 目标分位数）
     * @return 预测结果（点预测数组 + 各分位数预测数组）
     */
    ForecastResult forecast(ForecastRequest request);

    /**
     * 点预测便捷方法：基于历史序列预测未来 horizon 步的值。
     *
     * <p>示例（气温）：{@code double[] next6h = forecaster.pointForecast(last24hTemps, 6);}
     * 返回未来 6 小时逐小时气温（℃），按时间升序。</p>
     *
     * @param context 历史观测序列（等间隔，最近一个观测为最后一个元素）
     * @param horizon 预测步长
     * @return 点预测数组，长度为 horizon，按时间升序排列
     */
    default double[] pointForecast(double[] context, int horizon) {
        return forecast(new ForecastRequest(context, horizon, null)).point();
    }

    /**
     * 分位数预测便捷方法：基于历史序列预测未来各分位数。
     *
     * <p>示例（气温区间）：{@code double[][] band = forecaster.quantileForecast(
     * last24hTemps, 6, new double[]{0.1, 0.5, 0.9});}
     * {@code band[0]} 是 10% 下界、{@code band[1]} 是 50% 中位、{@code band[2]} 是 90% 上界，
     * 每个长度为 horizon（℃），区间随预测步长变宽。</p>
     *
     * @param context 历史观测序列
     * @param horizon 预测步长
     * @param quantiles 目标分位数（如 [0.1, 0.5, 0.9]），升序，每个值在 [0,1] 内
     * @return 分位数数组，外层长度为 quantiles.length，内层长度为 horizon
     */
    default double[][] quantileForecast(double[] context, int horizon, double[] quantiles) {
        return forecast(new ForecastRequest(context, horizon, quantiles)).forecast();
    }
}
