package com.chua.deeplearning.support.onnx.audio.denoise;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DFSMN 语音降噪 pipeline 验证：与 ModelScope 参考实现各阶段输出对比。
 * <p>
 * 参考数据由 {@code Z:\temp\opencode\gen_dfsmn_reference.py} 生成（torch + librosa），
 * 本测试验证 Java 侧 fbank / STFT / 完整降噪 PCM 与其一致性。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
class DfsmnAnsPipelineTest {

    private static final Path REF_DIR = Paths.get("Z:\\temp\\opencode");

    @Test
    void testFbankMatchesTorchReference() throws Exception {
        Path samplesPath = REF_DIR.resolve("ref_samples.f32");
        Path fbankPath = REF_DIR.resolve("ref_fbank_dither0.f32");
        if (!Files.exists(samplesPath) || !Files.exists(fbankPath)) {
            log.warn("参考数据缺失，跳过 fbank 对比测试");
            return;
        }
        float[] samples = readF32(samplesPath);
        float[] expected = readF32(fbankPath);
        DfsmnKaldiFbank fbank = new DfsmnKaldiFbank(0f);
        float[] actual = fbank.extract(samples);
        assertEquals(expected.length, actual.length, "fbank 输出长度不一致");
        float maxDiff = 0f;
        double sumDiff = 0;
        int overCount = 0;
        for (int i = 0; i < actual.length; i++) {
            float d = Math.abs(actual[i] - expected[i]);
            maxDiff = Math.max(maxDiff, d);
            sumDiff += d;
            if (d > 0.01f) {
                overCount++;
            }
        }
        double meanDiff = sumDiff / actual.length;
        log.info("fbank(dither=0) vs torch: maxDiff={}, meanDiff={}, over0.01={}/{}",
                maxDiff, String.format("%.4f", meanDiff), overCount, actual.length);
        assertTrue(maxDiff < 0.05f, "fbank 最大误差过大: " + maxDiff);
        assertTrue(overCount < actual.length * 0.01, "fbank 误差超限样本过多");
    }

    @Test
    void testStftMatchesTorchReference() throws Exception {
        Path samplesPath = REF_DIR.resolve("ref_samples.f32");
        Path stftRealPath = REF_DIR.resolve("ref_stft_real.f32");
        Path stftImagPath = REF_DIR.resolve("ref_stft_imag.f32");
        if (!Files.exists(samplesPath) || !Files.exists(stftRealPath)) {
            log.warn("参考数据缺失，跳过 STFT 对比测试");
            return;
        }
        float[] samples = readF32(samplesPath);
        float[] expectedReal = readF32(stftRealPath);
        float[] expectedImag = readF32(stftImagPath);
        DfsmnStftIStft stft = new DfsmnStftIStft();
        float[][][] spectrum = stft.stft(samples);
        int frames = spectrum.length;
        int freqs = spectrum[0].length;
        float maxDiff = 0f;
        for (int f = 0; f < frames; f++) {
            for (int k = 0; k < freqs; k++) {
                // 参考数据为 frequency-major：ref[freq*frames + frame]
                int idx = k * frames + f;
                maxDiff = Math.max(maxDiff, Math.abs(spectrum[f][k][0] - expectedReal[idx]));
                maxDiff = Math.max(maxDiff, Math.abs(spectrum[f][k][1] - expectedImag[idx]));
            }
        }
        log.info("STFT vs torch: maxDiff={}, bins={}x{}", maxDiff, frames, freqs);
        assertTrue(maxDiff < 1.0f, "STFT 最大误差过大: " + maxDiff);
    }

    @Test
    void testEndToEndEnhanceMatchesPython() throws Exception {
        Path wavPath = Paths.get("G:\\work\\utils-support-parent-starter\\utils-support-extra-parent\\utils-support-example-starter\\src\\main\\resources\\audio\\denoise\\speech_with_noise_48k.wav");
        // dither=0 参考（严格对比，无随机噪声扰动）
        Path refPcmPath = REF_DIR.resolve("ref_pcm_dither0.int16");
        if (!Files.exists(wavPath) || !Files.exists(refPcmPath)) {
            log.warn("参考数据缺失，跳过端到端对比测试");
            return;
        }
        byte[] wav = Files.readAllBytes(wavPath);
        DfsmnAnsTranslator translator = new DfsmnAnsTranslator(0f);
        byte[] outWav = translator.translate(wav);
        assertTrue(DfsmnAnsTranslator.isWav(outWav), "输出应为 WAV 格式");
        log.info("输出 WAV 总长度: {}", outWav.length);

        // 解析输出 WAV data
        short[] pcm = wavToPcm(outWav);
        log.info("输出 PCM 样本数: {}", pcm.length);
        assertTrue(pcm.length > 0, "降噪输出为空");

        byte[] refBytes = Files.readAllBytes(refPcmPath);
        short[] expected = new short[refBytes.length / 2];
        ByteBuffer bb = ByteBuffer.wrap(refBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expected.length; i++) {
            expected[i] = bb.getShort();
        }
        int compareLen = Math.min(pcm.length, expected.length);
        long sumAbs = 0;
        int maxAbs = 0;
        for (int i = 0; i < compareLen; i++) {
            int d = Math.abs(pcm[i] - expected[i]);
            sumAbs += d;
            maxAbs = Math.max(maxAbs, d);
        }
        double meanAbs = (double) sumAbs / compareLen;
        log.info("降噪 PCM vs Python(dither=0): len={}, meanAbs={}, maxAbs={}", compareLen, meanAbs, maxAbs);
        assertTrue(meanAbs < 50, "降噪 PCM 平均绝对误差过大: " + meanAbs);
        assertTrue(maxAbs < 500, "降噪 PCM 最大绝对误差过大: " + maxAbs);

        // 保存降噪后音频 + 能量对比，供人工试听/验证输出正常
        Files.write(Paths.get("Z:\\temp\\opencode\\clean_java.wav"), outWav);
        byte[] refBytes1 = Files.readAllBytes(wavPath);
        short[] noisyPcm = wavToPcm(refBytes1);
        double noisyEnergy = rms(noisyPcm);
        double cleanEnergy = rms(pcm);
        // SpeechBrain 降噪后能量应显著下降（噪声被抑制）
        log.info("能量对比: 输入 RMS={}, 降噪后 RMS={}, 抑制比={}dB",
                noisyEnergy, cleanEnergy, 20 * Math.log10(noisyEnergy / Math.max(cleanEnergy, 1)));
    }

