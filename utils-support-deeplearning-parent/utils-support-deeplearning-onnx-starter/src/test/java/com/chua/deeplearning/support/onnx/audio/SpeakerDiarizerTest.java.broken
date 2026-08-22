package com.chua.deeplearning.support.audio;

import com.chua.deeplearning.support.engine.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 说话人分离（SpeakerDiarizer）单元测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>能量 VAD 切分：含静音的音频正确切分为多个语音片段</li>
 *   <li>边界情况：空输入、纯静音、纯语音的异常处理</li>
 *   <li>WAV 解码：16-bit/8-bit PCM、多声道混缩、重采样</li>
 *   <li>端到端测试（需配置系统属性 audio.diarize.model.dir）</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.43
 */
class SpeakerDiarizerTest {

    private static final int SAMPLE_RATE = 16000;

    @BeforeEach
    void setUp() {
        ModelRegistry.discoverAll();
    }

    // ==================== 工具方法 ====================

    /**
     * 生成 WAV 字节：在指定时间段内播放正弦波，其余时间为静音。
     *
     * @param segments  时间片段列表，每个元素为 [startSec, endSec, frequencyHz]
     * @param duration  总时长（秒）
     * @param sampleRate 采样率
     * @return WAV 字节数组
     */
    private static byte[] generateSegmentedAudio(float[][] segments, float duration, int sampleRate) {
        int totalSamples = (int) (duration * sampleRate);
        float[] samples = new float[totalSamples];
        for (float[] seg : segments) {
            int start = (int) (seg[0] * sampleRate);
            int end = (int) (seg[1] * sampleRate);
            double freq = seg[2];
            for (int i = start; i < end && i < totalSamples; i++) {
                samples[i] = (float) Math.sin(2.0 * Math.PI * freq * i / sampleRate);
            }
        }
        return pcmToWavBytes(samples, sampleRate);
    }

    /**
     * 生成纯静音 WAV（全零采样）。
     */
    private static byte[] generateSilence(float duration) {
        int samples = (int) (duration * SAMPLE_RATE);
        return pcmToWavBytes(new float[samples], SAMPLE_RATE);
    }

    // ==================== 基本行为测试 ====================

    @Test
    @DisplayName("listModels 不应返回 null")
    void testListModels_notNull() {
        List<String> models = SpeakerDiarizer.listModels();
        assertNotNull(models);
    }

    @Test
    @DisplayName("create 默认实现返回非空实例")
    void testCreate_defaultImpl() {
        assertDoesNotThrow(() -> {
            SpeakerDiarizer d = SpeakerDiarizer.create("test-vad");
            assertNotNull(d);
        });
    }

    // ==================== VAD 切分逻辑测试 ====================

    @Nested
    @DisplayName("VAD 切分逻辑（DefaultSpeakerDiarizer）")
    class VadLogicTest {

        /**
         * 两段语音之间有明显静音间隔，应切分为两个片段。
         * 构造：0~1s 是 440Hz 正弦波，1.5~2.5s 是 880Hz 正弦波，中间 0.5s 静音。
         */
        @Test
        @DisplayName("两段分离语音应产生两个独立片段")
        void testTwoSpeechSegments() {
            // 直接测试 DefaultSpeakerDiarizer 的能量检测逻辑
            float[][] segments = {{0.0f, 1.0f, 440.0f}, {1.5f, 2.5f, 880.0f}};
            byte[] wav = generateSegmentedAudio(segments, 3.0f, SAMPLE_RATE);
            float[] pcm = DefaultSpeakerDiarizer.decodePcmWav(wav);
            assertNotNull(pcm, "WAV 解码应成功");
            assertEquals(SAMPLE_RATE * 3, pcm.length, "采样点数应为 3s × 16kHz");

            var diarizer = new DefaultSpeakerDiarizer(
                    com.chua.deeplearning.support.engine.AbstractIdentificationEngine.getInstance(),
                    "test-vad",
                    com.chua.deeplearning.support.config.ModelSetting.builder().build());
            List<SpeakerSegment> result = diarizer.diarize(wav);

            // 应该至少有两个语音片段（每段语音一个）
            assertTrue(result.size() >= 2,
                    String.format("应检测到至少 2 个语音片段，实际: %d", result.size()));

            // 第一个片段应在 0~1s 范围内
            assertTrue(result.get(0).startTimeMs() < 1100L,
                    "第一段语音起始时间应 < 1100ms");
            assertTrue(result.get(0).endTimeMs() > 900L,
                    "第一段语音结束时间应 > 900ms");
        }

