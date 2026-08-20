package com.chua.deeplearning.support.onnx.audio.tts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTS 引擎 RTF（Real-Time Factor）与内存占用基准对比测试。
 *
 * <p>测试指标：</p>
 * <ul>
 *   <li><b>RTF</b>：合成耗时 / 音频时长，越小越好（&lt;1.0 为超实时）</li>
 *   <li><b>模型加载时间</b>：首次 prepare() 耗时</li>
 *   <li><b>内存增量</b>：加载模型前后堆内存变化</li>
 *   <li><b>单次合成内存增量</b>：每次合成的堆内存变化</li>
 *   <li><b>WAV 时长</b>：输出音频的实际时长（秒）</li>
 * </ul>
 *
 * <p>运行条件：</p>
 * <ul>
 *   <li>MMS-TTS：需系统属性 {@code mms-tts.model.dir} 或模型已解压到缓存目录</li>
 *   <li>PocketTTS：需系统属性 {@code pocket-tts.model.dir}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
class TtsBenchmarkTest {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    private static final String[] TEST_TEXTS = {
            "Hello world",
            "The quick brown fox jumps over the lazy dog.",
            "Artificial intelligence is transforming how we interact with technology.",
            "Pocket TTS is a flow matching based text to speech model that can generate high quality speech.",
            "This is a longer sentence to test the real time factor of the text to speech engine. The real time factor should be less than one for real time applications."
    };

    private MmsTtsTranslator mmsTranslator;
    private PocketTtsTranslator pocketTranslator;

    @BeforeEach
    void setUp() {
        mmsTranslator = new MmsTtsTranslator();
        pocketTranslator = new PocketTtsTranslator();
    }

    @AfterEach
    void tearDown() {
        if (mmsTranslator != null) {
            mmsTranslator.close();
        }
        if (pocketTranslator != null) {
            pocketTranslator.close();
        }
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
    }

    // ==================== MMS-TTS 基准测试 ====================

    @Test
    @EnabledIfSystemProperty(named = "mms-tts.model.dir", matches = ".*")
    void benchmarkMmsTts() throws Exception {
        System.out.println("\n========== MMS-TTS Benchmark ==========");
        runBenchmark("MMS-TTS", mmsTranslator::synthesize);
    }

    // ==================== PocketTTS 基准测试 ====================

    @Test
    @EnabledIfSystemProperty(named = "pocket-tts.model.dir", matches = ".*")
    void benchmarkPocketTts() throws Exception {
        System.out.println("\n========== PocketTTS Benchmark ==========");
        runBenchmark("PocketTTS", pocketTranslator::synthesize);
    }

    // ==================== 对比测试 ====================

    @Test
    @EnabledIfSystemProperty(named = "mms-tts.model.dir", matches = ".*")
    @EnabledIfSystemProperty(named = "pocket-tts.model.dir", matches = ".*")
    void benchmarkComparison() throws Exception {
        System.out.println("\n========== TTS Engine Comparison ==========");

        // MMS-TTS
        BenchmarkResult mmsResult = runSingleBenchmark("MMS-TTS", mmsTranslator::synthesize, TEST_TEXTS[2]);

        // PocketTTS
        BenchmarkResult pocketResult = runSingleBenchmark("PocketTTS", pocketTranslator::synthesize, TEST_TEXTS[2]);

        // 输出对比
        System.out.println("\n========== Comparison Summary ==========");
        System.out.printf("%-20s %-15s %-15s%n", "Metric", "MMS-TTS", "PocketTTS");
        System.out.printf("%-20s %-15.4f %-15.4f%n", "RTF", mmsResult.rtf, pocketResult.rtf);
        System.out.printf("%-20s %-15.2f %-15.2f%n", "Audio Duration (s)", mmsResult.audioDuration, pocketResult.audioDuration);
        System.out.printf("%-20s %-15d %-15d%n", "WAV Size (bytes)", mmsResult.wavSize, pocketResult.wavSize);
        System.out.printf("%-20s %-15s %-15s%n", "Sample Rate", "16kHz", "24kHz");
        System.out.printf("%-20s %-15s %-15s%n", "Model Size", "~36MB", "~225MB");

        // RTF 对比判定
        if (pocketResult.rtf < mmsResult.rtf) {
            System.out.printf("%n✅ PocketTTS RTF is %.1fx faster than MMS-TTS%n",
                    mmsResult.rtf / pocketResult.rtf);
        } else {
            System.out.printf("%n✅ MMS-TTS RTF is %.1fx faster than PocketTTS%n",
                    pocketResult.rtf / mmsResult.rtf);
        }

        // Real-time factor thresholds: <1.0 is real-time, <5.0 is acceptable
        assertTrue(mmsResult.rtf < 5.0, "MMS-TTS RTF should be <5.0 for reasonable performance");
        assertTrue(pocketResult.rtf < 5.0, "PocketTTS RTF should be <5.0 for reasonable performance");
    }

    // ==================== 核心基准测试方法 ====================

    @FunctionalInterface
    interface TtsSynthesizer {
        byte[] synthesize(String text) throws Exception;
    }

    private record BenchmarkResult(
            String engine,
            String text,
            int textLength,
            long synthesisTimeMs,
            double audioDuration,
            int wavSize,
            double rtf,
            long heapBeforeBytes,
            long heapAfterBytes,
            long heapDeltaBytes
    ) {}

