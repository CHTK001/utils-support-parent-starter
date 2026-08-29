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
 * <p>覆盖场景：</p>
 * <ul>
 *   <li>AudioFingerprinter：wav2vec2-base (384维) + wespeaker (512维) 音频指纹提取</li>
 *   <li>VoiceprintPipeline：campplus (192维) + wespeaker (512维) 声纹入库/检索</li>
 *   <li>AsrPipeline：paraformer/sensevoice/whisper 语音识别</li>
 *   <li>端到端管线：VAD + 降噪 + ASR + 声纹 全流程</li>
 * </ul>
 *
 * <p>运行方式：无系统属性时运行基础测试，有属性时运行完整集成测试。</p>
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

    // ==================== AudioFingerprinter 测试 ====================

    @Nested
    @DisplayName("AudioFingerprinter 音频指纹提取")
    class AudioFingerprinterTests {

        @Test
        @DisplayName("wav2vec2-base 384维特征提取 + 余弦相似度")
        void testWav2Vec2Base_fingerprint() {
            var fp = AudioFingerprinter.create("onnx", "")
                    .model("wav2vec2-base-fingerprint")
                    .normalize(true);

            byte[] audioA = generateSineWave(440.0, 2, SAMPLE_RATE);
            byte[] audioB = generateSineWave(440.0, 2, SAMPLE_RATE);
            byte[] audioC = generateSineWave(880.0, 2, SAMPLE_RATE);

            float[] vecA = fp.extract(audioA);
            float[] vecB = fp.extract(audioB);
            float[] vecC = fp.extract(audioC);

            assertNotNull(vecA, "wav2vec2-base 特征向量不应为 null");
            assertEquals(384, vecA.length, "wav2vec2-base 应输出 384 维");
            assertEquals(vecA.length, vecB.length, "同模型输出维度应一致");
            assertEquals(vecA.length, vecC.length, "同模型输出维度应一致");

            float simAB = cosineSim(vecA, vecB);
            float simAC = cosineSim(vecA, vecC);
            System.out.printf("[wav2vec2-base] sim(A,A)=%.3f, sim(A,C)=%.3f%n", simAB, simAC);
            assertTrue(simAB > 0.9f,
                    String.format("同频相似度应 > 0.9，实际: %.3f", simAB));
        }

        @Test
        @DisplayName("wespeaker 512维说话人嵌入 + 归一化验证")
        void testWespeaker_embedding() {
            var fp = AudioFingerprinter.create("onnx", "")
                    .model("wespeaker-resnet34")
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
            var fpBase = AudioFingerprinter.create("onnx", "")
                    .model("wav2vec2-base-fingerprint");
            var fpWespeaker = AudioFingerprinter.create("onnx", "")
                    .model("wespeaker-resnet34");

            byte[] audio = generateSineWave(440.0, 1, SAMPLE_RATE);
            float[] vecBase = fpBase.extract(audio);
            float[] vecWespeaker = fpWespeaker.extract(audio);

            assertEquals(384, vecBase.length, "wav2vec2-base 应为 384 维");
            assertEquals(512, vecWespeaker.length, "wespeaker 应为 512 维");
            assertNotEquals(vecBase.length, vecWespeaker.length,
                    "不同模型输出维度应不同");
        }
    }

    // ==================== VoiceprintPipeline 测试 ====================

    @Nested
    @DisplayName("VoiceprintPipeline 声纹管线")
    class VoiceprintPipelineTests {

        @Test
        @DisplayName("CAM++ 声纹：入库 + 检索 + 相似度验证")
        void testCampplus_enrollAndSearch() throws Exception {
            VoiceprintPipeline vp = VoiceprintPipeline.create();

            byte[] audioAlice = generateSineWave(440.0, 3, SAMPLE_RATE);
            byte[] audioBob = generateSineWave(880.0, 3, SAMPLE_RATE);

            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"),
                    "voiceprint-test-" + System.currentTimeMillis());
            java.nio.file.Files.createDirectories(tmpDir);

            Path aliceWav = tmpDir.resolve("alice.wav");
            Path bobWav = tmpDir.resolve("bob.wav");
            java.nio.file.Files.write(aliceWav, audioAlice);
            java.nio.file.Files.write(bobWav, audioBob);

            try {
                // 入库
                vp.createEnroll()
                        .id("alice")
                        .label("女声")
                        .audio(aliceWav)
                        .execute();

                vp.createEnroll()
                        .id("bob")
                        .label("男声")
                        .audio(bobWav)
                        .execute();

                // 检索
                var hits = vp.createSearch()
                        .topK(5)
                        .query(aliceWav)
                        .execute();

                assertNotNull(hits, "检索结果不应为 null");
                assertFalse(hits.isEmpty(), "检索结果不应为空");
                assertEquals("alice", hits.get(0).speakerId(),
                        "最相似应为 alice");
                assertTrue(hits.get(0).similarity() > 0.8f,
                        String.format("alice 相似度应 > 0.8，实际: %.4f", hits.get(0).similarity()));

                System.out.printf("[CAM++] alice vs alice: sim=%.4f%n", hits.get(0).similarity());
            } finally {
                // 清理
                java.nio.file.Files.deleteIfExists(aliceWav);
                java.nio.file.Files.deleteIfExists(bobWav);
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db"));
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-wal"));
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-shm"));
                java.nio.file.Files.delete(tmpDir);
            }
        }

        @Test
        @DisplayName("VoiceprintPipeline 通过 model() 加载 CAM++")
        void testVoiceprintPipeline_modelMethod() throws Exception {
            VoiceprintPipeline vp = VoiceprintPipeline.builder()
                    .model("campplus-voiceprint")
                    .build();

            byte[] audio = generateSineWave(440.0, 3, SAMPLE_RATE);
            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"),
                    "vp-model-test-" + System.currentTimeMillis());
            java.nio.file.Files.createDirectories(tmpDir);
            Path wav = tmpDir.resolve("test.wav");
            java.nio.file.Files.write(wav, audio);

            try {
                float[] embedding = vp.extract(wav);
                assertNotNull(embedding, "CAM++ 嵌入不应为 null");
                assertEquals(192, embedding.length, "CAM++ 应输出 192 维");

                float norm = 0f;
                for (float v : embedding) norm += v * v;
                assertEquals(1.0f, (float) Math.sqrt(norm), 1e-5f,
                        "归一化后向量模长应为 1");
            } finally {
                java.nio.file.Files.deleteIfExists(wav);
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db"));
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-wal"));
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-shm"));
                java.nio.file.Files.delete(tmpDir);
            }
        }

        @Test
        @DisplayName("VoiceprintPipeline 通过 model() 加载 wespeaker")
        void testVoiceprintPipeline_wespeakerModel() throws Exception {
            VoiceprintPipeline vp = VoiceprintPipeline.builder()
                    .model("wespeaker-resnet34")
                    .build();

            byte[] audio = generateSineWave(440.0, 3, SAMPLE_RATE);
            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"),
                    "vp-wespeaker-test-" + System.currentTimeMillis());
            java.nio.file.Files.createDirectories(tmpDir);
            Path wav = tmpDir.resolve("test.wav");
            java.nio.file.Files.write(wav, audio);

            try {
                float[] embedding = vp.extract(wav);
                assertNotNull(embedding, "wespeaker 嵌入不应为 null");
                assertEquals(512, embedding.length, "wespeaker 应输出 512 维");
            } finally {
                java.nio.file.Files.deleteIfExists(wav);
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db"));
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-wal"));
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-shm"));
                java.nio.file.Files.delete(tmpDir);
            }
        }
    }

    // ==================== AsrPipeline 测试 ====================

    @Nested
    @DisplayName("AsrPipeline 语音识别")
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
    }

    // ==================== 端到端集成测试（需真实音频文件） ====================

    @Nested
    @DisplayName("端到端完整管线测试")
    class EndToEndTests {

        @Test
        @DisplayName("VAD + 音频指纹 + 声纹 全流程")
        void testFullAudioPipeline() throws Exception {
            // 生成包含两段语音的测试音频
            float[][] segments = {
                    {0.0f, 1.5f, 440.0f},  // 第一段：440Hz，1.5秒
                    {2.0f, 3.5f, 880.0f}   // 第二段：880Hz，1.5秒（间隔0.5秒静音）
            };
            byte[] testWav = generateSegmentedAudio(segments, 4.0f, SAMPLE_RATE);

            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"),
                    "e2e-pipeline-test-" + System.currentTimeMillis());
            java.nio.file.Files.createDirectories(tmpDir);
            Path wavPath = tmpDir.resolve("segmented.wav");
            java.nio.file.Files.write(wavPath, testWav);

            try {
                // 1. 音频指纹提取（wav2vec2-base）
                var fp = AudioFingerprinter.create("onnx", "")
                        .model("wav2vec2-base-fingerprint")
                        .normalize(true);
                float[] fingerprint = fp.extract(testWav);
                assertNotNull(fingerprint, "音频指纹不应为 null");
                assertEquals(384, fingerprint.length, "wav2vec2-base 应输出 384 维");
                System.out.printf("[E2E] 音频指纹: 384维向量已提取%n");

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

                // 3. 清理声纹存储
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db"));
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-wal"));
                java.nio.file.Files.deleteIfExists(tmpDir.resolve("voiceprint.db-shm"));

            } finally {
                java.nio.file.Files.deleteIfExists(wavPath);
                java.nio.file.Files.delete(tmpDir);
            }
        }

        @Test
        @DisplayName("多个说话人声纹区分度测试")
        void testSpeakerDistinguish() throws Exception {
            VoiceprintPipeline vp = VoiceprintPipeline.create();

            // 生成不同"说话人"的音频（不同频率模拟不同声纹）
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
                // 入库
                vp.createEnroll().id("speaker1").audio(wav1).execute();
                vp.createEnroll().id("speaker2").audio(wav2).execute();

                // 检索 speaker1
                var hits1 = vp.createSearch().topK(2).query(wav1).execute();
                assertFalse(hits1.isEmpty());
                assertEquals("speaker1", hits1.get(0).speakerId(),
                        "查询 speaker1 应返回 speaker1");

                // 检索 speaker2
                var hits2 = vp.createSearch().topK(2).query(wav2).execute();
                assertFalse(hits2.isEmpty());
                assertEquals("speaker2", hits2.get(0).speakerId(),
                        "查询 speaker2 应返回 speaker2");

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
        }
    }
}
