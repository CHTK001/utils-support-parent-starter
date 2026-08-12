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
 * utils-support-models-onnx-barcode 模型包资源测试。
 */
@DisplayName("utils-support-models-onnx-barcode 资源验证")
class BarcodeDetectModelPackageTest {

    private static final String CLASS_NAMES_RESOURCE = "vision/barcode/yolov8n/class.names.txt";
    private static final String PLACEHOLDER_FILE = "vision/barcode/yolov8n/.gitkeep";

    @Test
    @DisplayName("class.names.txt 在 classpath 中存在")
    void testClassNamesResourceExists() {
        assertThat(getClass().getClassLoader().getResource(CLASS_NAMES_RESOURCE))
                .as("缺少 %s — utils-support-models-onnx-barcode jar 未提供", CLASS_NAMES_RESOURCE)
                .isNotNull();
    }

    @Test
    @DisplayName("class.names.txt 恰好 5 行")
    void testClassNamesLineCount() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        long nonEmpty = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .count();
        assertThat(nonEmpty).as("class.names.txt 必须恰好 5 行, 实际: %d", nonEmpty).isEqualTo(5);
    }

    @Test
    @DisplayName("class.names.txt 内容为 5 类码制")
    void testClassNameValue() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        List<String> lines = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
        assertThat(lines)
                .containsExactly("qr_code", "code_39", "code_128", "ean_13", "pdf_417");
    }

    @Test
    @DisplayName("BarcodeDetectionYolov8Translator 从 class.names.txt 加载 5 类")
    void testTranslatorLoadsFromResource() {
        BarcodeDetectionYolov8Translator t = new BarcodeDetectionYolov8Translator();
        assertThat(t.actualClassNames()).hasSize(5);
        assertThat(t.actualClassNames()).contains("qr_code", "pdf_417");
    }

    @Test
    @DisplayName(".gitkeep 占位文件存在")
    void testGitkeepExists() {
        assertThat(getClass().getClassLoader().getResource(PLACEHOLDER_FILE)).isNotNull();
    }

    private static String readResource(String resource) throws IOException {
        ClassLoader cl = BarcodeDetectModelPackageTest.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("classpath 缺少资源: " + resource);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}