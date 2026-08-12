package com.chua.deeplearning.support.onnx.detection.multi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * utils-support-models-onnx-ppe 模型包资源测试。
 */
@DisplayName("utils-support-models-onnx-ppe 资源验证")
class PpeDetectModelPackageTest {

    private static final String CLASS_NAMES_RESOURCE = "vision/ppe/yolov8n/class.names.txt";
    private static final String PLACEHOLDER_FILE = "vision/ppe/yolov8n/.gitkeep";

    @Test
    @DisplayName("class.names.txt 在 classpath 中存在")
    void testClassNamesResourceExists() {
        assertThat(getClass().getClassLoader().getResource(CLASS_NAMES_RESOURCE))
                .as("缺少 %s — utils-support-models-onnx-ppe jar 未提供", CLASS_NAMES_RESOURCE)
                .isNotNull();
    }

    @Test
    @DisplayName("class.names.txt 恰好 3 行")
    void testClassNamesLineCount() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        long nonEmpty = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .count();
        assertThat(nonEmpty).as("class.names.txt 必须恰好 3 行, 实际: %d", nonEmpty).isEqualTo(3);
    }

    @Test
    @DisplayName("class.names.txt 内容为 helmet/vest/no-helmet")
    void testClassNameValue() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        List<String> lines = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
        assertThat(lines).containsExactly("helmet", "vest", "no-helmet");
    }

    @Test
    @DisplayName("PpeDetectionYolov8Translator 从 class.names.txt 加载 3 类")
    void testTranslatorLoadsFromResource() {
        PpeDetectionYolov8Translator t = new PpeDetectionYolov8Translator();
        assertThat(t.actualClassNames()).hasSize(3);
        assertThat(t.actualClassNames()).contains("helmet", "vest", "no-helmet");
    }

    @Test
    @DisplayName(".gitkeep 占位文件存在")
    void testGitkeepExists() {
        assertThat(getClass().getClassLoader().getResource(PLACEHOLDER_FILE)).isNotNull();
    }

    private static String readResource(String resource) throws IOException {
        ClassLoader cl = PpeDetectModelPackageTest.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("classpath 缺少资源: " + resource);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}