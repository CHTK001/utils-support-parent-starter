package com.chua.deeplearning.support.onnx.audio.denoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
* WAV / PCM 音频解码与封装工具。
* <p>解析 RIFF WAV（支持 PCM 8/16/32 位、IEEE float 32），输出 float 样本；纯 PCM 按
* 48khz 单声道 int16 处理。支持线性插值重采样到目标采样率。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class WavDecoder {

    /**
    * 默认采样率（纯 PCM 假定值）。
     */
    private static final int DEFAULT_SAMPLE_RATE = 48000;

    /**
    * 解码音频为 float 样本（幅值约 ±32768），并重采样到目标采样率。
    *
    * @param data            输入字节（wav 或纯 pcm）
    * @param targetSampleRate 目标采样率
    * @return float 单声道样本
     */
    public static float[] decodeToFloat(byte[] data, int targetSampleRate) {
        if (DfsmnAnsTranslator.isWav(data)) {
            return decodeWav(data, targetSampleRate);
        }
        // 纯 PCM：假定 48k 单声道 int16
        short[] pcm = new short[data.length / 2];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = bb.getShort();
        }
        float[] samples = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            samples[i] = pcm[i];
        }
        return resample(samples, DEFAULT_SAMPLE_RATE, targetSampleRate);
    }

    /**
    * 解析 WAV 为 float 样本并重采样。
    * @param data 数据
    * @param targetSampleRate Target样本rate
    * @return decodeWav的结果
     */
    private static float[] decodeWav(byte[] data, int targetSampleRate) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // 定位 fmt 子块
        int format = 1;      // 1=PCM, 3=IEEE float
        int channels = 1;
        int sampleRate = DEFAULT_SAMPLE_RATE;
        int bitsPerSample = 16;
        int dataOffset = -1;
        int dataLength = 0;

        int pos = 12;
        while (pos + 8 <= data.length) {
            int chunkId = bb.getInt(pos);
            int size = bb.getInt(pos + 4);
            String id = new String(new byte[]{
                    (byte) (chunkId & 0xff), (byte) ((chunkId >> 8) & 0xff),
                    (byte) ((chunkId >> 16) & 0xff), (byte) ((chunkId >> 24) & 0xff)});
            if ("fmt ".equals(id) && pos + 8 + size <= data.length) {
                format = bb.getShort(pos + 8);
                channels = bb.getShort(pos + 8 + 2);
                sampleRate = bb.getInt(pos + 8 + 4);
                bitsPerSample = bb.getShort(pos + 8 + 14);
            } else if ("data".equals(id)) {
                dataOffset = pos + 8;
                dataLength = size;
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (dataOffset < 0) {
            throw new IllegalArgumentException("未找到 WAV data 子块");
        }
        int bytesPerSample = bitsPerSample / 8;
        int samplesPerChannel = dataLength / bytesPerSample / channels;
        float[] out = new float[samplesPerChannel];
        for (int i = 0; i < samplesPerChannel; i++) {
            int sampleIdx = dataOffset + i * channels * bytesPerSample;
            float v = 0f;
            if (format == 1) {
                if (bytesPerSample == 1) {
                    v = ((data[sampleIdx] & 0xff) - 128) * 256f;
                } else if (bytesPerSample == 2) {
                    short s = bb.getShort(sampleIdx);
                    v = s;
                } else if (bytesPerSample == 4) {
                    v = bb.getInt(sampleIdx);
                }
            } else if (format == 3 && bytesPerSample == 4) {
                v = bb.getFloat(sampleIdx) * 32768f;
            } else {
                throw new IllegalArgumentException("不支持的 WAV 格式: audioFormat=" + format + ", bits=" + bitsPerSample);
            }
            out[i] = v;
        }
        return resample(out, sampleRate, targetSampleRate);
    }

    /**
    * 线性插值重采样。
    *
    * @param samples 源样本
    * @param srcRate 源采样率
    * @param dstRate 目标采样率
    * @return 重采样后样本
     */
    private static float[] resample(float[] samples, int srcRate, int dstRate) {
        if (srcRate == dstRate || samples.length == 0) {
            return samples;
        }
        int outLen = (int) Math.round(samples.length * (double) dstRate / srcRate);
        float[] out = new float[outLen];
        double ratio = (double) srcRate / dstRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * ratio;
            int lower = (int) pos;
            if (lower >= samples.length - 1) {
                out[i] = samples[samples.length - 1];
                continue;
            }
            float frac = (float) (pos - lower);
            out[i] = samples[lower] * (1f - frac) + samples[lower + 1] * frac;
        }
        return out;
    }

    /**
    * 构造 WAV 头。
    *
    * @param dataSize      数据 子块大小
    * @param sampleRate    采样率
    * @param channels      声道数
    * @param bitsPerSample 位深
    * @return 44 字节 WAV 头
     */
    public static byte[] buildWavHeader(int dataSize, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(0, "RIFF".getBytes());
        bb.putInt(4, 36 + dataSize);
        bb.put(8, "WAVE".getBytes());
        bb.put(12, "fmt ".getBytes());
        bb.putInt(16, 16);
        bb.putShort(20, (short) 1);
        bb.putShort(22, (short) channels);
        bb.putInt(24, sampleRate);
        bb.putInt(28, byteRate);
        bb.putShort(32, (short) blockAlign);
        bb.putShort(34, (short) bitsPerSample);
        bb.put(36, "data".getBytes());
        bb.putInt(40, dataSize);
        return bb.array();
    }
}