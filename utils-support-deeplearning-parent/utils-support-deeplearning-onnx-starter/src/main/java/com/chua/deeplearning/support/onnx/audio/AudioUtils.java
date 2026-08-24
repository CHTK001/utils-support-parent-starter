package com.chua.deeplearning.support.onnx.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 音频通用工具类。
 *
 * <p>聚合音频域的纯函数工具：采样解码、重采样、向量归一化、
 * 余弦相似度与 WAV 字节封装，供各音频管线复用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class AudioUtils {

    /**
     * 工具类统一目标采样率（16kHz）
     */
    public static final int TARGET_SAMPLE_RATE = 16000;

    private AudioUtils() {
    }

    /**
     * 加载任意 WAV 为 16kHz 单声道 [-1,1] 浮点采样。
     *
     * <p>内部完成声道合并与线性插值重采样。</p>
     *
     * @param path 音频路径
     * @return 16kHz 单声道采样
     * @throws Exception 解码失败
     */
    public static float[] loadMono16k(Path path) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(path.toUri()))) {
            AudioFormat fmt = in.getFormat();
            byte[] bytes = readAll(in);
            int bps = fmt.getSampleSizeInBits() / 8;
            int channels = fmt.getChannels();
            int monoLen = bytes.length / Math.max(1, bps) / channels;
            float[] mono = new float[monoLen];
            for (int i = 0; i < monoLen; i++) {
                float sum = 0F;
                for (int c = 0; c < channels; c++) {
                    int off = (i * channels + c) * bps;
                    short v = (short) (((bytes[off + 1]) << 8) | (bytes[off] & 0xFF));
                    sum += v / 32768.0F;
                }
                mono[i] = sum / channels;
            }
            if (Math.abs(fmt.getSampleRate() - TARGET_SAMPLE_RATE) < 1F) {
                return mono;
            }
            return resample(mono, fmt.getSampleRate(), TARGET_SAMPLE_RATE);
        }
    }

    /**
     * 线性插值重采样。
     *
     * @param samples 原始采样
     * @param sourceRate 源采样率
     * @param targetRate 目标采样率
     * @return 重采样结果
     */
    public static float[] resample(float[] samples, float sourceRate, float targetRate) {
        if (Math.abs(sourceRate - targetRate) < 1F) {
            return samples.clone();
        }
        int newLen = (int) ((long) samples.length * (long) targetRate / (long) sourceRate);
        float[] out = new float[Math.max(1, newLen)];
        for (int i = 0; i < out.length; i++) {
            double pos = (double) i * sourceRate / targetRate;
            int lo = (int) pos;
            int hi = Math.min(lo + 1, samples.length - 1);
            float frac = (float) (pos - lo);
            out[i] = samples[lo] * (1 - frac) + samples[hi] * frac;
        }
        return out;
    }

    /**
     * 余弦相似度。
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 相似度 [-1,1]
     */
    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double den = Math.sqrt(na) * Math.sqrt(nb);
        return den > 1e-12 ? dot / den : 0;
    }

    /**
     * 就地 L2 归一化。
     *
     * @param v 待归一化向量
     */
    public static void l2Normalize(float[] v) {
        double n = norm(v);
        if (n > 1e-12) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= n;
            }
        }
    }

    /**
     * 向量模长。
     *
     * @param v 输入向量
     * @return 模长
     */
    public static double norm(float[] v) {
        double s = 0;
        for (float x : v) {
            s += x * x;
        }
        return Math.sqrt(s);
    }

    /**
     * float 采样封装为 16bit 单声道小端 PCM WAV 字节。
     *
     * @param samples [-1,1] 采样
     * @param sampleRate 采样率
     * @return 完整 WAV 字节（含 RIFF 头）
     * @throws Exception 封装失败
     */
    public static byte[] toWavBytes(float[] samples, int sampleRate) throws Exception {
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(samples.length * 2);
        for (float v : samples) {
            int x = Math.round(Math.max(-1F, Math.min(1F, v)) * 32767F);
            pcmOut.write(x & 0xFF);
            pcmOut.write((x >> 8) & 0xFF);
        }
        byte[] pcm = pcmOut.toByteArray();
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream ais = new AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), fmt, samples.length)) {
            ByteArrayOutputStream wavOut = new ByteArrayOutputStream(pcm.length + 44);
            AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, wavOut);
            return wavOut.toByteArray();
        }
    }

    /**
     * 读取流全部字节。
     *
     * @param in 输入流
     * @return 字节数组
     * @throws Exception 读取失败
     */
    public static byte[] readAll(AudioInputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * 写临时 WAV 文件。
     *
     * @param prefix 文件名前缀
     * @param samples [-1,1] 采样
     * @param sampleRate 采样率
     * @return 已登记删除的临时文件路径
     * @throws Exception 写入失败
     */
    public static Path writeTempWav(String prefix, float[] samples, int sampleRate) throws Exception {
        Path tmp = Files.createTempFile(prefix, ".wav");
        tmp.toFile().deleteOnExit();
        Files.write(tmp, toWavBytes(samples, sampleRate));
        return tmp;
    }
}
