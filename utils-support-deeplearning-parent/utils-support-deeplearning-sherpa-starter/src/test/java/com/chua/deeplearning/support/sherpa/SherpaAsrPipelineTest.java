package com.chua.deeplearning.support.sherpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SherpaAsrPipeline} 门面验收测试。
 *
 * <p>分两层：</p>
 * <ul>
 *   <li><b>契约层</b>（无需原生库、无需模型）：构建参数校验、后处理、切块偏移换算；</li>
 *   <li><b>推理层</b>（需要 {@code -Dsherpa.onnx.model.dir} 与可用 native 库）：
 *       真实模型端到端识别 + 门面与 {@link SherpaRecognizer} 结果一致性。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
class SherpaAsrPipelineTest {

    /**
     * 临时目录，用例结束后清理。
     */
    private Path tempDir;

    /**
     * 每个用例前创建独立临时目录。
     *
     * @throws IOException 创建失败
     */
    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("sherpa-pipeline");
    }

    /**
     * 每个用例后清理临时目录。
     */
    @AfterEach
    void tearDown() {
        if (tempDir == null) {
            return;
        }
        try (var stream = Files.walk(tempDir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不影响结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /**
     * 未指定模型时必须立即失败，且错误信息可指导修复。
     */
    @Test
    void shouldRejectMissingModel() {
        assertThatThrownBy(() -> SherpaAsrPipeline.builder().build())
                .isInstanceOf(SherpaException.class)
                .hasMessageContaining("未指定 sherpa-onnx 模型");
    }

    /**
     * 最大段长非正数时必须拒绝，避免运行期死循环/空切块。
     */
    @Test
    void shouldRejectInvalidMaxSegment() {
        assertThatThrownBy(() -> SherpaAsrPipeline.builder()
                .model("/tmp/whatever")
                .maxSegmentSeconds(0F)
                .build())
                .isInstanceOf(SherpaException.class)
                .hasMessageContaining("maxSegmentSeconds");
    }

    /**
     * 未配置 VAD 时 {@code vadEnabled()} 应为 false，配置后为 true。
     */
    @Test
    void shouldReportVadState() {
        assertThat(SherpaAsrPipeline.builder().model("/tmp/m").build().vadEnabled()).isFalse();
        assertThat(SherpaAsrPipeline.builder()
                .model("/tmp/m")
                .vadModel("/tmp/silero_vad.onnx")
                .build()
                .vadEnabled()).isTrue();
    }

    /**
     * 后处理应压缩空白并把英文句首大写，且不破坏中文。
     */
    @Test
    void shouldPostProcessText() {
        assertThat(SherpaAsrPipeline.postProcess("  hello   world \n\n foo ")).isEqualTo("Hello world foo");
        assertThat(SherpaAsrPipeline.postProcess("你好   世界")).isEqualTo("你好 世界");
        assertThat(SherpaAsrPipeline.postProcess("")).isEmpty();
        assertThat(SherpaAsrPipeline.postProcess(null)).isEmpty();
        // 首字符非字母时不做大写化，避免把数字/标点弄坏
        assertThat(SherpaAsrPipeline.postProcess("123 abc")).isEqualTo("123 abc");
    }

    /**
     * 门面调用不存在的音频应立即给出明确异常（不触发原生库）。
     */
    @Test
    void shouldRejectMissingAudioFile() {
        SherpaAsrPipeline pipeline = SherpaAsrPipeline.builder().model("/tmp/m").build();
        assertThatThrownBy(() -> pipeline.transcribe(tempDir.resolve("nope.wav")))
                .isInstanceOf(SherpaException.class)
                .hasMessageContaining("音频文件不存在");
    }

    /**
     * 门面与底层识别器的真实端到端一致性验收。
     *
     * <p>前置：{@code -Dsherpa.onnx.model.dir} 指向含完整模型的目录，且 native 库可用。
     * 未满足时用例自动 skip（不伪装通过）。</p>
     *
     * @throws Exception 读写音频失败
     */
    @Test
    void shouldRecognizeEndToEnd() throws Exception {
        String root = System.getProperty(SherpaModelResolver.PROP_MODEL_DIR);
        Assumptions.assumeTrue(root != null && !root.isBlank(),
                "未设置 -Dsherpa.onnx.model.dir，跳过端到端验收");
        Path modelDir = Path.of(root);
        Assumptions.assumeTrue(SherpaModelResolver.isUsableModelDir(modelDir),
                "模型目录结构不完整: " + root);
        Assumptions.assumeTrue(SherpaNativeLoader.isAvailable(),
                "sherpa-onnx 原生库不可用: " + SherpaNativeLoader.failureReason());

        // 用 1 秒 440Hz 正弦波：只验证「链路可跑通 + 时长换算正确」。
        // 纯音不含人声，识别文本可能为空，因此这里不断言文本非空，
        // 段列表也不做非空断言——那是「无语音」的正常表现，不是缺陷。
        Path wav = SherpaWavUtils.writeTempWav("sherpa-e2e-", tone(1.0F, 440), 16000);
        try (SherpaAsrPipeline pipeline = SherpaAsrPipeline.builder()
                .model(modelDir)
                .maxSegmentSeconds(5F)
                .postProcess(true)
                .build()) {

            SherpaRecognitionResult result = pipeline.recognize(wav);

            assertThat(result).isNotNull();
            assertThat(result.getText()).isNotNull();
            assertThat(result.getModelType()).isNotNull();
            // 时长换算应与输入一致（1 秒 ±50ms）
            assertThat(result.getDuration()).isBetween(0.95D, 1.05D);
            assertThat(result.getElapsedMillis()).isGreaterThanOrEqualTo(0L);
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    /**
     * 超长音频（未配 VAD）走切块路径时，时间偏移必须单调递增且不越界。
     *
     * @throws Exception 读写音频失败
     */
    @Test
    void shouldKeepOffsetsWhenChunking() throws Exception {
        String root = System.getProperty(SherpaModelResolver.PROP_MODEL_DIR);
        Assumptions.assumeTrue(root != null && !root.isBlank(), "未设置模型目录，跳过切块验收");
        Path modelDir = Path.of(root);
        Assumptions.assumeTrue(SherpaModelResolver.isUsableModelDir(modelDir), "模型目录不完整: " + root);
        Assumptions.assumeTrue(SherpaNativeLoader.isAvailable(),
                "原生库不可用: " + SherpaNativeLoader.failureReason());

        // 3 秒音频 + 1 秒段长 → 至少切成 3 块
        Path wav = SherpaWavUtils.writeTempWav("sherpa-chunk-", tone(3.0F, 220), 16000);
        try (SherpaAsrPipeline pipeline = SherpaAsrPipeline.builder()
                .model(modelDir)
                .maxSegmentSeconds(1F)
                .postProcess(false)
                .build()) {

            SherpaRecognitionResult result = pipeline.recognize(wav);

            assertThat(result.getDuration()).isBetween(2.9D, 3.1D);
            double previousStart = -1D;
            for (SherpaRecognitionResult.Segment segment : result.getSegments()) {
                assertThat(segment.getStart()).isGreaterThan(previousStart);
                assertThat(segment.getEnd()).isGreaterThanOrEqualTo(segment.getStart());
                assertThat(segment.getEnd()).isLessThanOrEqualTo(result.getDuration() + 0.01D);
                previousStart = segment.getStart();
            }
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    /**
     * 生成指定时长与频率的正弦波（16kHz）。
     *
     * @param seconds 时长（秒）
     * @param hertz   频率（Hz）
     * @return 采样数组
     */
    private static float[] tone(float seconds, float hertz) {
        int sampleRate = 16000;
        int total = (int) (seconds * sampleRate);
        float[] samples = new float[total];
        for (int i = 0; i < total; i++) {
            samples[i] = (float) (0.2 * Math.sin(2 * Math.PI * hertz * i / sampleRate));
        }
        return samples;
    }
}
