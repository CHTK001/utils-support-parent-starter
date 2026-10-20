package com.chua.deeplearning.support.sherpa;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SherpaModelResolver} 与 {@link SherpaModelType} 的契约测试。
 *
 * <p>这些用例不依赖原生库、不下载模型，只验证「模型目录结构 → 模型族判定」这条
 * 纯 Java 逻辑，因此可在 CI 无条件执行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class SherpaModelResolverTest {

    /**
     * 模型族短标识解析应忽略大小写与分隔符差异。
     */
    @Test
    void shouldParseModelTypeLoosely() {
        assertThat(SherpaModelType.of("SenseVoice")).isEqualTo(SherpaModelType.SENSEVOICE);
        assertThat(SherpaModelType.of("zipformer_ctc")).isEqualTo(SherpaModelType.ZIPFORMER_CTC);
        assertThat(SherpaModelType.of("whisper")).isEqualTo(SherpaModelType.WHISPER);
        assertThat(SherpaModelType.of("paraformer")).isEqualTo(SherpaModelType.PARAFORMER);
        assertThat(SherpaModelType.of("transducer")).isEqualTo(SherpaModelType.TRANSDUCER);
        assertThat(SherpaModelType.of("unknown-family")).isNull();
        assertThat(SherpaModelType.of(null)).isNull();
    }

    /**
     * 三模型（encoder/decoder/joiner）目录应判定为 Transducer。
     *
     * @throws IOException 临时目录创建失败
     */
    @Test
    void shouldDetectTransducerByFileNames() throws IOException {
        Path dir = Files.createTempDirectory("sherpa-transducer");
        try {
            touch(dir, "encoder-epoch-99-avg-1.int8.onnx");
            touch(dir, "decoder-epoch-99-avg-1.int8.onnx");
            touch(dir, "joiner-epoch-99-avg-1.int8.onnx");
            touch(dir, "tokens.txt");
            assertThat(SherpaModelResolver.detect(dir, null)).isEqualTo(SherpaModelType.TRANSDUCER);
            assertThat(SherpaModelResolver.isUsableModelDir(dir)).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 双模型（encoder/decoder）目录应判定为 Whisper。
     *
     * @throws IOException 临时目录创建失败
     */
    @Test
    void shouldDetectWhisperByFileNames() throws IOException {
        Path dir = Files.createTempDirectory("sherpa-whisper");
        try {
            touch(dir, "tiny-encoder.int8.onnx");
            touch(dir, "tiny-decoder.int8.onnx");
            touch(dir, "tiny-tokens.txt");
            assertThat(SherpaModelResolver.detect(dir, null)).isEqualTo(SherpaModelType.WHISPER);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 单模型目录按目录名判定 Paraformer / SenseVoice。
     *
     * @throws IOException 临时目录创建失败
     */
    @Test
    void shouldDetectSingleModelByName() throws IOException {
        Path paraformer = Files.createTempDirectory("sherpa-paraformer-zh");
        Path sensevoice = Files.createTempDirectory("sherpa-sensevoice");
        try {
            touch(paraformer, "model.int8.onnx");
            touch(paraformer, "tokens.txt");
            assertThat(SherpaModelResolver.detect(paraformer, null)).isEqualTo(SherpaModelType.PARAFORMER);

            touch(sensevoice, "model.int8.onnx");
            touch(sensevoice, "tokens.txt");
            assertThat(SherpaModelResolver.detect(sensevoice, null)).isEqualTo(SherpaModelType.SENSEVOICE);
        } finally {
            deleteRecursively(paraformer);
            deleteRecursively(sensevoice);
        }
    }

    /**
     * 显式 hint 应覆盖自动判定。
     *
     * @throws IOException 临时目录创建失败
     */
    @Test
    void shouldPreferExplicitHint() throws IOException {
        Path dir = Files.createTempDirectory("sherpa-explicit");
        try {
            touch(dir, "model.int8.onnx");
            touch(dir, "tokens.txt");
            assertThat(SherpaModelResolver.detect(dir, "zipformer-ctc"))
                    .isEqualTo(SherpaModelType.ZIPFORMER_CTC);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 无法判定的目录应抛出可读异常而不是返回错误模型族。
     *
     * @throws IOException 临时目录创建失败
     */
    @Test
    void shouldFailOnUnknownLayout() throws IOException {
        Path dir = Files.createTempDirectory("sherpa-unknown");
        try {
            touch(dir, "weights.bin");
            assertThatThrownBy(() -> SherpaModelResolver.detect(dir, null))
                    .isInstanceOf(SherpaException.class)
                    .hasMessageContaining("无法判定模型族");
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 文件查找应优先 int8 量化版本。
     *
     * @throws IOException 临时目录创建失败
     */
    @Test
    void shouldPreferInt8ModelFile() throws IOException {
        Path dir = Files.createTempDirectory("sherpa-int8");
        try {
            touch(dir, "model.onnx");
            touch(dir, "model.int8.onnx");
            Path found = SherpaModelResolver.findFile(dir, "model");
            assertThat(found).isNotNull();
            assertThat(found.getFileName().toString()).isEqualTo("model.int8.onnx");
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 空模型引用应给出可操作提示。
     */
    @Test
    void shouldRejectBlankModel() {
        assertThatThrownBy(() -> SherpaModelResolver.resolveDirectory("  "))
                .isInstanceOf(SherpaException.class)
                .hasMessageContaining("未指定 sherpa-onnx 模型");
    }

    /**
     * 原生库不可用时应能优雅探测（不允许抛出 Error 终止 JVM 之外的流程）。
     */
    @Test
    void shouldProbeNativeLibraryWithoutThrowing() {
        boolean available = SherpaNativeLoader.isAvailable();
        String platform = SherpaNativeLoader.platform();
        assertThat(platform).isNotBlank();
        // 无论是否可用，失败原因都应包含平台与修复建议
        if (!available) {
            assertThat(SherpaNativeLoader.failureReason())
                    .contains("sherpa-onnx")
                    .contains(platform);
        }
    }

    /**
     * 真实模型目录（若已通过 -Dsherpa.onnx.model.dir 预置）应能构建识别器。
     */
    @Test
    void shouldBuildRecognizerWhenModelPresent() {
        String root = System.getProperty(SherpaModelResolver.PROP_MODEL_DIR);
        Assumptions.assumeTrue(root != null && !root.isBlank(),
                "未设置 -Dsherpa.onnx.model.dir，跳过真实模型用例");
        Path dir = Path.of(root);
        Assumptions.assumeTrue(SherpaModelResolver.isUsableModelDir(dir),
                "模型目录结构不完整（缺 tokens.txt 或 *.onnx）: " + root);
        Assumptions.assumeTrue(SherpaNativeLoader.isAvailable(),
                "sherpa-onnx 原生库不可用: " + SherpaNativeLoader.failureReason());

        try (SherpaRecognizer recognizer = SherpaRecognizer.builder().model(dir).build()) {
            assertThat(recognizer.modelType()).isNotNull();
            assertThat(recognizer.sampleRate()).isEqualTo(SherpaWavUtils.DEFAULT_SAMPLE_RATE);
        }
    }

    /**
     * 创建空文件。
     *
     * @param dir  目录
     * @param name 文件名
     * @throws IOException 创建失败
     */
    private static void touch(Path dir, String name) throws IOException {
        Files.write(dir.resolve(name), new byte[]{0});
    }

    /**
     * 递归删除临时目录。
     *
     * @param dir 目录
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响用例结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
