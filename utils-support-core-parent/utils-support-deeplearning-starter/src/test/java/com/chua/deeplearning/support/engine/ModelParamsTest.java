package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.ai.DetectionConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelParams} 单元测试。
 *
 * <p>覆盖：per-model 存储与合并、device 归一化，以及未实现
 * {@link DetectionConfigurable} 的 Translator 依靠反射回写实现运行期热更新的能力。</p>
 *
 * @author CH
 */
class ModelParamsTest {

    private static final String MODEL = "unit-test-model";

    @Test
    void 存取与合并() {
        ModelParams.clear();
        ModelParams.put(MODEL, Map.of("a", 1));
        ModelParams.putAll(MODEL, Map.of("b", 2));
        assertEquals(1, ModelParams.get(MODEL, "a", null));
        assertEquals(2, ModelParams.get(MODEL, "b", null));

        // 显式参数优先级高于模型级参数
        Map<String, Object> explicit = Map.of("b", 99);
        Map<String, Object> merged = ModelParams.merge(MODEL, explicit);
        assertEquals(1, merged.get("a"));
        assertEquals(99, merged.get("b"));

        ModelParams.remove(MODEL);
        assertTrue(ModelParams.get(MODEL).isEmpty());
    }

    @Test
    void 阈值与设备归一化() {
        ModelParams.clear();
        assertNull(ModelParams.resolveThreshold(MODEL, null));
        assertNull(ModelParams.resolveNms(MODEL, null));

        ModelParams.put(MODEL, Map.of(
                DetectionConfiguration.KEY_THRESHOLD, 0.42,
                DetectionConfiguration.KEY_IOU_THRESHOLD, "0.33",
                ModelParams.KEY_DEVICE, "cpu"));

        assertEquals(0.42f, ModelParams.resolveThreshold(MODEL, null));
        assertEquals(0.33f, ModelParams.resolveNms(MODEL, null));
        assertEquals("cpu", ModelParams.deviceOf(MODEL));
        assertEquals("cpu", ModelParams.resolveDevice(MODEL, null));

        ModelParams.clear();
    }

    @Test
    void 反射回写阈值字段() {
        // 模拟一个未实现 DetectionConfigurable 的 Translator：阈值存于 private 字段
        FakeTranslator fake = new FakeTranslator();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(DetectionConfiguration.KEY_THRESHOLD, 0.66);

        int applied = ModelParams.applyTo(fake, params);
        assertEquals(1, applied);
        assertEquals(0.66f, fake.threshold, 1e-6);
    }

    @Test
    void 反射回写别名与Nms字段() {
        AliasTranslator fake = new AliasTranslator();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(DetectionConfiguration.KEY_THRESHOLD, 0.11);
        params.put(DetectionConfiguration.KEY_IOU_THRESHOLD, 0.22);
        params.put(ModelParams.KEY_THREADS, 3);

        int applied = ModelParams.applyTo(fake, params);
        assertEquals(3, applied);
        assertEquals(0.11f, fake.scoreThreshold, 1e-6);
        assertEquals(0.22f, fake.nmsThreshold, 1e-6);
        assertEquals(3, fake.threads);
    }

    @Test
    void 未知参数被忽略() {
        FakeTranslator fake = new FakeTranslator();
        int applied = ModelParams.applyTo(fake, Map.of("notAStandardKey", 5));
        assertEquals(0, applied);
        // 空值与空表不报错
        assertEquals(0, ModelParams.applyTo(fake, null));
        assertEquals(0, ModelParams.applyTo(null, Map.of("a", 1)));
    }

    @Test
    void 线程参数对不支持的模型不报错() {
        // device 为空时按系统属性（auto）归一化，不应抛异常
        ModelParams.clear();
        String resolved = ModelParams.resolveDevice("no-such-model", null);
        assertNotNull(resolved);
        assertTrue(List.of("cpu", "gpu").contains(resolved));
        assertFalse(resolved.isBlank());
    }

    /**
     * 简单字段名（threshold）。
     */
    private static final class FakeTranslator {
        private float threshold = 0.25f;
    }

    /**
     * 别名字段名（scoreThreshold / nmsThreshold / threads）。
     */
    private static final class AliasTranslator {
        private float scoreThreshold = 0.25f;
        private float nmsThreshold = 0.45f;
        private int threads = 1;
    }
}
