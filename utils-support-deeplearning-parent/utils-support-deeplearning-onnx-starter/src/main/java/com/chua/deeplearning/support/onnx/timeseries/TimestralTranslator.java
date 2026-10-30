package com.chua.deeplearning.support.onnx.timeseries;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.timeseries.ForecastRequest;
import com.chua.deeplearning.support.timeseries.ForecastResult;
import com.chua.deeplearning.support.timeseries.TimeSeriesForecaster;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Timestral-5M 单变量时间序列预测翻译器（ONNX Runtime 实现）。
 *
 * <h2>模型说明</h2>
 * <p>包装 Mistral Timestral-5M ONNX 导出（onnx-community/Timestral-5M-ONNX）：</p>
 * <ul>
 *   <li>固定上下文 512 步（16 个 patch × 32），固定 horizon 32 步，输出 21 档分位数（0.01…0.99）。</li>
 *   <li>预处理与后处理严格对齐 {@code reference/prep.ts}：
 *       Welford 因果逐 patch loc/scale（float64 统计，float32 强转），
 *       asinh 归一化，missing / time_mask / variate_mask / positions，
 *       分位数反归一化 + cap + 对称平均 + 非负 clamp。</li>
 *   <li>输入 [x, -x] 双行（R=2）一次调用完成对称平均，无需两次推理。</li>
 * </ul>
 *
 * <h2>输入/输出</h2>
 * <p>输入：{@link ForecastRequest}（context 历史序列 + horizon + 目标分位数）；
 * 输出：{@link ForecastResult}（点预测 + 各目标分位数预测）。</p>
 *
 * <p><b>示例：预测某地未来 32 小时气温</b>（数值为示意）</p>
 * <pre>{@code
 * // 最近 512 小时逐小时气温（℃），等间隔、时间升序，最后一个是当前时刻
 * double[] temps = { ... , 10.6, 10.1, 9.7, 9.3};
 *
 * // 未来 32 小时的点预测 + 10%/90% 区间
 * double[] point = forecaster.pointForecast(temps, 32);          // 32 个气温值(℃)
 * double[][] q   = forecaster.quantileForecast(temps, 32, new double[]{0.1, 0.9});
 * // q[0] = 10% 下界, q[1] = 90% 上界，各 32 个值
 * }</pre>
 *
 * <p><b>内部数据通路</b>（以 512 步气温为例，行数 R=2 为 [x, -x] 双行）：</p>
 * <ul>
 *   <li>{@code scaled} [2,512]：asinh((气温-loc)/scale)，每 patch(32 步)各自 loc/scale</li>
 *   <li>{@code missing} [2,512]：NaN 缺失位为 1，其余为 0（气温全有时全 0）</li>
 *   <li>{@code time_mask} [2,1,17,17]：下三角因果掩码（17=16 上下文 patch + 1 预测 token）</li>
 *   <li>{@code variate_mask} [17,1,2,2]：每个 token 的 2×2 单位阵（单变量占位）</li>
 *   <li>{@code positions} [2,17]：相对首个有效 patch 的位置 0…16</li>
 *   <li>输出 {@code preds} [2,32,21] → 反归一化(sinh)→cap→双行对称平均→非负 clamp → 21 档分位数</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TimestralTranslator implements TimeSeriesForecaster {

    /** 因果上下文长度（步）。 */
    private static final int CONTEXT = 512;
    /** 因果统计分段数（16 段 × 32）。 */
    private static final int NP = CONTEXT / 32;
    /** 预测 horizon（步）。 */
    private static final int HORIZON = 32;
    /** 输出分位数个数（0.01 … 0.99）。 */
    private static final int NQ = 21;
    /** 分位数档（与 config.json quantile_knots 一致）。 */
    private static final double[] QUANTILE_LEVELS = {
            0.01, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45,
            0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 0.99
    };
    /** token 总数（16 上下文 + 1 预测）。 */
    private static final int TOKENS = NP + 1;
    /** float32 最小正规数的一半，对齐 prep.ts EPS32。 */
    private static final double EPS32 = 1.1920928955078125e-7;
    /** 最低 scale，防止 asinh 除零。 */
    private static final double MIN_SCALE = 1e-6;
    /** cap 倍数。 */
    private static final double CAP_K = 1e4;
    /** 默认内嵌资源路径（相对 classpath 根，对应 models jar 内的资源）。 */
    private static final String DEFAULT_RESOURCE = "timeseries/timestral/timestral-5m/model.onnx";

    private String modelPath;
    private OrtEnvironment ortEnv;
    private OrtSession session;
    private volatile boolean prepared = false;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 设置模型文件路径（仅供模型注册表在 SPI 实例化后注入使用）。
     *
     * @param modelPath 模型路径
     */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public ForecastResult forecast(ForecastRequest request) {
        lock.lock();
        try {
            ensurePrepared();
            try {
                return doForecast(request);
            } catch (ai.onnxruntime.OrtException e) {
                throw new RuntimeException("Timestral ONNX 推理失败: " + e.getMessage(), e);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 实际预测逻辑（抛出 {@code OrtException}，由 {@link #forecast} 捕获转为 {@code RuntimeException}）。 */
    private ForecastResult doForecast(ForecastRequest request) throws ai.onnxruntime.OrtException {
            double[] ctx = padContext(request.context());
            boolean[] obs = new boolean[CONTEXT];
            for (int i = 0; i < CONTEXT; i++) {
                obs[i] = Double.isFinite(ctx[i]);
            }

            // 双行 [x, -x]
            float[] scaled = new float[2 * CONTEXT];
            float[] missing = new float[2 * CONTEXT];
            double[] locs = new double[2];
            double[] scales = new double[2];
            double[] ctxMin = new double[2];
            double[] ctxMax = new double[2];
            boolean any = false;
            boolean nonneg = true;

            for (int r = 0; r < 2; r++) {
                double[] row = new double[CONTEXT];
                for (int i = 0; i < CONTEXT; i++) {
                    row[i] = obs[i] ? f32(r == 0 ? ctx[i] : -ctx[i]) : 0.0;
                }
                double[] s = scaleRow(row, obs, r * CONTEXT, scaled);
                locs[r] = s[0];
                scales[r] = s[1];
                double lo = Double.POSITIVE_INFINITY;
                double hi = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < CONTEXT; i++) {
                    missing[r * CONTEXT + i] = obs[i] ? 0.0f : 1.0f;
                    if (!obs[i]) {
    continue;
}
                    lo = Math.min(lo, row[i]);
                    hi = Math.max(hi, row[i]);
                    if (r == 0) {
                        any = true;
                        if (row[i] < 0) {
    nonneg = false;
}
                    }
                }
                ctxMin[r] = Double.isFinite(lo) ? lo : 0.0;
                ctxMax[r] = Double.isFinite(hi) ? hi : 0.0;
            }
            boolean nonNegative = any && nonneg;

            // 掩码与位置
            boolean[] obsPatch = new boolean[NP];
            for (int p = 0; p < NP; p++) {
                for (int i = p * 32; i < (p + 1) * 32; i++) {
                    if (obs[i]) {
                        obsPatch[p] = true; break;
                    }
                }
            }
            boolean[] keyValid = new boolean[TOKENS];
            int firstObs = 0;
            boolean foundFirst = false;
            for (int p = 0; p < NP; p++) {
                if (!foundFirst && obsPatch[p]) {
                    firstObs = p;
                    foundFirst = true;
                }
                keyValid[p] = foundFirst;
            }
            keyValid[NP] = true; // 预测 token

            byte[] timeMask = new byte[2 * TOKENS * TOKENS];
            long[] positions = new long[2 * TOKENS];
            for (int r = 0; r < 2; r++) {
                for (int q = 0; q < TOKENS; q++) {
                    positions[r * TOKENS + q] = (long) (q - firstObs);
                    for (int k = 0; k < TOKENS; k++) {
                        boolean v;
                        if (q < NP && k < NP) {
                            v = (k <= q && keyValid[k]) || q == k;
                        } else if (q < NP) {
                            v = false;
                        } else if (k < NP) {
                            v = keyValid[k];
                        } else {
                            v = true;
                        }
                        timeMask[(r * TOKENS + q) * TOKENS + k] = (byte) (v ? 1 : 0);
                    }
                }
            }
            byte[] varMask = new byte[TOKENS * 4];
            for (int s = 0; s < TOKENS; s++) {
                varMask[s * 4] = 1;
                varMask[s * 4 + 1] = 0;
                varMask[s * 4 + 2] = 0;
                varMask[s * 4 + 3] = 1;
            }

            // 构建 ONNX 输入
            // time_mask: [2, 1, 17, 17] — boolean 以 byte(0/1) 存储
            // variate_mask: [17, 1, 2, 2]

            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("scaled", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(scaled), new long[]{2, CONTEXT}));
            inputs.put("missing", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(missing), new long[]{2, CONTEXT}));
            inputs.put("time_mask", OnnxTensor.createTensor(ortEnv, ByteBuffer.wrap(timeMask),
                    new long[]{2, 1, TOKENS, TOKENS}, OnnxJavaType.BOOL));
            inputs.put("variate_mask", OnnxTensor.createTensor(ortEnv, ByteBuffer.wrap(varMask),
                    new long[]{TOKENS, 1, 2, 2}, OnnxJavaType.BOOL));
            inputs.put("positions", OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(positions),
                    new long[]{2, TOKENS}));

            float[] preds;
            try (OrtSession.Result results = session.run(inputs)) {
                Object val = results.get(0).getValue();
                preds = toFlatFloat3d(val);
            }
            for (OnnxTensor t : inputs.values()) {
                t.close();
            }

            // 后处理：双行对称平均 → 目标分位数
            double[][] quant = finishForecast(preds, locs, scales, ctxMin, ctxMax, nonNegative);
            double[] point = new double[HORIZON];
            int qMedian = NQ / 2; // 0.5 档
            for (int h = 0; h < HORIZON; h++) {
                point[h] = quant[h][qMedian];
            }

            double[] requested = request.quantiles();
            double[][] quantileOut = null;
            if (requested != null) {
                quantileOut = new double[requested.length][HORIZON];
                for (int qi = 0; qi < requested.length; qi++) {
                    int idx = nearestQuantileIndex(requested[qi]);
                    for (int h = 0; h < HORIZON; h++) {
                        quantileOut[qi][h] = quant[h][idx];
                    }
                }
            }
            return new ForecastResult(HORIZON, point, requested, quantileOut);
    }

    // ---------------------------------------------------------------- 预处理

    /** 补齐/截断至 512 步，左填充（与 prep.ts padded 一致：超出部分置 NaN）。 */
    private static double[] padContext(double[] raw) {
        double[] out = new double[CONTEXT];
        Arrays.fill(out, Double.NaN);
        int n = Math.min(raw.length, CONTEXT);
        for (int i = 0; i < n; i++) {
            out[CONTEXT - n + i] = raw[raw.length - n + i];
        }
        return out;
    }

    /**
     * Welford 因果逐 patch 统计 + asinh 归一化，填充 scaled 数组 offset 起始位置，
     * 返回最终 patch 的 [loc, scale]（用于后处理 denormalize）。
     *
     * @param row    当前行的观测值（obs[i]=false 时为 0）
     * @param obs    观测标志
     * @param offset scaled 数组中本行的起始位置
     * @param out    目标 scaled 数组
     * @return [loc, scale]（最后一个 patch 的值）
     */
    private static double[] scaleRow(double[] row, boolean[] obs, int offset, float[] out) {
        double cumW = 0;
        double cumX = 0;
        double m2 = 0;
        double prev = 0;
        double[] patchLoc = new double[NP];
        double[] patchScale = new double[NP];

        for (int p = 0; p < NP; p++) {
            int t0 = p * 32;
            int t1 = t0 + 32;
            double pW = 0, pX = 0, pM2 = 0;
            double pPrev = 0;
            boolean anyObs = false;
            for (int t = t0; t < t1; t++) {
                double w = obs[t] ? 1 : 0;
                double v = obs[t] ? row[t] : 0;
                if (obs[t]) {
    anyObs = true;
}
                pW += w;
                pX += w * v;
                double den = Math.max(pW, 1);
                double mean = pX / den;
                pM2 += (v - pPrev) * (v - mean) * w;
                pPrev = mean;
                // 全局累计
                cumW += w;
                cumX += w * v;
                double gDen = Math.max(cumW, 1);
                double gMean = cumX / gDen;
                m2 += (v - prev) * (v - gMean) * w;
                prev = gMean;
            }
            double pVar = pM2 / Math.max(pW - 1, 1);
            patchLoc[p] = f32(pX / Math.max(pW, 1));
            patchScale[p] = f32(Math.max(Math.sqrt(pVar), MIN_SCALE));

            double sc = Math.max(patchScale[p], EPS32);
            for (int k = 0; k < 32; k++) {
                int t = t0 + k;
                out[offset + t] = obs[t]
                        ? f32(asinh((f32(row[t]) - patchLoc[p]) / sc))
                        : 0.0f;
            }
        }
        int last = NP - 1;
        return new double[]{patchLoc[last], patchScale[last]};
    }

    /**
     * 后处理：双行对称平均 → 各档分位数（denormalize + cap + nonneg clamp）。
     *
     * @param preds        模型输出（[2, HORIZON, NQ] 展平）
     * @param locs         两行各自的 loc
     * @param scales       两行各自的 scale
     * @param ctxMin       两行上下文最小值
     * @param ctxMax       两行上下文最大值
     * @param nonNegative  原始序列是否全非负
     * @return [HORIZON][NQ] 各档分位数
     */
    private static double[][] finishForecast(float[] preds, double[] locs, double[] scales,
                                              double[] ctxMin, double[] ctxMax, boolean nonNegative) {
        // denormalize: raw_q[r][h][j] = f32(sinh(z) * scale[r] + loc[r])
        double[][][] raw = new double[2][HORIZON][NQ];
        for (int r = 0; r < 2; r++) {
            for (int h = 0; h < HORIZON; h++) {
                int base = (r * HORIZON + h) * NQ;
                for (int j = 0; j < NQ; j++) {
                    float z = preds[base + j];
                    if (Float.isNaN(z)) {
                        throw new IllegalStateException("Timestral 预测产生 NaN，输入序列退化");
                    }
                    double v = f32(f32(Math.sinh(z)) * scales[r] + locs[r]);
                    // cap: clamp to [ctxMin - cap, ctxMax + cap]
                    double cap = scales[r] * CAP_K;
                    v = Math.min(Math.max(v, ctxMin[r] - cap), ctxMax[r] + cap);
                    raw[r][h][j] = v;
                }
            }
        }

        // symmetric average: q[h][j] = 0.5 * (raw[0][h][j] - raw[1][h][NQ-1-j])
        double[][] q = new double[HORIZON][NQ];
        for (int h = 0; h < HORIZON; h++) {
            for (int j = 0; j < NQ; j++) {
                double v = 0.5 * (raw[0][h][j] - raw[1][h][NQ - 1 - j]);
                if (nonNegative) {
    v = Math.max(v, 0.0);
}
                q[h][j] = v;
            }
        }
        return q;
    }

    /** 将目标分位数值映射到 21 档输出中最近档的索引。 */
    private static int nearestQuantileIndex(double q) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < NQ; i++) {
            double d = Math.abs(QUANTILE_LEVELS[i] - q);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 工具方法

    private static float f32(double v) {
        return (float) v;
    }

    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1.0));
    }

    @SuppressWarnings("unchecked")
    private static float[] toFlatFloat3d(Object val) throws ai.onnxruntime.OrtException {
        if (val instanceof float[][][] f) {
            int total = f.length * f[0].length * f[0][0].length;
            float[] out = new float[total];
            int p = 0;
            for (float[][] a : f) {
                for (float[] b : a) {
                    System.arraycopy(b, 0, out, p, b.length);
                    p += b.length;
                }
            }
            return out;
        }
        if (val instanceof float[][] fa) {
            int total = fa.length * fa[0].length;
            float[] out = new float[total];
            int p = 0;
            for (float[] row : fa) {
                System.arraycopy(row, 0, out, p, row.length);
                p += row.length;
            }
            return out;
        }
        if (val instanceof float[] fa) {
            return fa;
        }
        if (val instanceof ai.onnxruntime.OnnxValue ov) {
            Object v = ov.getValue();
            return toFlatFloat3d(v);
        }
        throw new IllegalStateException("Timestral 输出类型不支持: " + val.getClass().getName());
    }

    // ---------------------------------------------------------------- 惰性加载

    private void ensurePrepared() {
        if (prepared) {
    return;
}
        synchronized (this) {
            if (prepared) {
    return;
}
            try {
                ortEnv = OrtEnvironment.getEnvironment();
                Path modelFile = resolveModelPath(modelPath);
                if (modelFile == null || !Files.exists(modelFile)) {
                    Path reg = registryModelPath();
                    if (reg != null && Files.exists(reg)) {
                        modelFile = reg;
                    } else {
                        modelFile = resolveModelPath(DEFAULT_RESOURCE);
                    }
                }
                if (modelFile == null || !Files.exists(modelFile)) {
                    throw new IllegalStateException("Timestral-5M ONNX 权重文件未找到（注入路径=" + modelPath
                            + "，注册表/默认资源均不可用，请添加 utils-support-models-onnx-timestral-5m 依赖）");
                }
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = ortEnv.createSession(modelFile.toString(), opts);
                prepared = true;
                log.info("[TimestralTranslator] model loaded: {}", modelFile);
            } catch (ai.onnxruntime.OrtException e) {
                throw new RuntimeException("Timestral ONNX 会话初始化失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 解析模型路径：绝对路径 → classpath 兜底（解压到临时文件）。
     *
     * @param pathStr 注册表注入的路径字符串
     * @return 可用的文件路径，找不到时返回 null
     */
    private static Path resolveModelPath(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return null;
        }
        Path abs = Path.of(pathStr);
        if (Files.exists(abs)) {
            return abs;
        }
        // classpath 兜底：将 jar 内资源解压到临时文件
        String resource = pathStr.startsWith("/") ? pathStr.substring(1) : pathStr;
        try {
            InputStream is = TimestralTranslator.class.getClassLoader().getResourceAsStream(resource);
            if (is != null) {
                Path tmp = Files.createTempFile("timestral-", ".onnx");
                Files.write(tmp, is.readAllBytes());
                is.close();
                tmp.toFile().deleteOnExit();
                return tmp;
            }
        } catch (Exception ignored) {
            // classpath 兜底失败，继续返回 null
        }
        return null;
    }

    /**
     * 从模型注册表解析权重路径（SPI 直接实例化、未注入 modelPath 时的兜底）。
     *
     * @return 注册表可解析的本地路径，解析失败返回 null
     */
    private static Path registryModelPath() {
        try {
            ModelRegistry.discoverAll();
            return ModelRegistry.resolveModelPath("timestral-5m");
        } catch (Exception e) {
            log.debug("[TimestralTranslator] 注册表解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 关闭会话。
     */
    public void close() {
        synchronized (this) {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响后续
                }
                session = null;
                prepared = false;
            }
        }
    }
}
