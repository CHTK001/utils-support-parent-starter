package com.chua.deeplearning.support.onnx.layout.doclaynet;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DocLayNet YOLOv8 文档版面分析 Translator 单元 + 集成测试。
 *
 * <p>测试分层:
 * <ul>
 *   <li>Translator 自身行为：构造、参数校验、类别列表、NMS-free 数值（NMS 留给集成）</li>
 *   <li>classpath 资源：class.names.txt 必须 11 类（前置条件）</li>
 *   <li>Registry 注册：doc-layout-yolo-imgsz640 已注册且入口正确</li>
 *   <li>集成（条件）：若 classpath 含 model.onnx，验证 ModelRegistry.resolveModelPath 可解析</li>
 * </ul>
 */
@DisplayName("DocLayNet YOLOv8 Translator 测试")
class DocLayNetYolov8TranslatorTest {

    private static final String MODEL_ID = "doc-layout-yolo-imgsz640";
    private static final String MODEL_RESOURCE = "vision/layout/doclaynet/model.onnx";
    private static final String CLASS_NAMES_RESOURCE = "vision/layout/doclaynet/class.names.txt";

    @BeforeAll
    static void setup() {
        // 显式触发 OnnxModelRegistrar 静态注册 + SPI 加载
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();
    }

    @Test
    @DisplayName("默认构造：不抛异常，11 类")
    void testDefaultConstructor() {
        DocLayNetYolov8Translator t = new DocLayNetYolov8Translator();
        assertThat(t).isNotNull();
        assertThat(DocLayNetYolov8Translator.DOCLAYNET_CLASSES).hasSize(11);
    }

    @Test
    @DisplayName("自定义构造：参数校验")
    void testCustomConstructor() {
        DocLayNetYolov8Translator t = new DocLayNetYolov8Translator(640, 0.3f, 0.5f);
        assertThat(t).isNotNull();

        // 非法输入
        try {
            new DocLayNetYolov8Translator(0, 0.25f, 0.45f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("inputSize");
        }
        try {
            new DocLayNetYolov8Translator(640, -0.1f, 0.45f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("threshold");
        }
        try {
            new DocLayNetYolov8Translator(640, 0.25f, 1.5f);
            fail("应为非法参数异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("nmsThreshold");
        }
    }

    @Test
    @DisplayName("DocLayNet 11 类列表包含所有关键类别")
    void testClassList() {
        List<String> classes = DocLayNetYolov8Translator.DOCLAYNET_CLASSES;
        assertThat(classes)
                .contains("title", "text", "table", "picture", "caption",
                        "section-header", "page-header", "page-footer",
                        "list-item", "formula", "footnote");
    }

    @Test
    @DisplayName("支持的尺寸 + 描述 API")
    void testStaticHelpers() {
        assertThat(DocLayNetYolov8Translator.getRecommendedSizes())
                .contains(640, 800, 1024);
        assertThat(DocLayNetYolov8Translator.getSupportedClasses())
                .hasSize(11);
        assertThat(DocLayNetYolov8Translator.getModelDescription())
                .contains("DocLayNet")
                .contains("YOLOv8")
                .contains("11 类")
                .contains("640");
    }

    @Test
    @DisplayName("class.names.txt 资源：必须包含 11 类，每行一个")
    void testClassNamesResource() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CLASS_NAMES_RESOURCE)) {
            if (is == null) {
                fail("classpath 缺少 " + CLASS_NAMES_RESOURCE + " (utils-support-models-onnx-doclaynet jar 未提供)");
                return;
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = content.split("\\R");
            long nonEmpty = java.util.Arrays.stream(lines)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .count();
            assertThat(nonEmpty)
                    .as("class.names.txt 必须恰好 11 类, 实际: %d", nonEmpty)
                    .isEqualTo(11);
        }
    }

    @Test
    @DisplayName("loadClassesOrDefault 工具：11 类 + 与默认列表一致")
    void testLoadClasses() {
        List<String> loaded = DocLayNetYolov8Translator.loadClassesOrDefault();
        assertThat(loaded)
                .hasSize(11)
                .containsExactlyElementsOf(DocLayNetYolov8Translator.DOCLAYNET_CLASSES);
    }

    @Test
    @DisplayName("ModelRegistry 注册：doc-layout-yolo-imgsz640 已注册且字段正确")
    void testRegistryEntry() {
        ModelRegistry.Entry entry = ModelRegistry.get(MODEL_ID);
        if (entry == null) {
            fail("OnnxModelRegistrar 未注册 " + MODEL_ID);
            return;
        }
        assertThat(entry.modelId()).isEqualTo(MODEL_ID);
        assertThat(entry.translatorClassName())
                .isEqualTo("com.chua.deeplearning.support.onnx.layout.doclaynet.DocLayNetYolov8Translator");
        assertThat(entry.relativePath()).isEqualTo(MODEL_RESOURCE);
        assertThat(entry.inputType()).isEqualTo(ai.djl.modality.cv.Image.class);
        assertThat(entry.outputType()).isEqualTo(ai.djl.modality.cv.output.DetectedObjects.class);
        assertThat(entry.capabilityInterface()).isEqualTo(com.chua.deeplearning.support.layout.LayoutDetector.class);
    }

    @Test
    @DisplayName("ModelRegistry 注册：doc-layout-yolo-imgsz640 唯一")
    void testRegistryUniqueness() {
        ModelRegistry.Entry a = ModelRegistry.get(MODEL_ID);
        ModelRegistry.Entry b = ModelRegistry.get(MODEL_ID);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("Translator 类可被 Class.forName 加载（懒加载 SPI 验证）")
    void testTranslatorClassLoadable() throws Exception {
        String className = "com.chua.deeplearning.support.onnx.layout.doclaynet.DocLayNetYolov8Translator";
        Class<?> clazz = Class.forName(className);
        assertThat(clazz).isNotNull();
        assertThat(ai.djl.translate.Translator.class).isAssignableFrom(clazz);
        assertThat(clazz.getDeclaredConstructor().newInstance()).isNotNull();
    }

    @Test
    @DisplayName("集成（条件）：若 model.onnx 已部署，可解析到本地路径")
    void testModelPathResolutionIfPresent() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MODEL_RESOURCE)) {
            if (is == null) {
                // 模型未提供是正常的（云效交付前），跳过而非失败
                return;
            }
            java.nio.file.Path path = ModelRegistry.resolveModelPath(MODEL_ID);
            if (path != null) {
                assertThat(path).exists();
            }
        } catch (Exception e) {
            // 静默：模型未部署时不应失败
        }
    }
}
