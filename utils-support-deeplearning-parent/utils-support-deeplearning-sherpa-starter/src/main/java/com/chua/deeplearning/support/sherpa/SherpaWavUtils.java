package com.chua.deeplearning.support.sherpa;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * sherpa-onnx 音频编解码工具。
 *
 * <p>sherpa-onnx 原生层只接受 {@code float[]} 采样（范围 [-1, 1]）与采样率，
 * 因此本模块需要自行完成 WAV 解码、声道合并、重采样与结果封装。
 * 这里只使用 JDK 自带的 {@code javax.sound.sampled}，不引入额外音频依赖，
 * 以保持本模块「仅依赖 sherpa-onnx 官方 JVM 产物」的定位。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SherpaWavUtils {

    /**
     * sherpa-onnx 全部内置模型的训练采样率。
     */
    public static final int DEFAULT_SAMPLE_RATE = 16000;

    /**
     * WAV 头长度（PCM，无附加块）。
     */
    private static final int WAV_HEADER_SIZE = 44;

    /**
     * 工具类禁止实例化。
     */
    private SherpaWavUtils() {
    }

    /**
     * 读取 WAV 文件并重采样为指定采样率的单声道浮点采样。
     *
     * @param path       音频文件路径
     * @param sampleRate 目标采样率（Hz）
     * @return [-1, 1] 浮点采样
     * @throws SherpaException 解码失败
     */
    public static float[] readMono(Path path, int sampleRate) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new SherpaException("音频文件不存在: " + path);
        }
        try (InputStream raw = Files.newInputStream(path)) {
            return readMono(raw, sampleRate);
        } catch (IOException e) {
            throw new SherpaException("读取音频失败: " + path, e);
        }
    }

    /**
     * 读取音频字节并重采样为指定采样率的单声道浮点采样。
     *
     * @param data       音频字节（WAV / 其它 JDK 可解码格式）
     * @param sampleRate 目标采样率（Hz）
     * @return [-1, 1] 浮点采样
     * @throws SherpaException 解码失败
     */
    public static float[] readMono(byte[] data, int sampleRate) {
        if (data == null || data.length == 0) {
            throw new SherpaException("音频字节为空");
        }
        return readMono(new ByteArrayInputStream(data), sampleRate);
    }

    /**
     * 从输入流解码为指定采样率的单声道浮点采样。
     *
     * @param input      音频输入流（方法内不关闭）
     * @param sampleRate 目标采样率（Hz）
     * @return [-1, 1] 浮点采样
     */
    public static float[] readMono(InputStream input, int sampleRate) {
        if (input == null) {
            throw new SherpaException("音频输入流为空");
        }
        // AudioSystem.getAudioInputStream(InputStream) 要求流支持 mark/reset，
        // 文件流与管道流均不满足，统一包一层 BufferedInputStream。
        InputStream buffered = input.markSupported() ? input : new BufferedInputStream(input);
        try (AudioInputStream in = AudioSystem.getAudioInputStream(buffered)) {
            AudioFormat format = in.getFormat();
            byte[] bytes = readAll(in);
            int bytesPerSample = Math.max(1, format.getSampleSizeInBits() / 8);
            int channels = Math.max(1, format.getChannels());
            boolean bigEndian = format.isBigEndian();
            boolean signed = format.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED;
            int frameCount = bytes.length / (bytesPerSample * channels);
            float[] mono = new float[frameCount];
            for (int i = 0; i < frameCount; i++) {
                float sum = 0F;
                for (int c = 0; c < channels; c++) {
                    int offset = (i * channels + c) * bytesPerSample;
                    sum += decodeSample(bytes, offset, bytesPerSample, bigEndian, signed);
                }
                mono[i] = sum / channels;
            }
            float sourceRate = format.getSampleRate();
            if (Math.abs(sourceRate - sampleRate) < 1F) {
                return mono;
            }
            return resample(mono, sourceRate, sampleRate);
        } catch (Exception e) {
            throw new SherpaException("解码音频失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按位深解码单个采样并归一化到 [-1, 1]。
     *
     * @param bytes         原始字节
     * @param offset        采样起始偏移
     * @param bytesPerSample 每采样字节数（1 / 2 / 3 / 4）
     * @param bigEndian     是否大端
     * @param signed        是否有符号
     * @return 归一化采样
     */
    private static float decodeSample(byte[] bytes, int offset, int bytesPerSample, boolean bigEndian, boolean signed) {
        return switch (bytesPerSample) {
            case 1 -> {
                int v = bytes[offset] & 0xFF;
                yield signed ? (v >= 128 ? (v - 256) / 128.0F : v / 128.0F) : (v - 128) / 128.0F;
            }
            case 2 -> {
                int lo = bigEndian ? bytes[offset + 1] & 0xFF : bytes[offset] & 0xFF;
                int hi = bigEndian ? bytes[offset] & 0xFF : bytes[offset + 1] & 0xFF;
                short v = (short) ((hi << 8) | lo);
                yield v / 32768.0F;
            }
            case 3 -> {
                int b0 = bytes[offset] & 0xFF;
                int b1 = bytes[offset + 1] & 0xFF;
                int b2 = bytes[offset + 2] & 0xFF;
                int v = bigEndian ? ((b0 << 16) | (b1 << 8) | b2) : ((b2 << 16) | (b1 << 8) | b0);
                if ((v & 0x800000) != 0) {
                    v |= 0xFF000000;
                }
                yield v / 8388608.0F;
            }
            default -> {
                int b0 = bytes[offset] & 0xFF;
                int b1 = bytes[offset + 1] & 0xFF;
                int b2 = bytes[offset + 2] & 0xFF;
                int b3 = bytes[offset + 3] & 0xFF;
                int v = bigEndian
                        ? ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3)
                        : ((b3 << 24) | (b2 << 16) | (b1 << 8) | b0);
                yield (float) (v / 2147483648.0);
            }
        };
    }

    /**
     * 线性插值重采样。
     *
     * @param samples    原始采样
     * @param sourceRate 源采样率
     * @param targetRate 目标采样率
     * @return 重采样结果
     */
    public static float[] resample(float[] samples, float sourceRate, float targetRate) {
        if (samples == null || samples.length == 0) {
            return new float[0];
        }
        if (Math.abs(sourceRate - targetRate) < 1F) {
            return samples.clone();
        }
        int newLength = (int) ((long) samples.length * (long) targetRate / (long) sourceRate);
        if (newLength <= 0) {
            return new float[0];
        }
        float[] out = new float[newLength];
        for (int i = 0; i < newLength; i++) {
            double position = (double) i * sourceRate / targetRate;
            int low = (int) position;
            int high = Math.min(low + 1, samples.length - 1);
            float fraction = (float) (position - low);
            out[i] = samples[low] * (1 - fraction) + samples[high] * fraction;
        }
        return out;
    }

    /**
     * 将浮点采样封装为 16 位单声道小端 PCM WAV 字节。
     *
     * @param samples    [-1, 1] 浮点采样
     * @param sampleRate 采样率（Hz）
     * @return 完整 WAV 字节（含 RIFF 头）
     * @throws SherpaException 封装失败
     */
    public static byte[] toWav(float[] samples, int sampleRate) {
        if (samples == null) {
            samples = new float[0];
        }
        int dataLength = samples.length * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(WAV_HEADER_SIZE + dataLength);
        writeAscii(out, "RIFF");
        writeIntLe(out, WAV_HEADER_SIZE - 8 + dataLength);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLe(out, 16);
        writeShortLe(out, 1);
        writeShortLe(out, 1);
        writeIntLe(out, sampleRate);
        writeIntLe(out, sampleRate * 2);
        writeShortLe(out, 2);
        writeShortLe(out, 16);
        writeAscii(out, "data");
        writeIntLe(out, dataLength);
        for (float sample : samples) {
            int value = Math.round(Math.max(-1F, Math.min(1F, sample)) * 32767F);
            writeShortLe(out, value);
        }
        return out.toByteArray();
    }

    /**
     * 将浮点采样写为临时 WAV 文件（JVM 退出时删除）。
     *
     * @param prefix     文件名前缀
     * @param samples    [-1, 1] 浮点采样
     * @param sampleRate 采样率（Hz）
     * @return 临时文件路径
     */
    public static Path writeTempWav(String prefix, float[] samples, int sampleRate) {
        try {
            Path tmp = Files.createTempFile(prefix, ".wav");
            tmp.toFile().deleteOnExit();
            Files.write(tmp, toWav(samples, sampleRate));
            return tmp;
        } catch (IOException e) {
            throw new SherpaException("写入临时 WAV 失败", e);
        }
    }

    /**
     * 拼接多段采样（各段之间插入等长静音）。
     *
     * @param segments   采样分段
     * @param gapSamples 段间静音采样数，可为 0
     * @return 拼接后的采样
     */
    public static float[] concat(List<float[]> segments, int gapSamples) {
        if (segments == null || segments.isEmpty()) {
            return new float[0];
        }
        int total = 0;
        for (float[] segment : segments) {
            total += segment == null ? 0 : segment.length;
        }
        total += Math.max(0, gapSamples) * (segments.size() - 1);
        float[] out = new float[total];
        int cursor = 0;
        for (int i = 0; i < segments.size(); i++) {
            float[] segment = segments.get(i);
            if (segment != null && segment.length > 0) {
                System.arraycopy(segment, 0, out, cursor, segment.length);
                cursor += segment.length;
            }
            if (i < segments.size() - 1) {
                cursor += Math.max(0, gapSamples);
            }
        }
        return out;
    }

    /**
     * 将长音频切分为定长片段（用于 VAD 缺失时的分段识别）。
     *
     * @param samples    原始采样
     * @param chunkSize  每段采样数（需大于 0）
     * @return 片段列表
     */
    public static List<float[]> split(float[] samples, int chunkSize) {
        List<float[]> result = new ArrayList<>();
        if (samples == null || samples.length == 0 || chunkSize <= 0) {
            return result;
        }
        for (int offset = 0; offset < samples.length; offset += chunkSize) {
            int length = Math.min(chunkSize, samples.length - offset);
            float[] chunk = new float[length];
            System.arraycopy(samples, offset, chunk, 0, length);
            result.add(chunk);
        }
        return result;
    }

    /**
     * 读取流全部字节。
     *
     * @param in 输入流
     * @return 字节数组
     * @throws IOException 读取失败
     */
    private static byte[] readAll(AudioInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * 写出 ASCII 字面量。
     *
     * @param out  输出流
     * @param text 文本
     */
    private static void writeAscii(ByteArrayOutputStream out, String text) {
        for (int i = 0; i < text.length(); i++) {
            out.write(text.charAt(i) & 0xFF);
        }
    }

    /**
     * 小端写出 16 位整数。
     *
     * @param out   输出流
     * @param value 数值
     */
    private static void writeShortLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /**
     * 小端写出 32 位整数。
     *
     * @param out   输出流
     * @param value 数值
     */
    private static void writeIntLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    /**
     * 计算给定采样数在指定采样率下的时长（秒）。
     *
     * @param sampleCount 采样数
     * @param sampleRate  采样率
     * @return 时长（秒）
     */
    public static double durationSeconds(int sampleCount, int sampleRate) {
        if (sampleRate <= 0) {
            return 0D;
        }
        return (double) sampleCount / sampleRate;
    }

    /**
     * 判断给定路径是否可能为音频文件（依据扩展名）。
     *
     * @param path 路径
     * @return true 表示扩展名属于常见音频容器
     */
    public static boolean isAudioFile(Path path) {
        if (path == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".wav") || name.endsWith(".flac") || name.endsWith(".mp3")
                || name.endsWith(".ogg") || name.endsWith(".m4a") || name.endsWith(".aac");
    }
}