    private static double rms(short[] pcm) {
        double sum = 0;
        for (short s : pcm) {
            sum += (double) s * s;
        }
        return Math.sqrt(sum / pcm.length);
    }

    @Test
    void testStftIStftRoundTrip() throws Exception {
        // round-trip：stft → istft(全 1 mask) 应近似重建原信号
        int rate = 48000;
        short[] orig = new short[rate];
        for (int i = 0; i < orig.length; i++) {
            orig[i] = (short) Math.round(30000 * Math.sin(2 * Math.PI * 440 * i / rate));
        }
        float[] signal = new float[orig.length];
        for (int i = 0; i < orig.length; i++) {
            signal[i] = orig[i];
        }
        DfsmnStftIStft stft = new DfsmnStftIStft();
        float[][][] spec = stft.stft(signal);
        float[][] ones = new float[spec.length][961];
        for (int f = 0; f < spec.length; f++) {
            for (int k = 0; k < 961; k++) {
                ones[f][k] = 1f;
            }
        }
        float[] rec = stft.istft(spec, ones, signal.length);
        float maxDiff = 0f;
        double sum = 0;
        for (int i = 0; i < signal.length; i++) {
            float d = Math.abs(rec[i] - signal[i]);
            maxDiff = Math.max(maxDiff, d);
            sum += d;
        }
        double meanDiff = sum / signal.length;
        log.info("STFT/ISTFT round-trip: maxDiff={}, meanDiff={}", maxDiff, meanDiff);
        assertTrue(maxDiff < 500f, "round-trip 重建误差过大: maxDiff=" + maxDiff);
        assertTrue(meanDiff < 10, "round-trip 重建均值误差过大: " + meanDiff);
    }

    @Test
    void testComplexFftRoundTrip() throws Exception {
        for (int n : new int[]{2048, 1920, 961, 7}) {
            float[] sig = new float[n];
            for (int i = 0; i < n; i++) {
                sig[i] = (float) Math.sin(i * 0.3) * 1000f + (float) Math.cos(i * 1.7) * 500f;
            }
            float[][] spec = ComplexFft.rfft(sig);
            float[] rec = ComplexFft.irfft(spec, n);
            float maxDiff = 0f;
            for (int i = 0; i < n; i++) {
                maxDiff = Math.max(maxDiff, Math.abs(rec[i] - sig[i]));
            }
            log.info("ComplexFft round-trip n={}: maxDiff={}", n, maxDiff);
            assertTrue(maxDiff < 0.01f * (1 + maxAbsF(sig)), "FFT round-trip 误差过大 n=" + n + " diff=" + maxDiff);
        }
    }

    private static float maxAbsF(float[] a) {
        float m = 0;
        for (float v : a) {
            m = Math.max(m, Math.abs(v));
        }
        return m;
    }

    private static float[] readF32(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        FloatBufferUtil fb = new FloatBufferUtil(bytes);
        return fb.floatArray();
    }

    private static short[] wavToPcm(byte[] wav) {
        ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        int dataOffset = -1;
        while (pos + 8 <= wav.length) {
            int chunkId = bb.getInt(pos);
            int size = bb.getInt(pos + 4);
            String id = new String(new byte[]{
                    (byte) (chunkId & 0xff), (byte) ((chunkId >> 8) & 0xff),
                    (byte) ((chunkId >> 16) & 0xff), (byte) ((chunkId >> 24) & 0xff)});
            if ("data".equals(id)) {
                dataOffset = pos + 8;
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (dataOffset < 0) {
            return new short[0];
        }
        int n = (wav.length - dataOffset) / 2;
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            pcm[i] = bb.getShort(dataOffset + i * 2);
        }
        return pcm;
    }

    /** FloatBuffer 便捷工具，避免测试引入额外库。 */
    private static final class FloatBufferUtil {
        private final ByteBuffer bb;

        FloatBufferUtil(byte[] bytes) {
            bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        float[] floatArray() {
            float[] out = new float[bb.remaining() / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = bb.getFloat();
            }
            return out;
        }
    }
}