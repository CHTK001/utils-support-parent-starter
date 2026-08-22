package com.chua.deeplearning.support.onnx.audio.tts;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pocket-TTS 声音克隆测试。
 *
 * <p>测试覆盖：mimi_encoder 就绪检查、默认音色合成、声音克隆、空参考回退。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
class PocketTtsVoiceCloneTest {

    private static final String TEST_TEXT = "This is a voice cloning test.";
    private static final String CLONE_TEXT = "Hello, can you hear me?";
    private static final long MIN_HEAP_FREE_BYTES = 512L * 1024 * 1024;
    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    private boolean hasEnoughMemory() {
        long committed = MEMORY_BEAN.getHeapMemoryUsage().getCommitted();
        long used = MEMORY_BEAN.getHeapMemoryUsage().getUsed();
        return (committed - used) >= MIN_HEAP_FREE_BYTES;
    }

    /** 测试 1：验证 mimi_encoder 已嵌入，克隆能力可用 */
    @Test
    void testSupportsVoiceClone() throws Exception {
        PocketTtsTranslator t = new PocketTtsTranslator();
        try {
            t.synthesize("Hi");
            assertTrue(t.supportsVoiceClone(), "mimi_encoder.onnx 未嵌入 JAR");
            log.info("[VoiceClone] supportsVoiceClone()=true");
        } finally { t.close(); }
    }

    /** 测试 2：默认音色合成 */
    @Test
    void testDefaultVoiceSynthesis() throws Exception {
        if (!hasEnoughMemory()) {
            log.info("[VoiceClone] 内存不足，跳过");
            return;
        }
        PocketTtsTranslator t = new PocketTtsTranslator();
        try {
            byte[] wav = t.synthesize(TEST_TEXT);
            assertNotNull(wav);
            assertTrue(wav.length > 100);
            assertEquals('R', (char) wav[0]);
            log.info("[VoiceClone] Default: {} bytes", wav.length);
            Files.write(Path.of(System.getProperty("java.io.tmpdir"), "pocket-tts-default.wav"), wav);
        } finally { t.close(); }
    }

    /** 测试 3：完整声音克隆（参考音频 → 克隆） */
    @Test
    void testVoiceClone() throws Exception {
        if (!hasEnoughMemory()) {
            log.info("[VoiceClone] 内存不足，跳过");
            return;
        }
        PocketTtsTranslator t = new PocketTtsTranslator();
        try {
            byte[] refWav = t.synthesize(TEST_TEXT);
            assertNotNull(refWav);
            byte[] clonedWav = t.voice(CLONE_TEXT, refWav);
            assertNotNull(clonedWav);
            assertTrue(clonedWav.length > 100);
            assertEquals('R', (char) clonedWav[0]);
            log.info("[VoiceClone] Cloned: {} bytes", clonedWav.length);
            Files.write(Path.of(System.getProperty("java.io.tmpdir"), "pocket-tts-cloned.wav"), clonedWav);
        } finally { t.close(); }
    }

    /** 测试 4：空参考音频回退默认音色 */
    @Test
    void testFallbackToDefaultVoice() throws Exception {
        PocketTtsTranslator t = new PocketTtsTranslator();
        try {
            byte[] result = t.voice(TEST_TEXT, null);
            assertNotNull(result);
            assertTrue(result.length > 100);
            log.info("[VoiceClone] Fallback: {} bytes", result.length);
        } finally { t.close(); }
    }
}
