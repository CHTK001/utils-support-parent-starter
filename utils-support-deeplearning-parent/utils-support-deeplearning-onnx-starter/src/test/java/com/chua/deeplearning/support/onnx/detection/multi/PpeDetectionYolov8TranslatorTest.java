package com.chua.deeplearning.support.onnx.detection.multi;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@link PpeDetectionYolov8Translator} 单元 + 集成测试。
 */
@DisplayName("PpeDetectionYolov8Translator 测试")
class PpeDetectionYolov8TranslatorTest {

    private static final String MODEL_ID = "yolov8n-ppe";

    @BeforeAll
    static void setup() {
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();
    }

    @Test
    @DisplayName("默认构造：inputSize=640, 3 类")
    void testDefaultConstructor() {
        PpeDetectionYolov8Translator t = new PpeDetectionYolov8Translator();
        assertThat(t).isNotNull();
        assertThat(t.getInputSize()).isEqualTo(640);
        assertThat(t.actualClassNames()).hasSize(3);
    }

    @Test
    @DisplayName("3 类必含 helmet/vest/no-helmet")
    void testClassNames() {
        PpeDetectionYolov8Translator t = new PpeDetectionYolov8Translator();
        List<String> names = t.actualClassNames();
        assertThat(names).contains("helmet", "vest", "no-helmet");
    }

    @Test
    @DisplayName("自定义构造：参数校验")
    void testCustomConstructor() {
        assertThat(new PpeDetectionYolov8Translator(800, 0.3f, 0.5f).getInputSize()).isEqualTo(800);

        try {
            new PpeDetectionYolov8Translator(0, 0.25f, 0.45f);
            fail("应为非法参数");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("inputSize");
        }
        try {
            new PpeDetectionYolov8Translator(640, 1.5f, 0.45f);
            fail("应为非法参数");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("threshold");
        }
    }

    @Test
    @DisplayName("ModelRegistry 注册：yolov8n-ppe 已注册")
    void testRegistryEntry() {
        ModelRegistry.Entry entry = ModelRegistry.get(MODEL_ID);
        if (entry == null) {
            fail("OnnxModelRegistrar 未注册 " + MODEL_ID);
            return;
        }
        assertThat(entry.translatorClassName())
                .isEqualTo("com.chua.deeplearning.support.onnx.detection.multi.PpeDetectionYolov8Translator");
        assertThat(entry.relativePath()).isEqualTo("vision/ppe/yolov8n/model.onnx");
        assertThat(entry.capabilityInterface()).isEqualTo(ImageDetector.class);
    }

    @Test
    @DisplayName("Translator 类可被 Class.forName 加载")
    void testTranslatorClassLoadable() throws Exception {
        Class<?> clazz = Class.forName("com.chua.deeplearning.support.onnx.detection.multi.PpeDetectionYolov8Translator");
        assertThat(clazz).isNotNull();
        assertThat(ai.djl.translate.Translator.class).isAssignableFrom(clazz);
    }
}
