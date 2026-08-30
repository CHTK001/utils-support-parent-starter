package com.chua.deeplearning.support.onnx.audio;

import com.chua.deeplearning.support.audio.AudioFingerprinter;
import com.chua.deeplearning.support.engine.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 音频管线完整集成测试。
 *
 * <p>基础测试（无模型）：AsrPipeline 构建、接口签名验证。</p>
 * <p>集成测试（需模型 JAR）：通过 -DskipTests=false + 模型模块在 classpath 运行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class AudioPipelineFullTest {

    private static final int SAMPLE_RATE = 16000;

    @BeforeEach
    void setUp() {
        ModelRegistry.discoverAll();
    }

    // ==================== 工具方法 ====================

    private static byte[] generateSineWave(double frequencyHz, int durationSec, int sampleRate) {
        int numSamples = sampleRate * durationSec;
        float[] samples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate);
        }
        return pcmToWavBytes(samples, sampleRate);
    }

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

    private static byte[] pcmToWavBytes(float[] samples, int sampleRate) {
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
        writeShort(wav, 20, (short) 1);
        writeShort(wav, 22, (short) numChannels);
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, byteRate);
        writeShort(wav, 32, (short) blockAlign);
        writeShort(wav, 34, (short) bitsPerSample);
        writeStr(wav, 36, "data");
        writeInt(wav, 40, dataSize);
        int off = 44;
        for (float s : samples) {
            int val = (int) (Math.max(-1.0f, Math.min(1.0f, s)) * 32767);
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

    private static float cosineSim(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) return 0f;
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA < 1e-12f || normB < 1e-12f) return 0f;
        return dot / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ==================== AsrPipeline 基础构建测试（无模型） ====================

    @Nested
    @DisplayName("AsrPipeline 语音识别构建")
    class AsrPipelineTests {

        @Test
        @DisplayName("AsrPipeline 构建不抛异常")
        void testAsrPipeline_builderNoThrow() {
            assertDoesNotThrow(() -> {
                AsrPipeline pipeline = AsrPipeline.builder("paraformer-zh-small")
                        .postProcess(true)
                        .build();
                assertNotNull(pipeline);
            });
        }

        @Test
        @DisplayName("AsrPipeline 带 VAD 构建不抛异常")
        void testAsrPipeline_withVad() {
            assertDoesNotThrow(() -> {
                AsrPipeline pipeline = AsrPipeline.builder("paraformer-zh-small")
                        .vad("energy")
                        .postProcess(true)
                        .build();
                assertNotNull(pipeline);
            });
        }

        @Test
        @DisplayName("AsrPipeline 带降噪构建不抛异常")
        void testAsrPipeline_withDenoise() {
            assertDoesNotThrow(() -> {
                AsrPipeline pipeline = AsrPipeline.builder("paraformer-zh-small")
                        .denoise("dfsmn-ans")
                        .postProcess(true)
                        .build();
                assertNotNull(pipeline);
            });
        }

        @Test
        @DisplayName("AsrPipeline 全管线构建不抛异常")
        void testAsrPipeline_fullConfig() {
            assertDoesNotThrow(() -> {
                AsrPipeline pipeline = AsrPipeline.builder("paraformer-zh-small")
                        .vad("energy")
                        .denoise("dfsmn-ans")
                        .language("zh")
                        .postProcess(true)
                        .build();
                assertNotNull(pipeline);
            });
        }
    }

    // ==================== VoiceprintPipeline 构建测试（无模型） ====================

    @Nested
    @DisplayName("VoiceprintPipeline 声纹管线构建")
    class VoiceprintPipelineTests {

        @Test
        @DisplayName("VoiceprintPipeline 默认构建不抛异常")
        void testVoiceprintPipeline_defaultBuild() {
            assertDoesNotThrow(() -> {
                VoiceprintPipeline vp = VoiceprintPipeline.builder().build();
                assertNotNull(vp);
            });
        }

        @Test
        @DisplayName("VoiceprintPipeline 带 VAD 构建不抛异常")
        void testVoiceprintPipeline_withVad() {
            assertDoesNotThrow(() -> {
                VoiceprintPipeline vp = VoiceprintPipeline.builder()
                        .vad("energy")
                        .build();
                assertNotNull(vp);
            });
        }

        @Test
        @DisplayName("VoiceprintPipeline 带降噪构建不抛异常")
        void testVoiceprintPipeline_withDenoise() {
            assertDoesNotThrow(() -> {
                VoiceprintPipeline vp = VoiceprintPipeline.builder()
                        .denoise("dfsmn-ans")
                        .build();
                assertNotNull(vp);
            });
        }

        @Test
        @DisplayName("VoiceprintPipeline 全配置构建不抛异常")
        void testVoiceprintPipeline_fullConfig() {
            assertDoesNotThrow(() -> {
                VoiceprintPipeline vp = VoiceprintPipeline.builder()
                        .vad("energy")
                        .denoise("dfsmn-ans")
                        .max(50)
                        .build();
                assertNotNull(vp);
            });
        }
    }

    // ==================== AudioFingerprinter 接口测试（无模型） ====================

    @Nested
    @DisplayName("AudioFingerprinter 接口签名")
    class AudioFingerprinterTests {

        @Test
        @DisplayName("listModels 不应返回 null")
        void testListModels_notNull() {
            List<String> models = AudioFingerprinter.listModels();
            assertNotNull(models, "listModels() 不应返回 null");
        }

        @Test
        @DisplayName("create 默认实现返回非空实例")
        void testCreate_defaultImpl() {
            assertDoesNotThrow(() -> {
                AudioFingerprinter fp = AudioFingerprinter.create("test-model");
                assertNotNull(fp);
            });
        }

        @Test
        @DisplayName("normalize 链式调用返回 this")
        void testNormalize_chainReturnsThis() {
            var fp = AudioFingerprinter.create("test");
            assertSame(fp, fp.normalize(true));
            assertSame(fp, fp.normalize(false));
        }
    }

    // ==================== 端到端集成测试（需要模型 JAR 在 classpath） ====================

    @Nested
    @DisplayName("端到端完整管线测试（需模型）")
    class EndToEndTests {

    @Test
    @DisplayName("wav2vec2-base 特征提取 + 余弦相似度")
    void testWav2Vec2Base_fingerprint() {
        assumeModelsRegistered();

        var fp = AudioFingerprinter.create("wav2vec2-base-fingerprint")
                .normalize(true);

        byte[] audioA = generateSineWave(440.0, 2, SAMPLE_RATE);
        byte[] audioB = generateSineWave(440.0, 2, SAMPLE_RATE);
        byte[] audioC = generateSineWave(880.0, 2, SAMPLE_RATE);

        float[] vecA = fp.extract(audioA);
        float[] vecB = fp.extract(audioB);
        float[] vecC = fp.extract(audioC);

        assertNotNull(vecA, "wav2vec2-base 特征向量不应为 null");
        assertEquals(32, vecA.length, "wav2vec2-base-960h ASR head 输出 32 维 vocab");
        assertEquals(vecA.length, vecB.length, "同模型输出维度应一致");
        assertEquals(vecA.length, vecC.length, "同模型输出维度应一致");

        float simAB = cosineSim(vecA, vecB);
        float simAC = cosineSim(vecA, vecC);
        System.out.printf("[wav2vec2-base] dim=%d, sim(A,A)=%.3f, sim(A,C)=%.3f%n", vecA.length, simAB, simAC);
        assertTrue(simAB > 0.9f,
                String.format("同频相似度应 > 0.9，实际: %.3f", simAB));
    }

        @Test
        @DisplayName("wespeaker 512维说话人嵌入 + 归一化验证")
        void testWespeaker_embedding() {
            assumeModelsRegistered();

            var fp = AudioFingerprinter.create("wespeaker-resnet34")
                    .normalize(true);

            byte[] audio = generateSineWave(440.0, 3, SAMPLE_RATE);
            float[] vec = fp.extract(audio);

            assertNotNull(vec, "wespeaker 特征向量不应为 null");
            assertEquals(512, vec.length, "wespeaker 应输出 512 维");

            float norm = 0f;
            for (float v : vec) norm += v * v;
            assertEquals(1.0f, (float) Math.sqrt(norm), 1e-5f,
                    "归一化后向量模长应为 1");
        }

        @Test
        @DisplayName("不同模型输出维度不同")
        void testDifferentModels_differentDimensions() {
            assumeModelsRegistered();

            var fpBase = AudioFingerprinter.create("wav2vec2-base-fingerprint");
            var fpWespeaker = AudioFingerprinter.create("wespeaker-resnet34");

            byte[] audio = generateSineWave(440.0, 1, SAMPLE_RATE);
            float[] vecBase = fpBase.extract(audio);
            float[] vecWespeaker = fpWespeaker.extract(audio);

            assertEquals(32, vecBase.length, "wav2vec2-base-960h ASR head 输出 32 维 vocab");
            assertEquals(512, vecWespeaker.length, "wespeaker 应为 512 维");
            assertNotEquals(vecBase.length, vecWespeaker.length,
                    "不同模型输出维度应不同");
        }

        @Test
        @DisplayName("VAD + 音频指纹 + 声纹 全流程")
        void testFullAudioPipeline() {
            assumeModelsRegistered();

            float[][] segments = {
                    {0.0f, 1.5f, 440.0f},
                    {2.0f, 3.5f, 880.0f}
            };
            byte[] testWav = generateSegmentedAudio(segments, 4.0f, SAMPLE_RATE);

            try {
                Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"),
                        "e2e-pipeline-test-" + System.currentTimeMillis());
                java.nio.file.Files.createDirectories(tmpDir);
                Path wavPath = tmpDir.resolve("segmented.wav");
                java.nio.file.Files.write(wavPath, testWav);

                try {
                    // 1. 音频指纹提取
                    var fp = AudioFingerprinter.create("wav2vec2-base-fingerprint")
                            .normalize(true);
                    float[] fingerprint = fp.extract(testWav);
                    assertNotNull(fingerprint, "音频指纹不应为 null");
                    assertEquals(32, fingerprint.length, "wav2vec2-base-960h ASR head 输出 32 维 vocab");
                    System.out.printf("[E2E] 音频指纹: %d 维向量已提取%n", fingerprint.length);

                    // 2. 声纹入库 + 检索
                    VoiceprintPipeline vp = VoiceprintPipeline.create();
                    vp.createEnroll()
                            .id("test-speaker")
                            .label("测试说话人")
                            .audio(wavPath)
                            .execute();

                    var hits = vp.createSearch()
                            .topK(1)
                            .query(wavPath)
                            .execute();

                    assertFalse(hits.isEmpty(), "声纹检索不应为空");
                    assertEquals("test-speaker", hits.get(0).speakerId());
                    assertTrue(hits.get(0).similarity() > 0.9f,
                            String.format("自匹配相似度应 > 0.9，实际: %.4f", hits.get(0).similarity()));
                    System.out.printf("[E2E] 声纹检索: %s sim=%.4f%n",
                            hits.get(0).speakerId(), hits.get(0).similarity());

                    java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db"));
                    java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-wal"));
                    java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-shm"));
                } finally {
                    java.nio.file.Files.deleteIfExists(wavPath);
                    java.nio.file.Files.delete(tmpDir);
                }
            } catch (Exception e) {
                fail("端到端测试异常: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("多个说话人声纹区分度测试")
        void testSpeakerDistinguish() {
            assumeModelsRegistered();

            try {
                VoiceprintPipeline vp = VoiceprintPipeline.create();

                byte[] audioSpeaker1 = generateSineWave(300.0, 3, SAMPLE_RATE);
                byte[] audioSpeaker2 = generateSineWave(600.0, 3, SAMPLE_RATE);

                Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"),
                        "speaker-dist-test-" + System.currentTimeMillis());
                java.nio.file.Files.createDirectories(tmpDir);
                Path wav1 = tmpDir.resolve("speaker1.wav");
                Path wav2 = tmpDir.resolve("speaker2.wav");
                java.nio.file.Files.write(wav1, audioSpeaker1);
                java.nio.file.Files.write(wav2, audioSpeaker2);

                try {
                    vp.createEnroll().id("speaker1").audio(wav1).execute();
                    vp.createEnroll().id("speaker2").audio(wav2).execute();

                    var hits1 = vp.createSearch().topK(2).query(wav1).execute();
                    assertFalse(hits1.isEmpty());
                    assertEquals("speaker1", hits1.get(0).speakerId());

                    var hits2 = vp.createSearch().topK(2).query(wav2).execute();
                    assertFalse(hits2.isEmpty());
                    assertEquals("speaker2", hits2.get(0).speakerId());

                    System.out.printf("[E2E] 说话人区分: s1→%s sim=%.4f, s2→%s sim=%.4f%n",
                            hits1.get(0).speakerId(), hits1.get(0).similarity(),
                            hits2.get(0).speakerId(), hits2.get(0).similarity());
                } finally {
                    java.nio.file.Files.deleteIfExists(wav1);
                    java.nio.file.Files.deleteIfExists(wav2);
                    java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db"));
                    java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-wal"));
                    java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-shm"));
                    java.nio.file.Files.delete(tmpDir);
                }
            } catch (Exception e) {
                fail("说话人区分测试异常: " + e.getMessage());
            }
        }

        private void assumeModelsRegistered() {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    !AudioFingerprinter.listModels().isEmpty(),
                    "模型未注册（ModelRegistry entries=0），跳过集成测试。需模型 JAR 在 classpath。");
        }
    }
}
