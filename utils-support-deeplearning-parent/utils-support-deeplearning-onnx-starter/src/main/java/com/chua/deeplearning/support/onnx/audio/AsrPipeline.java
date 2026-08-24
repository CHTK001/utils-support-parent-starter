package com.chua.deeplearning.support.onnx.audio;

import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地离线语音识别管线。
 *
 * <p><b>阶段构成</b>：</p>
 * <ul>
 *   <li>必选-1 音频解码：任意 WAV/PCM → 16kHz 单声道 float[]</li>
 *   <li>必选-2 引擎推理：moonshine / whisper / paraformer（AudioClient SPI）逐段转写</li>
 *   <li>必选-3 结果拼接：多段文本按时间序合并</li>
 *   <li>可选-A 能量 VAD 切分：静音检测切段，长音频必备，短音频可关</li>
     *   <li>可选-B 降噪预处理：DFSMN 单麦近场降噪（48k 模型，内部自动重采样），嘈杂场景建议开启</li>
 *   <li>可选-C 后处理：去多余空白、首字母大写（英文）</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   String text = AsrPipeline.builder()
 *           .engine("moonshine")      // 必选：引擎 id
 *           .vad(true)                // 可选：默认 false
 *           .postProcess(true)        // 可选：默认 true
 *           .build()
 *           .transcribe(Path.of("a.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class AsrPipeline {

    /**
     * 目标采样率
     */
    private static final int TARGET_SR = 16000;

    /**
     * 默认静音阈值（RMS）
     */
    private static final float DEFAULT_SILENCE_RMS = 0.01F;

    /**
     * 默认最短语音段（秒）
     */
    private static final float DEFAULT_MIN_SEG = 0.4F;

    /**
     * 默认最大段长（秒），超长强制切分
     */
    private static final float DEFAULT_MAX_SEG = 28F;

    private final String engineId;
    private final String language;
    private final boolean vad;
    private final boolean denoise;
    private final boolean postProcess;
    private final float silenceRms;
    private final float minSegSec;
    private final float maxSegSec;

    private AsrPipeline(Builder b) {
        this.engineId = b.engineId;
        this.language = b.language;
        this.vad = b.vad;
        this.denoise = b.denoise;
        this.postProcess = b.postProcess;
        this.silenceRms = b.silenceRms;
        this.minSegSec = b.minSegSec;
        this.maxSegSec = b.maxSec;
    }

    /**
     * 创建构建器。
     *
     * @param engineId 必选：ASR 引擎 id（moonshine / whisper-tiny / paraformer-zh-small 等）
     * @return 构建器
     */
    public static Builder builder(String engineId) {
        return new Builder(engineId);
    }

    /**
     * 执行管线：解码 → (降噪) → (VAD 切分) → 引擎转写 → 拼接 → (后处理)。
     *
     * @param wavPath 输入音频
     * @return 全文转写结果；无语音时返回空串
     * @throws Exception 管线失败
     */
    public String transcribe(Path wavPath) throws Exception {
        long t0 = System.currentTimeMillis();
        // 必选-1：解码重采样
        float[] samples = loadMono16k(wavPath);
        log.info("[AsrPipeline] 解码完成: {} 采样 ({}, {}s)", samples.length, wavPath.getFileName(),
                String.format("%.1f", samples.length / (float) TARGET_SR));

        // 可选-B：降噪占位
        if (denoise) {
            samples = denoise(samples);
        }

        // 可选-A：VAD 切分；关闭则整段单块（内部仍按 maxSeg 强制切块）
        List<float[]> segments;
        if (vad) {
            segments = splitByEnergy(samples);
        } else {
            segments = new ArrayList<>(forceSplit(samples));
        }

        // 必选-2：逐段引擎推理
        List<String> parts = new ArrayList<>(segments.size());
        try (com.chua.common.support.ai.audio.AudioClient client =
                     com.chua.common.support.ai.audio.AudioClient.create(engineId, "")) {
            if (language != null && !language.isBlank()) {
                client.language(language);
            }
            for (int i = 0; i < segments.size(); i++) {
                Path tmp = toTempWav(segments.get(i));
                try {
                    String part = client.transcribe(tmp);
                    if (part != null && !part.isBlank()) {
                        parts.add(part.trim());
                        log.info("[AsrPipeline] 段 {}/{} → {}", i + 1, segments.size(), part);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        }

        // 必选-3 + 可选-C：拼接与后处理
        String text = String.join(" ", parts);
        if (postProcess) {
            text = postProcess(text);
        }
        log.info("[AsrPipeline] 完成: {} 字符, {}ms", text.length(), System.currentTimeMillis() - t0);
        return text;
    }

    /** 可选-A：能量 VAD 切分（RMS 门限 + 迟滞合并） */
    List<float[]> splitByEnergy(float[] s) {
        int frame = (int) (0.03F * TARGET_SR);
        int minSeg = (int) (minSegSec * TARGET_SR);
        int maxSeg = (int) (maxSegSec * TARGET_SR);
        List<int[]> voiced = new ArrayList<>();
        int start = -1;
        for (int i = 0; i + frame <= s.length; i += frame) {
            boolean loud = rms(s, i, frame) >= silenceRms;
            if (loud && start < 0) {
                start = i;
            } else if (!loud && start >= 0 && i - start >= minSeg) {
                voiced.add(new int[]{start, i});
                start = -1;
            } else if (!loud) {
                start = -1;
            }
        }
        if (start >= 0) {
            voiced.add(new int[]{start, Math.min(s.length, start + maxSeg)});
        }
        if (start >= 0) {
            voiced.add(new int[]{start, Math.min(s.length, start + maxSeg)});
        }
        // 合并短停顿（<0.6s）的相邻段，保持完整语句上下文（对 moonshine 类模型尤为关键）
        List<int[]> merged = new ArrayList<>(voiced.size());
        for (int[] r : voiced) {
            int gap = (int) (0.6F * TARGET_SR);
            if (!merged.isEmpty() && r[0] - merged.get(merged.size() - 1)[1] < gap) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = r[1];
            } else {
                merged.add(new int[]{r[0], r[1]});
            }
        }
        if (merged.isEmpty()) {
            return List.of(s);
        }
        List<float[]> out = new ArrayList<>(merged.size());
        for (int[] r : merged) {
            for (int p = r[0]; p < r[1]; p += maxSeg) {
                int end = Math.min(r[1], p + maxSeg);
                float[] seg = new float[end - p];
                System.arraycopy(s, p, seg, 0, seg.length);
                out.add(seg);
            }
        }
        return out;
    }

    /** 关闭 VAD 时按最大段长强制切块 */
    private List<float[]> forceSplit(float[] s) {
        int maxSeg = (int) (maxSegSec * TARGET_SR);
        if (s.length <= maxSeg) {
            return List.of(s);
        }
        List<float[]> out = new ArrayList<>((s.length + maxSeg - 1) / maxSeg);
        for (int p = 0; p < s.length; p += maxSeg) {
            int end = Math.min(s.length, p + maxSeg);
            float[] seg = new float[end - p];
            System.arraycopy(s, p, seg, 0, seg.length);
            out.add(seg);
        }
        return out;
    }

    /** 计算帧 RMS */
    private static float rms(float[] s, int off, int len) {
        double sum = 0;
        for (int i = off; i < off + len; i++) {
            sum += s[i] * s[i];
        }
        return (float) Math.sqrt(sum / len);
    }

    /**
     * 可选-B：真实降噪（DFSMN 单麦近场模型）。
     * <p>管线内部为 16k float，先上采样至 48k 转 WAV 字节送 DFSMN，
     * 再将增强结果解码回 16k float。</p>
     */
    private float[] denoise(float[] s) {
        try {
            // 16k → 48k 上采样（3 倍线性插值）
            float[] up = new float[s.length * 3];
            for (int i = 0; i < up.length; i++) {
                double pos = i / 3.0;
                int lo = (int) pos;
                int hi = Math.min(lo + 1, s.length - 1);
                float frac = (float) (pos - lo);
                up[i] = s[lo] * (1 - frac) + s[hi] * frac;
            }
            byte[] wavIn = toWavBytes(up);
            byte[] wavOut = com.chua.deeplearning.support.onnx.audio.denoise.DfsmnAnsTranslator
                    .getInstance().translate(wavIn);
            // 解码回 16k float
            float[] enhanced48k = com.chua.deeplearning.support.onnx.audio.denoise.WavDecoder
                    .decodeToFloat(wavOut, 48000);
            float[] down = new float[enhanced48k.length / 3];
            for (int i = 0; i < down.length; i++) {
                down[i] = enhanced48k[i * 3];
            }
            log.info("[AsrPipeline] DFSMN 降噪完成: {} → {} 采样", s.length, down.length);
            return down;
        } catch (Exception e) {
            log.warn("[AsrPipeline] 降噪失败，回退原始音频: {}", e.getMessage());
            return s;
        }
    }

    /** float[] → 48k 16bit 单声道 WAV 字节 */
    private static byte[] toWavBytes(float[] samples) throws Exception {
        try (var baos = new java.io.ByteArrayOutputStream()) {
            for (float v : samples) {
                int x = Math.round(Math.max(-1F, Math.min(1F, v)) * 32767F);
                baos.write(x & 0xFF);
                baos.write((x >> 8) & 0xFF);
            }
            byte[] pcm = baos.toByteArray();
            byte[] header = com.chua.deeplearning.support.onnx.audio.denoise.WavDecoder
                    .buildWavHeader(pcm.length, 48000, 1, 16);
            byte[] out = new byte[header.length + pcm.length];
            System.arraycopy(header, 0, out, 0, header.length);
            System.arraycopy(pcm, 0, out, header.length, pcm.length);
            return out;
        }
    }

    /** 可选-C：压缩空白 + 英文句首大写 */
    static String postProcess(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        if (!t.isEmpty() && Character.isLetter(t.charAt(0))) {
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }

    /** float[] → 16kHz 单声道临时 WAV */
    private static Path toTempWav(float[] samples) throws Exception {
        Path tmp = Files.createTempFile("asr-pipeline-", ".wav");
        tmp.toFile().deleteOnExit();
        try (var baos = new java.io.ByteArrayOutputStream()) {
            for (float v : samples) {
                int x = Math.round(Math.max(-1F, Math.min(1F, v)) * 32767F);
                baos.write(x & 0xFF);
                baos.write((x >> 8) & 0xFF);
            }
            AudioFormat fmt = new AudioFormat(TARGET_SR, 16, 1, true, false);
            try (AudioInputStream ais = new AudioInputStream(
                    new java.io.ByteArrayInputStream(baos.toByteArray()), fmt, samples.length)) {
                AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, tmp.toFile());
            }
        }
        return tmp;
    }

    /** 必选-1：任意音频 → 16kHz 单声道采样（复用各 translator 的解码逻辑） */
    static float[] loadMono16k(Path path) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(path.toUri()))) {
            AudioFormat fmt = in.getFormat();
            byte[] bytes;
            try (var baos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                bytes = baos.toByteArray();
            }
            int bps = fmt.getSampleSizeInBits() / 8;
            int channels = fmt.getChannels();
            int total = bytes.length / Math.max(1, bps);
            int monoLen = total / channels;
            float[] mono = new float[Math.max(1, monoLen)];
            for (int i = 0; i < monoLen; i++) {
                float sum = 0F;
                for (int c = 0; c < channels; c++) {
                    int off = (i * channels + c) * bps;
                    int lo = bytes[off] & 0xFF;
                    int hi = bytes[off + 1];
                    short v = (short) ((hi << 8) | lo);
                    sum += v / 32768.0F;
                }
                mono[i] = sum / channels;
            }
            if (Math.abs(fmt.getSampleRate() - TARGET_SR) < 1F) {
                return mono;
            }
            int newLen = (int) Math.round(mono.length * (double) TARGET_SR / fmt.getSampleRate());
            float[] out = new float[newLen];
            for (int i = 0; i < newLen; i++) {
                double pos = i * fmt.getSampleRate() / (double) TARGET_SR;
                int lo = (int) pos;
                int hi = Math.min(lo + 1, mono.length - 1);
                float frac = (float) (pos - lo);
                out[i] = mono[lo] * (1 - frac) + mono[hi] * frac;
            }
            return out;
        }
    }

    /**
     * 构建器。
     */
    public static final class Builder {

        private final String engineId;
        private String language;
        private boolean vad;
        private boolean denoise;
        private boolean postProcess = true;
        private float silenceRms = DEFAULT_SILENCE_RMS;
        private float minSegSec = DEFAULT_MIN_SEG;
        private float maxSec = DEFAULT_MAX_SEG;

        private Builder(String engineId) {
            if (engineId == null || engineId.isBlank()) {
                throw new IllegalArgumentException("engineId 为必选项");
            }
            this.engineId = engineId;
        }

        /** 可选：识别语言（zh/en 等；whisper 类多语言引擎建议显式指定） */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /** 可选-A：启用能量 VAD 切分（长音频建议开启） */
        public Builder vad(boolean enable) {
            this.vad = enable;
            return this;
        }

        /** 可选-B：启用 DFSMN 降噪（真实实现，内部 16k↔48k 重采样） */
        public Builder denoise(boolean enable) {
            this.denoise = enable;
            return this;
        }

        /** 可选-C：启用文本后处理（默认开） */
        public Builder postProcess(boolean enable) {
            this.postProcess = enable;
            return this;
        }

        /** VAD 静音 RMS 门限 */
        public Builder silenceRms(float threshold) {
            this.silenceRms = threshold;
            return this;
        }

        /** 最短有效语音段秒数 */
        public Builder minSegment(float sec) {
            this.minSegSec = sec;
            return this;
        }

        /** 最大段长秒数 */
        public Builder maxSegment(float sec) {
            this.maxSec = sec;
            return this;
        }

        /** 构建管线实例 */
        public AsrPipeline build() {
            return new AsrPipeline(this);
        }
    }
}
