// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.onnx.timeseries;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.timeseries.ForecastRequest;
import com.chua.deeplearning.support.timeseries.ForecastResult;
import com.chua.deeplearning.support.timeseries.TimeSeriesForecaster;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Timestral-5M 单变量时间序列预测验收测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>注册表条目：modelId / 类型 / 能力接口 / relativePath / downloadUrl</li>
 *   <li>SPI 扩展：{@code ServiceProvider.getExtension("onnx")} 命中 {@link TimestralTranslator}</li>
 *   <li>jar 内嵌资源：model.onnx / config.json / LICENSE 均存在</li>
 *   <li>预处理参数校验：context 为空 / horizon 不合法 / quantile 越界</li>
 *   <li>真实推理：正弦 512 步上下文 → 点预测 + 分位数区间</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
class TimestralTranslatorTest {

    private static final int HORIZON = 32;

    // ---------------------------------------------------------------- 注册表

    @Test
    void registered() {
        ModelRegistry.discoverAll();
        ModelRegistry.Entry e = ModelRegistry.get("timestral-5m");
        assertNotNull(e, "timestral-5m 应在注册表中");
        assertEquals("com.chua.deeplearning.support.onnx.timeseries.TimestralTranslator",
                e.translatorClassName());
        assertEquals(ForecastRequest.class, e.inputType());
        assertEquals(ForecastResult.class, e.outputType());
        assertEquals(TimeSeriesForecaster.class, e.capabilityInterface());
        assertEquals("timeseries/timestral/timestral-5m/model.onnx", e.relativePath());
        assertNotNull(e.downloadUrl());
    }

    // ---------------------------------------------------------------- SPI

    @Test
    void spiResolvesOnnx() {
        TimeSeriesForecaster f =
                ServiceProvider.of(TimeSeriesForecaster.class).getExtension("onnx");
        assertNotNull(f, "SPI onnx 扩展应解析到 TimestralTranslator");
        assertInstanceOf(TimestralTranslator.class, f);
    }

    // ---------------------------------------------------------------- 内嵌资源

    @Test
    void bundledResourcesPresent() {
        ClassLoader loader = TimestralTranslator.class.getClassLoader();
        assertResource(loader, "timeseries/timestral/timestral-5m/model.onnx", 20_000_000);
        assertResource(loader, "timeseries/timestral/timestral-5m/config.json", 500);
        assertResource(loader, "timeseries/timestral/timestral-5m/LICENSE", 1000);
    }

    private static void assertResource(ClassLoader loader, String resource, int minBytes) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            assertNotNull(in, "缺少内嵌资源: " + resource);
            int len = in.available();
            assertTrue(len >= minBytes, resource + " 应 >= " + minBytes + " 字节，实际 " + len);
        } catch (Exception e) {
            fail("读取资源失败: " + resource + " -> " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    void emptyContextThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ForecastRequest(new double[0], HORIZON, null));
    }

    @Test
    void nullContextThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ForecastRequest(null, HORIZON, null));
    }

    @Test
    void horizonBelowOneThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ForecastRequest(new double[]{1.0}, 0, null));
    }

    @Test
    void quantileOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ForecastRequest(new double[]{1.0}, HORIZON, new double[]{0.5, 1.5}));
    }

    // ---------------------------------------------------------------- 真实推理

    @Test
    void forecastOnSineContext() {
        TimeSeriesForecaster f =
                ServiceProvider.of(TimeSeriesForecaster.class).getExtension("onnx");
        assertNotNull(f);

        // 512 步正弦上下文（周期 64，模拟昼夜波动）
        double[] ctx = new double[512];
        for (int i = 0; i < ctx.length; i++) {
            ctx[i] = 10 + 5 * Math.sin(2 * Math.PI * i / 64.0);
        }

        // 点预测
        double[] point = f.pointForecast(ctx, HORIZON);
        assertNotNull(point);
        assertEquals(HORIZON, point.length, "点预测长度应等于 horizon");
        for (double v : point) {
            assertTrue(Double.isFinite(v), "点预测应有限: " + v);
        }

        // 分位数区间：0.1 / 0.5 / 0.9
        double[] q = {0.1, 0.5, 0.9};
        double[][] bands = f.quantileForecast(ctx, HORIZON, q);
        assertNotNull(bands);
        assertEquals(3, bands.length, "应有 3 档分位数");
        for (double[] band : bands) {
            assertEquals(HORIZON, band.length);
        }
        // 对每步：0.1 分位数应 <= 0.5 分位数 <= 0.9 分位数（单调性）
        for (int h = 0; h < HORIZON; h++) {
            assertTrue(bands[0][h] <= bands[1][h],
                    "h=" + h + " 0.1(" + bands[0][h] + ") 应 <= 0.5(" + bands[1][h] + ")");
            assertTrue(bands[1][h] <= bands[2][h],
                    "h=" + h + " 0.5(" + bands[1][h] + ") 应 <= 0.9(" + bands[2][h] + ")");
        }

        // 手动 ForecastResult 路径
        ForecastResult r = f.forecast(new ForecastRequest(ctx, HORIZON, q));
        assertNotNull(r);
        assertEquals(HORIZON, r.horizon());
        assertEquals(HORIZON, r.point().length);
        assertNotNull(r.forecast());
        assertEquals(3, r.forecast().length);
    }

    @Test
    void forecastOnShortContext() {
        // 100 步短上下文（不足 512 应自动左补 NaN）
        TimeSeriesForecaster f =
                ServiceProvider.of(TimeSeriesForecaster.class).getExtension("onnx");
        assertNotNull(f);
        double[] ctx = new double[100];
        for (int i = 0; i < ctx.length; i++) {
            ctx[i] = 20 + 3 * Math.sin(i / 7.0);
        }
        double[] point = f.pointForecast(ctx, HORIZON);
        assertEquals(HORIZON, point.length);
        for (double v : point) {
            assertTrue(Double.isFinite(v));
        }
    }

    @Test
    void forecastOnAllNaN() {
        // 全 NaN：模型应输出有限值（预处理置 0，denormalize 后回 ctxMin/Max = 0）
        TimeSeriesForecaster f =
                ServiceProvider.of(TimeSeriesForecaster.class).getExtension("onnx");
        assertNotNull(f);
        double[] ctx = new double[512];
        // 全部 NaN
        java.util.Arrays.fill(ctx, Double.NaN);
        double[] point = f.pointForecast(ctx, HORIZON);
        assertNotNull(point);
        assertEquals(HORIZON, point.length);
        for (double v : point) {
            assertTrue(Double.isFinite(v), "全 NaN 输入应产生有限输出: " + v);
        }
    }
}