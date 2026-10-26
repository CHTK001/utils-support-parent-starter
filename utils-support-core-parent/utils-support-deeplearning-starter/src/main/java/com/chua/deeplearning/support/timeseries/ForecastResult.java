package com.chua.deeplearning.support.timeseries;

import java.util.Arrays;
import java.util.Objects;

/**
 * 时间序列预测结果。
 *
 * <p><b>示例：接上"未来 24 小时气温"请求的返回结果</b></p>
 * <pre>{@code
 * ForecastResult r = forecaster.forecast(req);   // req = (hourlyTemps, 24, [0.1,0.5,0.9])
 *
 * r.horizon();     // 24
 * r.point();       // 长度 24：未来 24 小时逐小时点预测气温（℃）
 * r.quantiles();   // {0.1, 0.5, 0.9}
 * double[][] fc = r.forecast();
 * fc[0];           // 长度 24：10% 下界（较冷的可能）
 * fc[1];           // 长度 24：50% 中位（≈ point）
 * fc[2];           // 长度 24：90% 上界（较热的可能）
 * // 例：第 1 小时 [fc[0][0] .. fc[1][0] .. fc[2][0]] ≈ [7.5, 8.2, 8.9]℃
 * }</pre>
 *
 * @param horizon    预测步长
 * @param point      点预测数组，长度 = horizon
 * @param quantiles  目标分位数数组，长度 = q（可为 null，表示未请求分位数）
 * @param forecast   分位数预测，外层 = quantiles.length，内层 = horizon；quantiles 为 null 时为 null
 * @author CH
 * @since 4.0.0.42
 */
public final class ForecastResult {

    /** 预测步长。 */
    private final int horizon;
    /** 点预测数组（深拷贝，长度 = horizon）。 */
    private final double[] point;
    /** 目标分位数数组（深拷贝，可为 null）。 */
    private final double[] quantiles;
    /** 分位数预测（外层 = quantiles.length，内层 = horizon；quantiles 为 null 时为 null）。 */
    private final double[][] forecast;

    /**
     * 构造结果：对 point / quantiles / forecast 做深拷贝，防止外部修改。
     *
     * @param horizon   预测步长
     * @param point     点预测（长度必须等于 horizon，否则抛出 IllegalArgumentException）
     * @param quantiles 分位数数组，可为 null
     * @param forecast  分位数预测，可为 null
     */
    public ForecastResult(int horizon, double[] point, double[] quantiles, double[][] forecast) {
        Objects.requireNonNull(point, "point");
        if (horizon != point.length) {
            throw new IllegalArgumentException(
                    "horizon (" + horizon + ") 与 point.length (" + point.length + ") 不一致");
        }
        this.horizon = horizon;
        this.point = Arrays.copyOf(point, point.length);
        if (quantiles != null) {
            this.quantiles = Arrays.copyOf(quantiles, quantiles.length);
            this.forecast = forecast == null ? null : Arrays.stream(forecast)
                    .map(r -> Arrays.copyOf(r, r.length))
                    .toArray(double[][]::new);
        } else {
            this.quantiles = null;
            this.forecast = null;
        }
    }

    /** @return 预测步长。 */
    public int horizon() {
        return horizon;
    }

    /** @return 点预测数组（长度 = horizon）。 */
    public double[] point() {
        return point;
    }

    /** @return 目标分位数数组（可为 null）。 */
    public double[] quantiles() {
        return quantiles;
    }

    /** @return 分位数预测（可为 null，外层 = quantiles.length，内层 = horizon）。 */
    public double[][] forecast() {
        return forecast;
    }
}