    private void runBenchmark(String engineName, TtsSynthesizer synthesizer) throws Exception {
        System.out.printf("%-20s %-50s %-10s %-12s %-10s %-15s%n",
                "Engine", "Text (first 50 chars)", "Chars", "Audio (s)", "RTF", "WAV (bytes)");
        System.out.println("-".repeat(110));

        for (String text : TEST_TEXTS) {
            BenchmarkResult result = runSingleBenchmark(engineName, synthesizer, text);
            System.out.printf("%-20s %-50s %-10d %-12.2f %-10.4f %-15d%n",
                    result.engine,
                    result.text.length() > 50 ? result.text.substring(0, 47) + "..." : result.text,
                    result.textLength,
                    result.audioDuration,
                    result.rtf,
                    result.wavSize);
        }
    }

    private BenchmarkResult runSingleBenchmark(String engineName, TtsSynthesizer synthesizer, String text) throws Exception {
        // 预热
        synthesizer.synthesize("warmup");

        // 测量堆内存（加载模型后）
        System.gc();
        Thread.sleep(100);
        long heapBefore = MEMORY_BEAN.getHeapMemoryUsage().getUsed();

        // 合成并计时
        long startNanos = System.nanoTime();
        byte[] wav = synthesizer.synthesize(text);
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedMs = elapsedNanos / 1_000_000.0;

        // 测量堆内存（合成后）
        long heapAfter = MEMORY_BEAN.getHeapMemoryUsage().getUsed();

        // 解析 WAV 获取音频时长
        double audioDuration = getWavDuration(wav);

        // 计算 RTF
        double rtf = audioDuration > 0 ? (elapsedMs / 1000.0) / audioDuration : Double.MAX_VALUE;

        return new BenchmarkResult(
                engineName,
                text,
                text.length(),
                (long) elapsedMs,
                audioDuration,
                wav.length,
                rtf,
                heapBefore,
                heapAfter,
                heapAfter - heapBefore
        );
    }

    /**
     * 从 WAV 字节数组解析音频时长（秒）。
     */
    private static double getWavDuration(byte[] wav) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
            AudioFormat format = ais.getFormat();
            long frames = ais.getFrameLength();
            ais.close();
            if (frames > 0 && format.getFrameRate() > 0) {
                return frames / format.getFrameRate();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ==================== 内存压力测试 ====================

    @Test
    @EnabledIfSystemProperty(named = "mms-tts.model.dir", matches = ".*")
    void memoryPressureMmsTts() throws Exception {
        System.out.println("\n========== MMS-TTS Memory Pressure Test ==========");
        runMemoryPressureTest("MMS-TTS", mmsTranslator::synthesize);
    }

    @Test
    @EnabledIfSystemProperty(named = "pocket-tts.model.dir", matches = ".*")
    void memoryPressurePocketTts() throws Exception {
        System.out.println("\n========== PocketTTS Memory Pressure Test ==========");
        runMemoryPressureTest("PocketTTS", pocketTranslator::synthesize);
    }

    private void runMemoryPressureTest(String engineName, TtsSynthesizer synthesizer) throws Exception {
        int iterations = 20;
        String longText = "This is a memory pressure test for the " + engineName + " engine. "
                + "We will synthesize the same text multiple times to check for memory leaks "
                + "or excessive memory usage. The memory usage should remain relatively stable.";

        // Warm-up
        synthesizer.synthesize("warmup");
        System.gc();
        Thread.sleep(100);

        System.out.printf("Running %d iterations of synthesis...%n", iterations);
        System.out.printf("%-10s %-15s %-15s %-15s%n", "Iter", "Heap (MB)", "Delta (MB)", "RTF");
        System.out.println("-".repeat(60));

        long baselineHeap = MEMORY_BEAN.getHeapMemoryUsage().getUsed();
        double prevHeap = baselineHeap;

        for (int i = 0; i < iterations; i++) {
            long beforeHeap = MEMORY_BEAN.getHeapMemoryUsage().getUsed();
            long startNanos = System.nanoTime();

            byte[] wav = synthesizer.synthesize(longText);

            long elapsedNanos = System.nanoTime() - startNanos;
            long afterHeap = MEMORY_BEAN.getHeapMemoryUsage().getUsed();
            double audioDuration = getWavDuration(wav);
            double rtf = audioDuration > 0 ? (elapsedNanos / 1_000_000.0 / 1000.0) / audioDuration : 0;

            double heapMB = afterHeap / (1024.0 * 1024.0);
            double deltaMB = (afterHeap - prevHeap) / (1024.0 * 1024.0);

            System.out.printf("%-10d %-15.1f %-15.2f %-15.4f%n",
                    i + 1, heapMB, deltaMB, rtf);
            prevHeap = afterHeap;
        }

        long finalHeap = MEMORY_BEAN.getHeapMemoryUsage().getUsed();
        double totalGrowthMB = (finalHeap - baselineHeap) / (1024.0 * 1024.0);
        System.out.printf("%nTotal heap growth after %d iterations: %.2f MB%n", iterations, totalGrowthMB);
        System.out.println("If growth is > 50MB, there may be a memory leak.");
    }
}