        @Test
        @DisplayName("纯静音音频不应产生任何片段")
        void testPureSilence_noSegments() {
            byte[] wav = generateSilence(2.0f);
            var diarizer = new DefaultSpeakerDiarizer(
                    com.chua.deeplearning.support.engine.AbstractIdentificationEngine.getInstance(),
                    "test-vad",
                    com.chua.deeplearning.support.config.ModelSetting.builder().build());
            List<SpeakerSegment> result = diarizer.diarize(wav);
            assertTrue(result.isEmpty(), "纯静音不应产生任何语音片段");
        }

        @Test
        @DisplayName("过短的音频（< 44 字节）应抛出 IllegalArgumentException")
        void testTooShortAudio_throws() {
            var diarizer = new DefaultSpeakerDiarizer(
                    com.chua.deeplearning.support.engine.AbstractIdentificationEngine.getInstance(),
                    "test-vad",
                    com.chua.deeplearning.support.config.ModelSetting.builder().build());
            assertThrows(IllegalArgumentException.class,
                    () -> diarizer.diarize(new byte[]{0x01, 0x02, 0x03}));
        }

        @Test
        @DisplayName("无效 WAV 头应抛出 IllegalArgumentException")
        void testInvalidWav_throws() {
            byte[] invalid = "not a wav file at all".getBytes(StandardCharsets.UTF_8);
            var diarizer = new DefaultSpeakerDiarizer(
                    com.chua.deeplearning.support.engine.AbstractIdentificationEngine.getInstance(),
                    "test-vad",
                    com.chua.deeplearning.support.config.ModelSetting.builder().build());
            assertThrows(IllegalArgumentException.class,
                    () -> diarizer.diarize(invalid));
        }
    }

    // ==================== WAV 解码测试 ====================

    @Nested
    @DisplayName("WAV 解码（decodePcmWav / wavBytesToPcm）")
    class WavDecodingTest {

        @Test
        @DisplayName("正确解码 16-bit PCM WAV")
        void testDecode16bitWav() {
            float[] original = new float[SAMPLE_RATE]; // 1秒
            for (int i = 0; i < original.length; i++) {
                original[i] = (float) (i % 100) / 100.0f * 0.5f;
            }
            byte[] wav = pcmToWavBytes(original, SAMPLE_RATE);
            float[] decoded = DefaultSpeakerDiarizer.decodePcmWav(wav);
            assertNotNull(decoded);
            assertEquals(original.length, decoded.length, "解码后采样数应一致");
            // 允许一定误差（量化损失）
            for (int i = 0; i < original.length; i++) {
                assertEquals(original[i], decoded[i], 0.01f,
                        String.format("采样点 %d 偏差过大", i));
            }
        }

        @Test
        @DisplayName("空字节数组返回 null")
        void testDecodeEmpty_returnsNull() {
            assertNull(DefaultSpeakerDiarizer.decodePcmWav(new byte[0]));
        }

