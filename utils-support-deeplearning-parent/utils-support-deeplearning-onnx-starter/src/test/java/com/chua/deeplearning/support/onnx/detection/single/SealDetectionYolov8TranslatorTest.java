package com.chua.deeplearning.support.onnx.detection.single;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@link SealDetectionYolov8Translator} 单元 + 集成测试。
 */
@DisplayName("SealDetectionYolov8Translator 测试")
class SealDetectionYolov8TranslatorTest {

    private static final String MODEL_ID = "yolov8n-seal-detection";

    @BeforeAll
    static void setup() {
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();
    }

    @Test
    @DisplayName("默认构造：inputSize=640, 类别=seal")
    void testDefaultConstructor() {
        SealDetectionYolov8Translator t = new SealDetectionYolov8Translator();
        assertThat(t).isNotNull();
        assertThat(t.getInputSize()).isEqualTo(640);
        assertThat(t.actualClassName()).isEqualTo("seal");
    }

    @Test
    @DisplayName("自定义构造：参数校验")
    void testCustomConstructor() {
        SealDetectionYolov8Translator t = new SealDetectionYolov8Translator(800, 0.3f, 0.5f);
        assertThat(t).isNotNull();
        assertThat(t.getInputSize()).isEqualTo(800);

        try {
            new SealDetectionYolov8Translator(0, 0.25f, 0.45f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("inputSize");
        }
        try {
            new SealDetectionYolov8Translator(640, 1.5f, 0.45f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("threshold");
        }
        try {
            new SealDetectionYolov8Translator(640, 0.25f, -0.1f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("nmsThreshold");
        }
    }

    @Test
    @DisplayName("类别名恒为 'seal'")
    void testClassName() {
        assertThat(new SealDetectionYolov8Translator().actualClassName()).isEqualTo("seal");
    }

    @Test
    @DisplayName("ModelRegistry 注册：yolov8n-seal-detection 已注册且字段正确")
    void testRegistryEntry() {
        ModelRegistry.Entry entry = ModelRegistry.get(MODEL_ID);
        if (entry == null) {
            fail("OnnxModelRegistrar 未注册 " + MODEL_ID);
            return;
        }
        assertThat(entry.modelId()).isEqualTo(MODEL_ID);
        assertThat(entry.translatorClassName())
                .isEqualTo("com.chua.deeplearning.support.onnx.detection.single.SealDetectionYolov8Translator");
        assertThat(entry.relativePath()).isEqualTo("vision/seal/yolov8n/model.onnx");
        assertThat(entry.inputType()).isEqualTo(ai.djl.modality.cv.Image.class);
        assertThat(entry.outputType()).isEqualTo(ai.djl.modality.cv.output.DetectedObjects.class);
        assertThat(entry.capabilityInterface()).isEqualTo(ImageDetector.class);
    }

    @Test
    @DisplayName("Translator 类可被 Class.forName 加载（懒加载 SPI 验证）")
    void testTranslatorClassLoadable() throws Exception {
        String className = "com.chua.deeplearning.support.onnx.detection.single.SealDetectionYolov8Translator";
        Class<?> clazz = Class.forName(className);
        assertThat(clazz).isNotNull();
        assertThat(ai.djl.translate.Translator.class).isAssignableFrom(clazz);
        assertThat(clazz.getDeclaredConstructor().newInstance()).isNotNull();
    }

    @Test
    @DisplayName("两个单类 Translator 互不污染：table vs seal 类别名独立")
    void testClassNameIsolation() {
        TableDetectionYolov8Translator tab = new TableDetectionYolov8Translator();
        SealDetectionYolov8Translator seal = new SealDetectionYolov8Translator();
        assertThat(tab.actualClassName()).isNotEqualTo(seal.actualClassName());
        assertThat(tab.actualClassName()).isEqualTo("table");
        assertThat(seal.actualClassName()).isEqualTo("seal");
    }
}
