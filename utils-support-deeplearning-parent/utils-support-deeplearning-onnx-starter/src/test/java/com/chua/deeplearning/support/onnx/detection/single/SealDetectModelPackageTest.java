package com.chua.deeplearning.support.onnx.detection.single;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * utils-support-models-onnx-seal-detect 模型包资源测试。
 */
@DisplayName("utils-support-models-onnx-seal-detect 资源验证")
class SealDetectModelPackageTest {

    private static final String CLASS_NAMES_RESOURCE = "vision/seal/yolov8n/class.names.txt";
    private static final String PLACEHOLDER_FILE = "vision/seal/yolov8n/.gitkeep";

    @Test
    @DisplayName("class.names.txt 在 classpath 中存在")
    void testClassNamesResourceExists() {
        assertThat(getClass().getClassLoader().getResource(CLASS_NAMES_RESOURCE))
                .as("缺少 %s — 请确认 utils-support-models-onnx-seal-detect jar 已在 classpath (test scope)",
                        CLASS_NAMES_RESOURCE)
                .isNotNull();
    }

    @Test
    @DisplayName("class.names.txt 恰好 1 行 ('seal')")
    void testClassNamesLineCount() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        long nonEmpty = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .count();
        assertThat(nonEmpty)
                .as("class.names.txt 必须恰好 1 行, 实际: %d", nonEmpty)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("class.names.txt 内容 = 'seal'")
    void testClassNameValue() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        List<String> lines = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
        assertThat(lines).containsExactly("seal");
    }

    @Test
    @DisplayName("SealDetectionYolov8Translator 从 class.names.txt 加载类别")
    void testTranslatorLoadsFromResource() {
        SealDetectionYolov8Translator t = new SealDetectionYolov8Translator();
        assertThat(t.actualClassName()).isEqualTo("seal");
    }

    @Test
    @DisplayName(".gitkeep 占位文件存在")
    void testGitkeepExists() {
        assertThat(getClass().getClassLoader().getResource(PLACEHOLDER_FILE))
                .as("缺少 %s — 模型未部署到 jar 时应保留此占位", PLACEHOLDER_FILE)
                .isNotNull();
    }

    private static String readResource(String resource) throws IOException {
        ClassLoader cl = SealDetectModelPackageTest.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("classpath 缺少资源: " + resource);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