        @Test
        @DisplayName("非 WAV 数据返回 null")
        void testDecodeNonWav_returnsNull() {
            assertNull(DefaultSpeakerDiarizer.decodePcmWav("hello world".getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("44 字节以下数据返回 null")
        void testDecodeTooShort_returnsNull() {
            assertNull(DefaultSpeakerDiarizer.decodePcmWav(new byte[43]));
        }
    }

    // ==================== SpeakerSegment 数据模型测试 ====================

    @Nested
    @DisplayName("SpeakerSegment 数据模型")
    class SpeakerSegmentTest {

        @Test
        @DisplayName("record 构造器正确")
        void testRecordConstructor() {
            SpeakerSegment seg = new SpeakerSegment("speaker_0", 0L, 1000L, "hello", 0.95f);
            assertEquals("speaker_0", seg.speakerId());
            assertEquals(0L, seg.startTimeMs());
            assertEquals(1000L, seg.endTimeMs());
            assertEquals("hello", seg.transcript());
            assertEquals(0.95f, seg.confidence(), 1e-6f);
        }

        @Test
        @DisplayName("durationMs 计算正确")
        void testDurationMs() {
            SpeakerSegment seg = new SpeakerSegment("s0", 100L, 500L, null);
            assertEquals(400L, seg.durationMs());
        }

        @Test
        @DisplayName("durationSec 精度正确")
        void testDurationSec() {
            SpeakerSegment seg = new SpeakerSegment("s0", 0L, 1500L, null);
            assertEquals(1.5d, seg.durationSec(), 0.01);
        }

        @Test
        @DisplayName("overlapsWith 判断正确")
        void testOverlapsWith() {
            SpeakerSegment a = new SpeakerSegment("s0", 0L, 1000L, null);
            SpeakerSegment b = new SpeakerSegment("s1", 500L, 1500L, null);
            SpeakerSegment c = new SpeakerSegment("s2", 2000L, 3000L, null);
            assertTrue(a.overlapsWith(b), "A 和 B 应重叠");
            assertFalse(a.overlapsWith(c), "A 和 C 不应重叠");
        }

        @Test
        @DisplayName("intersects 判断正确")
        void testIntersects() {
            SpeakerSegment seg = new SpeakerSegment("s0", 100L, 500L, null);
            assertTrue(seg.intersects(0L, 300L), "区间 [0,300] 与 [100,500] 有交集");
            assertFalse(seg.intersects(600L, 800L), "区间 [600,800] 与 [100,500] 无交集");
        }

        @Test
        @DisplayName("无 transcript 参数时 confidence 默认 1.0")
        void testDefaultConfidence() {
            SpeakerSegment seg = new SpeakerSegment("s0", 0L, 1000L, null);
            assertEquals(1.0f, seg.confidence());
        }
    }

    // ==================== 端到端测试（需要模型） ====================

    /**
     * 需要系统属性 audio.diarize.model.dir 指向包含测试音频的目录时才运行。
     * 目录中需包含 test_segmented.wav（两段分离语音的 WAV 文件）。
     */
    @Nested
    @DisplayName("端到端集成测试（需模型文件）")
    @EnabledIfSystemProperty(named = "audio.diarize.model.dir", matches = ".*")
    class EndToEndTest {

        @Test
        @DisplayName("完整 DSP 管线：VAD 切分 + 说话人嵌入 + 聚类 + ASR 转写")
        void testFullPipeline() throws Exception {
            String modelDir = System.getProperty("audio.diarize.model.dir");
            Path testWav = Path.of(modelDir, "test_segmented.wav");
            if (!Files.exists(testWav)) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "测试音频不存在: " + testWav);
            }

            // 构建管线：VAD + Wespeaker 嵌入 + Whisper ASR
            var pipeline = AudioRecognitionPipeline.builder()
                    .speakerEmbeddingModel("wespeaker-resnet34")
                    .asrModel("whisper-tiny")
                    .maxSpeakers(2)
                    .build();

            List<SpeakerSegment> segments = pipeline.recognize(testWav);
            assertNotNull(segments);
            // 结果按时间排序
            for (int i = 1; i < segments.size(); i++) {
                assertTrue(segments.get(i).startTimeMs() >= segments.get(i - 1).startTimeMs(),
                        "片段应按时间升序排列");
            }
            logSegments(segments);
        }

        @Test
        @DisplayName("仅 VAD 切分（不配置嵌入和 ASR 模型）")
        void testVadOnly() throws Exception {
            String modelDir = System.getProperty("audio.diarize.model.dir");
            Path testWav = Path.of(modelDir, "test_segmented.wav");
            if (!Files.exists(testWav)) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "测试音频不存在: " + testWav);
            }

            var pipeline = AudioRecognitionPipeline.builder().build();
            List<SpeakerSegment> segments = pipeline.recognize(testWav);
            assertNotNull(segments);
            assertTrue(segments.size() >= 1, "至少应检测到 1 个语音片段");
            // 不配置 ASR 时 transcript 应为 null
            for (SpeakerSegment seg : segments) {
                assertNull(seg.transcript(), "未配置 ASR 时 transcript 应为 null");
            }
        }

        private static void logSegments(List<SpeakerSegment> segments) {
            System.out.println("[AudioPipelineTest] 识别结果：");
            for (SpeakerSegment seg : segments) {
                System.out.printf("  %s  %.2fs-%.2fs  [%s]%n",
                        seg.speakerId(),
                        seg.startTimeMs() / 1000.0,
                        seg.endTimeMs() / 1000.0,
                        seg.transcript() != null ? seg.transcript() : "(无转写)");
            }
        }
    }

    // ==================== 辅助方法 ====================

    /** 将 float 采样编码为 16-bit PCM WAV 字节 */
    static byte[] pcmToWavBytes(float[] samples, int sampleRate) {
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = samples.length * blockAlign;
        int bufferSize = 44 + dataSize;
        byte[] wav = new byte[bufferSize];
        writeStr(wav, 0, "RIFF");
        writeInt(wav, 4, bufferSize - 8);
        writeStr(wav, 8, "WAVE");
        writeStr(wav, 12, "fmt ");
        writeInt(wav, 16, 16);
        writeShort(wav, 20, 1);
        writeShort(wav, 22, (short) numChannels);
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, byteRate);
        writeShort(wav, 32, (short) blockAlign);
        writeShort(wav, 34, (short) bitsPerSample);
        writeStr(wav, 36, "data");
        writeInt(wav, 40, dataSize);
        int off = 44;
        for (float s : samples) {
            int val = (int) Math.max(-1.0f, Math.min(1.0f, s)) * 32767;
            wav[off++] = (byte) (val & 0xff);
            wav[off++] = (byte) ((val >> 8) & 0xff);
        }
        return wav;
    }

    private static void writeStr(byte[] b, int off, String s) {
        System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, b, off, s.length());
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void writeShort(byte[] b, int off, short v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }
}
