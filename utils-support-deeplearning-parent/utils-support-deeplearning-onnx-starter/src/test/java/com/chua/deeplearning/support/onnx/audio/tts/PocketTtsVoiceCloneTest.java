package com.chua.deeplearning.support.onnx.audio.tts;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pocket-TTS 声音克隆测试。
 *
 * <p>测试分两层：
 * <ul>
 *   <li>{@link #testSupportsVoiceClone()} — 验证 mimi_encoder 已嵌入，克隆能力可用（不加载模型）</li>
 *   <li>{@link #testDefaultVoiceSynthesis()} — 默认音色合成（需足够内存）</li>
 *   <li>{@link #testVoiceClone()} — 完整声音克隆流程（需足够内存）</li>
 * </ul>
 * 内存不足时后两个测试自动跳过。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class PocketTtsVoiceCloneTest {

    private static final String TEST_TEXT = "This is a voice cloning test sentence.";
    private static final String CLONE_TEXT = "Hello, can you hear me clearly now?";

    /** 最小可用堆内存阈值（字节），低于此值跳过推理测试 */
    private static final long MIN_HEAP_FREE_BYTES = 512L * 1024 * 1024; // 512MB

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    /**
     * 检查是否有足够内存运行推理测试。
     */
    private boolean hasEnoughMemory() {
        long freeHeap = MEMORY_BEAN.getHeapMemoryUsage().getUsed();
        long committed = MEMORY_BEAN.getHeapMemoryUsage().getCommitted();
        long available = committed - freeHeap;
        return available >= MIN_HEAP_FREE_BYTES;
    }

    /**
     * 测试 1：验证 mimi_encoder 已嵌入 JAR，声音克隆能力就绪。
     */
    @Test
    void testSupportsVoiceClone() throws Exception {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        try {
            // 先触发 prepare()（通过合成一个短文本）
            translator.synthesize("Hi");
            assertTrue(translator.supportsVoiceClone(),
                    "mimi_encoder.onnx 未嵌入 JAR，声音克隆不可用。请运行 scripts/fetch-pocket-tts.ps1 并重新构建");
            System.out.println("[VoiceClone] supportsVoiceClone() = true, mimi_encoder 就绪");
        } finally {
            translator.close();
        }
    }

    /**
     * 测试 2：默认音色合成（内存充足时运行）。
     */
    @Test
    void testDefaultVoiceSynthesis() throws Exception {
        if (!hasEnoughMemory()) {
            System.out.println("[VoiceClone] 内存不足，跳过默认音色合成测试");
            return;
        }
        PocketTtsTranslator translator = new PocketTtsTranslator();
        try {
            byte[] wav = translator.synthesize(TEST_TEXT);
            assertNotNull(wav, "默认音色合成结果不应为 null");
            assertTrue(wav.length > 100, "WAV 应大于 100 字节，实际: " + wav.length);
            assertEquals('R', (char) wav[0], "WAV 文件应以 RIFF 头开始");
            assertEquals('W', (char) wav[8], "WAV 格式标识应为 WAVE");
            System.out.printf("[VoiceClone] Default voice: %d bytes%n", wav.length);
            Files.write(Path.of(System.getProperty("java.io.tmpdir"), "pocket-tts-default.wav"), wav);
        } finally {
            translator.close();
        }
    }

    /**
     * 测试 3：完整声音克隆流程（内存充足时运行）。
     *
     * <p>默认音色合成参考音频 → 克隆到新文本 → 验证输出。</p>
     */
    @Test
    void testVoiceClone() throws Exception {
        if (!hasEnoughMemory()) {
            System.out.println("[VoiceClone] 内存不足，跳过克隆测试");
            return;
        }
        PocketTtsTranslator translator = new PocketTtsTranslator();
        try {
            // Step 1: 生成参考音频
            byte[] refWav = translator.synthesize(TEST_TEXT);
            assertNotNull(refWav, "参考音频合成失败");
            System.out.printf("[VoiceClone] Reference audio: %d bytes%n", refWav.length);

            // Step 2: 克隆合成
            byte[] clonedWav = translator.voice(CLONE_TEXT, refWav);
            assertNotNull(clonedWav, "克隆合成结果不应为 null");
            assertTrue(clonedWav.length > 100, "克隆 WAV 应大于 100 字节，实际: " + clonedWav.length);
            assertEquals('R', (char) clonedWav[0], "克隆 WAV 应以 RIFF 头开始");
            System.out.printf("[VoiceClone] Cloned audio: %d bytes%n", clonedWav.length);

            Path clonePath = Path.of(System.getProperty("java.io.tmpdir"), "pocket-tts-cloned.wav");
            Files.write(clonePath, clonedWav);
        } finally {
            translator.close();
        }
    }

    /**
     * 测试 4：空参考音频回退到默认音色。
     */
    @Test
    void testFallbackToDefaultVoice() throws Exception {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        try {
            byte[] result = translator.voice(TEST_TEXT, null);
            assertNotNull(result, "null 参考音频应回退默认音色");
            assertTrue(result.length > 100, "默认音色输出应大于 100 字节");
            System.out.printf("[VoiceClone] Fallback to default: %d bytes%n", result.length);
        } finally {
            translator.close();
        }
    }
}
