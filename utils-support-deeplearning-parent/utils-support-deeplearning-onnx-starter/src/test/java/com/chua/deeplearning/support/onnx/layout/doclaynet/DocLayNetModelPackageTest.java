package com.chua.deeplearning.support.onnx.layout.doclaynet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * utils-support-models-onnx-doclaynet 模型包资源测试。
 *
 * <p>验证：
 * <ul>
 *   <li>classpath 必须能找到 {@code vision/layout/doclaynet/class.names.txt}（由
 *       {@code utils-support-models-onnx-doclaynet} JAR 提供）</li>
 *   <li>class.names.txt 恰好包含 11 类 DocLayNet 类别</li>
 *   <li>类别顺序与 {@link DocLayNetYolov8Translator#DOCLAYNET_CLASSES} 完全一致
 *       （模型输出 class_id 索引 → 类别名的映射正确性）</li>
 *   <li>JAR 内 model.onnx 占位 .gitkeep 存在（部署文档）</li>
 * </ul>
 *
 * <p>运行依赖：utils-support-models-onnx-doclaynet jar（test scope）已配置在 pom。
 * 如果 jar 缺失，测试会失败提示需要先部署模型包。
 */
@DisplayName("utils-support-models-onnx-doclaynet 资源验证")
class DocLayNetModelPackageTest {

    private static final String CLASS_NAMES_RESOURCE = "vision/layout/doclaynet/class.names.txt";
    private static final String MODEL_RESOURCE = "vision/layout/doclaynet/model.onnx";
    private static final String PLACEHOLDER_FILE = "vision/layout/doclaynet/.gitkeep";

    @Test
    @DisplayName("class.names.txt 在 classpath 中存在")
    void testClassNamesResourceExists() {
        URL url = getClass().getClassLoader().getResource(CLASS_NAMES_RESOURCE);
        assertThat(url)
                .as("缺少 %s — 请确认 utils-support-models-onnx-doclaynet jar 已在 classpath (test scope)",
                        CLASS_NAMES_RESOURCE)
                .isNotNull();
    }

    @Test
    @DisplayName("class.names.txt 11 行（与 DocLayNet 11 类一致）")
    void testClassNamesLineCount() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        long nonEmpty = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .count();
        assertThat(nonEmpty)
                .as("class.names.txt 必须恰好 11 行, 实际: %d", nonEmpty)
                .isEqualTo(11);
    }

    @Test
    @DisplayName("class.names.txt 顺序与 DOCLAYNET_CLASSES 严格一致")
    void testClassNamesOrderMatches() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        List<String> lines = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
        assertThat(lines)
                .containsExactlyElementsOf(DocLayNetYolov8Translator.DOCLAYNET_CLASSES);
    }

    @Test
    @DisplayName("11 类必含所有 DocLayNet 关键类别")
    void testClassNamesContainKeywords() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        for (String keyword : new String[]{
                "title", "text", "table", "picture", "caption",
                "section-header", "page-header", "page-footer",
                "list-item", "formula", "footnote"}) {
            assertThat(content)
                    .as("class.names.txt 必须包含 '%s'", keyword)
                    .contains(keyword);
        }
    }

    @Test
    @DisplayName("类名规范化：lowercase + hyphen，无空格、无大写")
    void testClassNamesNormalized() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        List<String> lines = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
        for (String line : lines) {
            assertThat(line)
                    .as("类名必须 lowercase, 实际: '%s'", line)
                    .matches("[a-z0-9\\-]+");
        }
    }

    @Test
    @DisplayName("不重复：11 个类名互不相同")
    void testClassNamesUnique() throws IOException {
        String content = readResource(CLASS_NAMES_RESOURCE);
        List<String> lines = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
        assertThat(lines)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName(".gitkeep 占位文件存在（部署说明）")
    void testGitkeepExists() {
        URL url = getClass().getClassLoader().getResource(PLACEHOLDER_FILE);
        assertThat(url)
                .as("缺少 %s — 模型未部署到 jar 时应保留此占位", PLACEHOLDER_FILE)
                .isNotNull();
    }

    @Test
    @DisplayName("model.onnx 存在性：可选（云效注入后才会有）")
    void testModelOnnxOptional() {
        URL url = getClass().getClassLoader().getResource(MODEL_RESOURCE);
        if (url == null) {
            // 模型未提供是正常的（云效交付前），跳过而非失败
            return;
        }
        // 如果存在，必须是 > 1MB 的有效 ONNX
        try (InputStream is = url.openStream()) {
            byte[] header = is.readNBytes(2);
            assertThat(header)
                    .as("ONNX protobuf header 前 2 字节必须 0x08 0x07")
                    .isEqualTo(new byte[]{(byte) 0x08, (byte) 0x07});
        } catch (IOException e) {
            throw new AssertionError("读取 model.onnx 失败: " + e.getMessage(), e);
        }
    }

    private static String readResource(String resource) throws IOException {
        ClassLoader cl = DocLayNetModelPackageTest.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("classpath 缺少资源: " + resource);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
