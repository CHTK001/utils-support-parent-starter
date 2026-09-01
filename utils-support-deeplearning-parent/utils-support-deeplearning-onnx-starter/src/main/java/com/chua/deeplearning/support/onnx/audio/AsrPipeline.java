package com.chua.deeplearning.support.onnx.audio;

import lombok.extern.slf4j.Slf4j;

import com.chua.deeplearning.support.speech.SpeechEnhancer;

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
 *   <li>必选-2 引擎推理：moonshine / whisper / paraformer（VirtualClient SPI）逐段转写</li>
 *   <li>必选-3 结果拼接：多段文本按时间序合并</li>
 *   <li>可选-A 能量 VAD 切分：静音检测切段，长音频必备，短音频可关；
 *       相邻语音段间隙小于 0.6s 自动合并以保持整句上下文</li>
 *   <li>可选-B 降噪预处理：DFSMN 单麦近场降噪（48k 模型，内部自动重采样），
 *       嘈杂场景建议开启</li>
 *   <li>可选-C 后处理：压缩空白、英文句首大写</li>
 * </ul>
 *
 * <p>用法：</p>
 *
 * <pre>{@code
 *   String text = AsrPipeline.builder()
 *           .engine("moonshine")          // 必选：引擎 id
 *           .vad("energy")                // 可选：VAD 类型（默认 null）
 *           .denoise("dfsmn-ans")         // 可选：降噪模型 ID（默认 null）
 *           .postProcess(true)            // 可选：默认 true
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

    /**
     * 引擎 id（必选）
     */
    private final String engineId;

    /**
     * 识别语言（可选，whisper 类多语言引擎建议显式指定）
     */
    private final String language;

    /**
     * VAD 类型（null 表示不做 VAD）。
     */
    private final String vadType;

    /**
     * 降噪增强器（null 表示不降噪）。
     */
    private final SpeechEnhancer denoiseEnhancer;

    /**
     * 是否启用文本后处理（可选，默认开）
     */
    private final boolean postProcess;

    /**
     * VAD 静音 RMS 门限
     */
    private final float silenceRms;

    /**
     * 最短有效语音段（秒）
     */
    private final float minSegSec;

    /**
     * 最大段长（秒）
     */
    private final float maxSegSec;

    private AsrPipeline(Builder b) {
        this.engineId = b.engineId;
        this.language = b.language;
        this.vadType = b.vadType;
        this.denoiseEnhancer = b.denoiseEnhancer;
        this.postProcess = b.postProcess;
        this.silenceRms = b.silenceRms;
        this.minSegSec = b.minSegSec;
        this.maxSegSec = b.maxSegSec;
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
     * 执行管线：解码 → 降噪 → VAD 切分 → 引擎转写 → 拼接 → 后处理。
     *
     * @param wavPath 输入音频
     * @return 全文转写结果；无语音时返回空串
     * @throws Exception 管线失败
     */
    public String transcribe(Path wavPath) throws Exception {
        long t0 = System.currentTimeMillis();
        float[] samples = AudioUtils.loadMono16k(wavPath);
        log.info("[AsrPipeline] 解码完成: {} 采样 ({}, {}s)", samples.length,
                wavPath.getFileName(), String.format("%.1f", samples.length / (float) TARGET_SR));

        if (denoiseEnhancer != null) {
            samples = denoise(samples);
        }

        List<float[]> segments;
        if (vadType != null) {
            segments = splitByVad(samples, vadType);
        } else {
            segments = new ArrayList<>(forceSplit(samples));
        }

        List<String> parts = new ArrayList<>(segments.size());
        try (com.chua.common.support.ai.audio.VirtualClient client =
                     com.chua.common.support.ai.audio.VirtualClient.create(engineId, "")) {
            if (language != null && !language.isBlank()) {
                client.language(language);
            }
            for (int i = 0; i < segments.size(); i++) {
                Path tmp = AudioUtils.writeTempWav(
                        "asr-pipeline-", segments.get(i), TARGET_SR);
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

        String text = String.join(" ", parts);
        if (postProcess) {
            text = postProcess(text);
        }
        log.info("[AsrPipeline] 完成: {} 字符, {}ms", text.length(), System.currentTimeMillis() - t0);
        return text;
    }

    /**
     * 可选-A：按类型 VAD 切分。
     *
     * @param s    16kHz 单声道采样
     * @param type VAD 类型（"energy" / "silero" 等）
     * @return 语音段列表
     */
    private List<float[]> splitByVad(float[] s, String type) {
        return switch (type.toLowerCase()) {
            case "energy" -> splitByEnergy(s, silenceRms, minSegSec, maxSegSec);
            default -> List.of(s);
        };
    }

    /**
     * 能量 VAD 切分（静态通用实现）。
     *
     * <p>RMS 门限判定有声帧；短于最小时长的片段丢弃；相邻语音段间隙小于
     * 0.6 秒时自动合并为完整语句；超过最大段长强制二次切分。</p>
     *
     * @param s           16kHz 单声道采样
     * @param silenceRms  静音 RMS 门限
     * @param minSegSec   最短语音段秒数
     * @param maxSegSec   最大段长秒数
     * @return 语音段列表
     */
    static List<float[]> splitByEnergy(float[] s, float silenceRms, float minSegSec, float maxSegSec) {
        int frame = (int) (0.03F * TARGET_SR);
        int minSeg = (int) (minSegSec * TARGET_SR);
        int maxSeg = (int) (maxSegSec * TARGET_SR);
        int mergeGap = (int) (0.6F * TARGET_SR);

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

        // 合并短停顿的相邻段，保持完整语句上下文（对 moonshine 类模型尤为关键）
        List<int[]> merged = new ArrayList<>(voiced.size());
        for (int[] r : voiced) {
            if (!merged.isEmpty() && r[0] - merged.get(merged.size() - 1)[1] < mergeGap) {
                merged.get(merged.size() - 1)[1] = r[1];
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

    /**
     * 关闭 VAD 时按最大段长强制切块。
     *
     * @param s 16kHz 单声道采样
     * @return 分块列表
     */
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

    /**
     * 计算帧 RMS 能量。
     *
     * @param s 采样
     * @param off 起始偏移
     * @param len 帧长
     * @return RMS 值
     */
    private static float rms(float[] s, int off, int len) {
        double sum = 0;
        for (int i = off; i < off + len; i++) {
            sum += s[i] * s[i];
        }
        return (float) Math.sqrt(sum / len);
    }

    /**
     * 可选-B：降噪预处理（委托给 SpeechEnhancer）。
     *
     * <p>管线内部为 16k float：先上采样至 48k 封装 WAV 送增强器，
     * 再将增强结果解码回 16k float；失败时回退原始音频。</p>
     *
     * @param s 16kHz 采样
     * @return 增强后采样
     */
    private float[] denoise(float[] s) {
        try {
            float[] up = AudioUtils.resample(s, TARGET_SR, 48000);
            byte[] wavIn = AudioUtils.toWavBytes(up, 48000);
            byte[] wavOut = denoiseEnhancer.enhance(wavIn);
            float[] enhanced48k = com.chua.deeplearning.support.onnx.audio.denoise.WavDecoder
                    .decodeToFloat(wavOut, 48000);
            float[] down = AudioUtils.resample(enhanced48k, 48000, TARGET_SR);
            log.info("[AsrPipeline] 降噪完成: {} → {} 采样", s.length, down.length);
            return down;
        } catch (Exception e) {
            log.warn("[AsrPipeline] 降噪失败，回退原始音频: {}", e.getMessage());
            return s;
        }
    }

    /**
     * 可选-C：压缩连续空白并大写英文句首字符。
     *
     * @param text 原始文本
     * @return 后处理文本
     */
    static String postProcess(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        if (!t.isEmpty() && Character.isLetter(t.charAt(0))) {
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }

    /**
     * 构建器。
     */
    public static final class Builder {

        /**
         * 引擎 id（必选）
         */
        private final String engineId;

        /**
         * 识别语言（可选）
         */
        private String language;

        /**
         * VAD 类型（null 表示不做 VAD）
         */
        private String vadType;

        /**
         * 降噪增强器（null 表示不降噪）
         */
        private SpeechEnhancer denoiseEnhancer;

        /**
         * 启用文本后处理（默认 true）
         */
        private boolean postProcess = true;

        /**
         * 静音 RMS 门限（默认 0.01）
         */
        private float silenceRms = DEFAULT_SILENCE_RMS;

        /**
         * 最短语音段秒数（默认 0.4）
         */
        private float minSegSec = DEFAULT_MIN_SEG;

        /**
         * 最大段长秒数（默认 28）
         */
        private float maxSegSec = DEFAULT_MAX_SEG;

        private Builder(String engineId) {
            if (engineId == null || engineId.isBlank()) {
                throw new IllegalArgumentException("engineId 为必选项");
            }
            this.engineId = engineId;
        }

        /**
         * 可选：识别语言（zh/en 等；whisper 类多语言引擎建议显式指定）。
         *
         * @param language 语言代码
         * @return 构建器
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * 可选-A：启用能量 VAD 切分（长音频建议开启）。
         *
         * <p>统一 provider 模式（与 FacePipeline 一致），按类型字符串选择切分策略：
         * <pre>{@code
         * .vad("energy")   // 能量 VAD（默认参数）
         * }</pre>
         *
         * @param type VAD 类型（"energy" 等），null 关闭
         * @return 构建器
         */
        public Builder vad(String type) {
            this.vadType = type;
            return this;
        }

        /**
         * 可选-B：降噪模型 ID（嘈杂场景建议开启）。
         *
         * <p>统一 provider 模式（与 FacePipeline 一致），按模型 ID 从 ModelRegistry 解析：
         * <pre>{@code
         * .denoise("dfsmn-ans")  // DFSMN 单麦近场降噪
         * }</pre>
         *
         * @param modelId 模型 ID（对应 {@link SpeechEnhancer} 注册表），null 关闭
         * @return 构建器
         */
        public Builder denoise(String modelId) {
            this.denoiseEnhancer = modelId != null ? SpeechEnhancer.create(modelId) : null;
            return this;
        }

        /**
         * 可选-C：启用文本后处理（默认开）。
         *
         * @param enable 是否启用
         * @return 构建器
         */
        public Builder postProcess(boolean enable) {
            this.postProcess = enable;
            return this;
        }

        /**
         * 设置 VAD 静音 RMS 门限。
         *
         * @param threshold 门限值
         * @return 构建器
         */
        public Builder silenceRms(float threshold) {
            this.silenceRms = threshold;
            return this;
        }

        /**
         * 设置最短有效语音段秒数。
         *
         * @param sec 秒数
         * @return 构建器
         */
        public Builder minSegment(float sec) {
            this.minSegSec = sec;
            return this;
        }

        /**
         * 设置最大段长秒数。
         *
         * @param sec 秒数
         * @return 构建器
         */
        public Builder maxSegment(float sec) {
            this.maxSegSec = sec;
            return this;
        }

        /**
         * 构建管线实例。
         *
         * @return 管线实例
         */
        public AsrPipeline build() {
            return new AsrPipeline(this);
        }
    }
}

