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
 * {@link TableDetectionYolov8Translator} 单元 + 集成测试。
 *
 * <p>测试分层：
 * <ul>
 *   <li>Translator 自身行为：构造、参数校验、类别名固定、Registry 注册</li>
 *   <li>Translator 类可被 Class.forName 加载（懒加载 SPI 验证）</li>
 * </ul>
 */
@DisplayName("TableDetectionYolov8Translator 测试")
class TableDetectionYolov8TranslatorTest {

    private static final String MODEL_ID = "yolov8n-table-detection";

    @BeforeAll
    static void setup() {
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();
    }

    @Test
    @DisplayName("默认构造：inputSize=640, 类别=table")
    void testDefaultConstructor() {
        TableDetectionYolov8Translator t = new TableDetectionYolov8Translator();
        assertThat(t).isNotNull();
        assertThat(t.getInputSize()).isEqualTo(640);
        assertThat(t.actualClassName()).isEqualTo("table");
    }

    @Test
    @DisplayName("自定义构造：参数校验")
    void testCustomConstructor() {
        TableDetectionYolov8Translator t = new TableDetectionYolov8Translator(800, 0.3f, 0.5f);
        assertThat(t).isNotNull();
        assertThat(t.getInputSize()).isEqualTo(800);

        try {
            new TableDetectionYolov8Translator(0, 0.25f, 0.45f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("inputSize");
        }
        try {
            new TableDetectionYolov8Translator(640, 1.5f, 0.45f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("threshold");
        }
        try {
            new TableDetectionYolov8Translator(640, 0.25f, -0.1f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("nmsThreshold");
        }
    }

    @Test
    @DisplayName("类别名恒为 'table'")
    void testClassName() {
        assertThat(new TableDetectionYolov8Translator().actualClassName()).isEqualTo("table");
    }

    @Test
    @DisplayName("ModelRegistry 注册：yolov8n-table-detection 已注册且字段正确")
    void testRegistryEntry() {
        ModelRegistry.Entry entry = ModelRegistry.get(MODEL_ID);
        if (entry == null) {
            fail("OnnxModelRegistrar 未注册 " + MODEL_ID);
            return;
        }
        assertThat(entry.modelId()).isEqualTo(MODEL_ID);
        assertThat(entry.translatorClassName())
                .isEqualTo("com.chua.deeplearning.support.onnx.detection.single.TableDetectionYolov8Translator");
        assertThat(entry.relativePath()).isEqualTo("vision/table/yolov8n/model.onnx");
        assertThat(entry.inputType()).isEqualTo(ai.djl.modality.cv.Image.class);
        assertThat(entry.outputType()).isEqualTo(ai.djl.modality.cv.output.DetectedObjects.class);
        assertThat(entry.capabilityInterface()).isEqualTo(ImageDetector.class);
    }

    @Test
    @DisplayName("Translator 类可被 Class.forName 加载（懒加载 SPI 验证）")
    void testTranslatorClassLoadable() throws Exception {
        String className = "com.chua.deeplearning.support.onnx.detection.single.TableDetectionYolov8Translator";
        Class<?> clazz = Class.forName(className);
        assertThat(clazz).isNotNull();
        assertThat(ai.djl.translate.Translator.class).isAssignableFrom(clazz);
        assertThat(clazz.getDeclaredConstructor().newInstance()).isNotNull();
    }
}
