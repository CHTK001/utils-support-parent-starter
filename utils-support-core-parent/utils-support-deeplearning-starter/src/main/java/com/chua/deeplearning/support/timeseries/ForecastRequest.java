package com.chua.deeplearning.support.timeseries;

/**
 * 时间序列预测请求。
 *
 * <p><b>示例：预测某地未来 24 小时气温</b></p>
 * <pre>{@code
 * // 最近 168 小时逐小时气温（℃），最后一个是最新观测（等间隔、时间升序）
 * double[] hourlyTemps = { ... , 12.4, 11.8, 11.1, 10.5, 9.9};
 *
 * // horizon=24 预测未来 24 小时；quantiles 要 10% / 50% / 90% 三档区间
 * ForecastRequest req = new ForecastRequest(hourlyTemps, 24, new double[]{0.1, 0.5, 0.9});
 * }</pre>
 *
 * <p>context 不足模型上下文长度（如 512 步）会自动左补齐，超出则取最近的窗口；
 * quantiles 传 {@code null} 则只算点预测。</p>
 *
 * @param context 历史观测序列（等间隔，最近一个观测为最后一个元素）
 * @param horizon 预测步长
 * @param quantiles 目标分位数（升序，如 [0.1, 0.5, 0.9]）；为空时仅输出点预测
 * @author CH
 * @since 4.0.0.42
 */
public record ForecastRequest(double[] context, int horizon, double[] quantiles) {

    /**
     * 校验上下文与预测参数。
     *
     * @throws IllegalArgumentException 参数不合法时抛出
     */
    public ForecastRequest {
        if (context == null || context.length == 0) {
            throw new IllegalArgumentException("context 不能为空");
        }
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon 必须 >= 1");
        }
        if (quantiles != null) {
            for (int i = 0; i < quantiles.length; i++) {
                if (quantiles[i] < 0 || quantiles[i] > 1) {
                    throw new IllegalArgumentException("quantile 必须在 [0,1] 内: " + quantiles[i]);
                }
                if (i > 0 && quantiles[i] < quantiles[i - 1]) {
                    throw new IllegalArgumentException("quantiles 必须升序: " + quantiles);
                }
            }
        }
    }
}
