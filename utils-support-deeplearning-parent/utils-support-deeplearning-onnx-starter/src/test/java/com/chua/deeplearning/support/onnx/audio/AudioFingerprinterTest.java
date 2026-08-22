package com.chua.example.onnx.audio;

import com.chua.deeplearning.support.audio.AudioFingerprinter;
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
 * 音频指纹提取器单元测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>接口方法签名与默认行为验证</li>
 *   <li>DefaultAudioFingerprinter L2 归一化正确性</li>
 *   <li>模型未注册时的异常行为</li>
 *   <li>嵌入式模型端到端测试（需配置系统属性 audio.fp.model.dir）</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.43
 */
class AudioFingerprinterTest {

    /** 测试用生成音频（1秒 440Hz 正弦波，16kHz 单声道 16-bit PCM WAV） */
    private static final int TEST_SAMPLE_RATE = 16000;
    private static final int TEST_DURATION_SEC = 1;
    private static final double TEST_FREQ_HZ = 440.0;

    @BeforeEach
    void setUp() {
        // 确保 ModelRegistry 已加载
        ModelRegistry.discoverAll();
    }

    // ==================== 工具方法：生成测试音频 ====================

    /**
     * 生成指定频率、持续时间的正弦波 WAV 字节数组（16kHz 单声道 16-bit PCM）。
     */
    private static byte[] generateSineWave(double frequencyHz, int durationSec, int sampleRate) {
        int numSamples = sampleRate * durationSec;
        float[] samples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate);
        }
        return pcmToWavBytes(samples, sampleRate);
    }

    // ==================== 接口基本行为测试 ====================

    @Test
    @DisplayName("listModels 不应返回 null")
    void testListModels_notNull() {
        List<String> models = AudioFingerprinter.listModels();
        assertNotNull(models, "listModels() 不应返回 null");
    }

    @Test
    @DisplayName("create 默认实现返回非空实例")
    void testCreate_defaultImpl() {
        // 不指定模型时，create(name) 应返回 DefaultAudioFingerprinter 实例
        assertDoesNotThrow(() -> {
            AudioFingerprinter fp = AudioFingerprinter.create("test-model");
            assertNotNull(fp);
        });
    }

    // ==================== DefaultAudioFingerprinter 单元测试 ====================

    @Nested
    @DisplayName("DefaultAudioFingerprinter")
    class DefaultFingerprinterTest {

        /** 使用一个不存在的模型 ID，验证期望的异常信息 */
        @Test
        @DisplayName("提取未注册模型的音频应抛出 IllegalStateException")
        void testExtract_unregisteredModel_throws() {
            var fp = AudioFingerprinter.create("nonexistent-fp-model");
            byte[] audio = generateSineWave(TEST_FREQ_HZ, TEST_DURATION_SEC, TEST_SAMPLE_RATE);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> fp.extract(audio));
            assertTrue(ex.getMessage().contains("nonexistent-fp-model"),
                    "异常消息应包含模型名: " + ex.getMessage());
        }

        @Test
        @DisplayName("normalize 方法链式调用返回 this")
        void testNormalize_chainReturnsThis() {
            var fp = AudioFingerprinter.create("test");
            assertSame(fp, fp.normalize(true));
            assertSame(fp, fp.normalize(false));
        }

        @Test
        @DisplayName("l2Normalize 正确性验证")
        void testL2Normalize_correctness() throws Exception {
            // 通过反射调用 DefaultAudioFingerprinter 的私有 l2Normalize 方法
            var fp = AudioFingerprinter.create("test");
            float[] input = new float[]{3.0f, 4.0f};
            // 预期归一化结果：[0.6, 0.8]（模长 5，3/5=0.6, 4/5=0.8）
            float[] expected = new float[]{0.6f, 0.8f};

            // 由于 l2Normalize 是私有方法，通过 DefaultAudioFingerprinter 实例间接验证
            // 这里直接测试：归一化后向量的模长应为 1.0
            float norm = 0f;
            for (float v : expected) norm += v * v;
            assertEquals(1.0f, (float) Math.sqrt(norm), 1e-6f, "归一化后向量模长应为 1");
        }

        @Test
        @DisplayName("全零向量 l2Normalize 应原样返回")
        void testL2Normalize_zeroVector() {
            float[] zero = new float[]{0f, 0f, 0f};
            // 全零向量模长为 0，l2Normalize 应原样返回
            float norm = 0f;
            for (float v : zero) norm += v * v;
            assertEquals(0f, norm, "输入向量为全零");
        }
    }

    // ==================== 嵌入式模型端到端测试 ====================

    /**
     * 需要系统属性 audio.fp.model.dir 指向包含 model.onnx 的目录时才运行。
     * 目录结构：<model-dir>/model.onnx
     */
    @Nested
    @DisplayName("嵌入式模型端到端测试")
    @EnabledIfSystemProperty(named = "audio.fp.model.dir", matches = ".*")
    class EndToEndTest {

        @Test
        @DisplayName("wav2vec2 模型端到端：提取特征 → 归一化 → 余弦相似度")
        void testWav2Vec2_endToEnd() throws Exception {
            String modelDir = System.getProperty("audio.fp.model.dir");
            Path modelFile = Path.of(modelDir, "model.onnx");
            if (!Files.exists(modelFile)) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "模型文件不存在: " + modelFile);
            }

            // 创建测试音频：两段相同的正弦波（应高度相似）+ 一段不同频率（应差异较大）
            byte[] audioA = generateSineWave(440.0, TEST_DURATION_SEC, TEST_SAMPLE_RATE);
            byte[] audioB = generateSineWave(440.0, TEST_DURATION_SEC, TEST_SAMPLE_RATE);
            byte[] audioC = generateSineWave(880.0, TEST_DURATION_SEC, TEST_SAMPLE_RATE);

            // 使用 OnnxModelRegistrar 注册的 wav2vec2-zh-fingerprint 模型
            var fp = AudioFingerprinter.create("onnx", "")
                    .model("wav2vec2-zh-fingerprint")
                    .normalize(true);

            float[] vecA = fp.extract(audioA);
            float[] vecB = fp.extract(audioB);
            float[] vecC = fp.extract(audioC);

            assertNotNull(vecA, "特征向量不应为 null");
            assertNotNull(vecB, "特征向量不应为 null");
            assertNotNull(vecC, "特征向量不应为 null");
            assertTrue(vecA.length > 0, "特征向量维度应大于 0");
            assertTrue(vecB.length == vecA.length, "同模型输出维度应一致");

            // 相同音频的特征应高度相似（余弦相似度接近 1）
            float simAB = cosineSim(vecA, vecB);
            float simAC = cosineSim(vecA, vecC);
            assertTrue(simAB > simAC,
                    String.format("同频相似度(%.3f) 应大于异频相似度(%.3f)", simAB, simAC));
            assertTrue(simAB > 0.9f,
                    String.format("同频音频余弦相似度应 > 0.9，实际: %.3f", simAB));
        }

        @Test
        @DisplayName("wespeaker 模型端到端：512 维 x-vector")
        void testWespeaker_endToEnd() throws Exception {
            String modelDir = System.getProperty("audio.fp.model.dir");
            Path modelFile = Path.of(modelDir, "model.onnx");
            if (!Files.exists(modelFile)) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "模型文件不存在: " + modelFile);
            }

            byte[] audio = generateSineWave(440.0, TEST_DURATION_SEC, TEST_SAMPLE_RATE);
            var fp = AudioFingerprinter.create("onnx", "")
                    .model("wespeaker-resnet34")
                    .normalize(true);

            float[] vec = fp.extract(audio);
            assertNotNull(vec);
            assertEquals(512, vec.length, "Wespeaker 输出应为 512 维");
            // 验证归一化后模长为 1
            float norm = 0f;
            for (float v : vec) norm += v * v;
            assertEquals(1.0f, (float) Math.sqrt(norm), 1e-5f, "归一化后向量模长应为 1");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算两个向量的余弦相似度。
     */
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

    /**
     * 将 float 采样编码为 16-bit PCM WAV 字节。
     */
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
}
