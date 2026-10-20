package com.chua.deeplearning.support.sherpa;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * sherpa-onnx 语音活动检测（Silero VAD）封装。
 *
 * <p>用途是把长音频切成「只含人声」的片段，再交给离线识别器逐段解码——
 * 这是 sherpa-onnx 官方推荐的「VAD + 离线模型」组合，既避免超长音频的显存/内存压力，
 * 也能过滤纯静音段从而显著减少误识别。同时它也是把离线模型当作「模拟流式」使用的基础。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * try (SherpaVadSegmenter vad = SherpaVadSegmenter.builder()
 *         .model("/path/to/silero_vad.onnx")
 *         .build()) {
 *     List<float[]> segments = vad.split(samples);
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SherpaVadSegmenter implements AutoCloseable {

    /**
     * 底层 VAD 实例。
     */
    private final Vad vad;

    /**
     * 采样率（Hz）。
     */
    private final int sampleRate;

    /**
     * 窗口采样数（Silero VAD 要求 512 @16k）。
     */
    private final int windowSize;

    /**
     * 是否已 flush 尾部。
     */
    private boolean flushed;

    /**
     * 构造分段器。
     *
     * @param vad        底层 VAD
     * @param sampleRate 采样率
     * @param windowSize 窗口采样数
     */
    private SherpaVadSegmenter(Vad vad, int sampleRate, int windowSize) {
        this.vad = vad;
        this.sampleRate = sampleRate;
        this.windowSize = windowSize;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 按 VAD 切分整段音频。
     *
     * <p>内部按 {@link #windowSize} 逐窗送入检测器，遇到语音段结束即收集；末尾残余语音在
     * 返回前 flush 出来。若整段都未被判定为语音，则回退为「整段作为单一片段」，
     * 避免静音阈值过严导致完全无输出。</p>
     *
     * @param samples 完整音频采样（采样率需与构建时一致）
     * @return 语音片段列表（保持时间顺序）
     */
    public List<float[]> split(float[] samples) {
        List<float[]> result = new ArrayList<>();
        for (VoiceSegment segment : splitWithOffsets(samples)) {
            result.add(segment.samples());
        }
        return result;
    }

    /**
     * 按 VAD 切分整段音频，并保留每段在原始音频中的起始采样偏移。
     *
     * <p>与 {@link #split(float[])} 的区别是返回值带偏移量：识别结果要换算「相对整段音频」
     * 的绝对时间戳时必须用真实偏移，而不能用「已处理片段长度累加」（VAD 段之间被丢弃的
     * 静音会导致后者偏差持续累积）。</p>
     *
     * @param samples 完整音频采样
     * @return 带偏移的语音片段列表
     */
    public List<VoiceSegment> splitWithOffsets(float[] samples) {
        List<VoiceSegment> segments = new ArrayList<>();
        if (samples == null || samples.length == 0) {
            return segments;
        }
        // 每次切分都从干净状态开始：否则同一实例连续处理多段音频时，
        // 上一段残留的缓冲会串到下一段，导致片段边界与偏移错乱。
        vad.reset();
        flushed = false;
        int offset = 0;
        while (offset + windowSize <= samples.length) {
            float[] window = new float[windowSize];
            System.arraycopy(samples, offset, window, 0, windowSize);
            vad.acceptWaveform(window);
            drain(segments);
            offset += windowSize;
        }
        if (offset < samples.length) {
            float[] tail = new float[samples.length - offset];
            System.arraycopy(samples, offset, tail, 0, tail.length);
            float[] padded = new float[windowSize];
            System.arraycopy(tail, 0, padded, 0, tail.length);
            vad.acceptWaveform(padded);
            drain(segments);
        }
        if (!flushed) {
            vad.flush();
            drain(segments);
            flushed = true;
        }
        if (segments.isEmpty()) {
            // 整段未检出语音：退化为整段，避免严格阈值下完全无输出
            segments.add(new VoiceSegment(0, samples.clone()));
        }
        return segments;
    }

    /**
     * 取走检测器当前已就绪的全部语音段。
     *
     * @param into 结果收集列表
     */
    private void drain(List<VoiceSegment> into) {
        while (!vad.empty()) {
            SpeechSegment segment = vad.front();
            if (segment == null) {
                break;
            }
            float[] data = segment.getSamples();
            if (data != null && data.length > 0) {
                into.add(new VoiceSegment(Math.max(0, segment.getStart()), data));
            }
            vad.pop();
        }
    }

    /**
     * 语音片段：起始采样偏移 + 采样数据。
     *
     * @param startSample 该片段在原始音频中的起始采样偏移
     * @param samples     片段采样
     */
    public record VoiceSegment(int startSample, float[] samples) {

        /**
         * 计算该片段起始时间（秒）。
         *
         * @param sampleRate 采样率（Hz）
         * @return 起始时间（秒）
         */
        public double startSeconds(int sampleRate) {
            return SherpaWavUtils.durationSeconds(startSample, sampleRate);
        }
    }

    /**
     * 重置检测器内部状态，便于复用同一实例处理下一段音频。
     */
    public void reset() {
        vad.reset();
        flushed = false;
    }

    /**
     * 释放原生资源。
     */
    @Override
    public void close() {
        vad.release();
    }

    /**
     * 获取采样率。
     *
     * @return 采样率（Hz）
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * VAD 分段器构建器。
     */
    public static final class Builder {

        /**
         * silero_vad.onnx 路径。
         */
        private String model;

        /**
         * 采样率。
         */
        private int sampleRate = SherpaWavUtils.DEFAULT_SAMPLE_RATE;

        /**
         * 语音判定阈值。
         */
        private float threshold = 0.5F;

        /**
         * 最小静音时长（秒）。
         */
        private float minSilenceDuration = 0.25F;

        /**
         * 最小语音时长（秒）。
         */
        private float minSpeechDuration = 0.25F;

        /**
         * 单段最大时长（秒）。
         */
        private float maxSpeechDuration = 20.0F;

        /**
         * 窗口采样数。
         */
        private int windowSize = 512;

        /**
         * 线程数。
         */
        private int numThreads;

        /**
         * 设置模型路径。
         *
         * @param model silero_vad.onnx 路径
         * @return 当前构建器
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 设置模型路径。
         *
         * @param model 模型路径
         * @return 当前构建器
         */
        public Builder model(Path model) {
            this.model = model == null ? null : model.toString();
            return this;
        }

        /**
         * 设置采样率。
         *
         * @param sampleRate 采样率（Hz）
         * @return 当前构建器
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * 设置语音判定阈值。
         *
         * @param threshold 阈值（0~1）
         * @return 当前构建器
         */
        public Builder threshold(float threshold) {
            this.threshold = threshold;
            return this;
        }

        /**
         * 设置最小静音时长。
         *
         * @param seconds 秒
         * @return 当前构建器
         */
        public Builder minSilenceDuration(float seconds) {
            this.minSilenceDuration = seconds;
            return this;
        }

        /**
         * 设置最小语音时长。
         *
         * @param seconds 秒
         * @return 当前构建器
         */
        public Builder minSpeechDuration(float seconds) {
            this.minSpeechDuration = seconds;
            return this;
        }

        /**
         * 设置单段最大时长。
         *
         * @param seconds 秒
         * @return 当前构建器
         */
        public Builder maxSpeechDuration(float seconds) {
            this.maxSpeechDuration = seconds;
            return this;
        }

        /**
         * 设置窗口采样数。
         *
         * @param windowSize 采样数
         * @return 当前构建器
         */
        public Builder windowSize(int windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        /**
         * 设置线程数。
         *
         * @param numThreads 线程数
         * @return 当前构建器
         */
        public Builder numThreads(int numThreads) {
            this.numThreads = numThreads;
            return this;
        }

        /**
         * 构建 VAD 分段器。
         *
         * @return 分段器实例
         * @throws SherpaException 模型文件缺失或原生库不可用
         */
        public SherpaVadSegmenter build() {
            if (model == null || model.isBlank()) {
                throw new SherpaException("未指定 silero_vad.onnx 路径");
            }
            Path path = Path.of(model);
            if (!Files.isRegularFile(path)) {
                throw new SherpaException("silero_vad.onnx 不存在: " + path);
            }
            SherpaNativeLoader.ensureLoaded();
            SileroVadModelConfig silero = SileroVadModelConfig.builder()
                    .setModel(path.toString())
                    .setThreshold(threshold)
                    .setMinSilenceDuration(minSilenceDuration)
                    .setMinSpeechDuration(minSpeechDuration)
                    .setMaxSpeechDuration(maxSpeechDuration)
                    .setWindowSize(windowSize)
                    .build();
            VadModelConfig config = VadModelConfig.builder()
                    .setSileroVadModelConfig(silero)
                    .setSampleRate(sampleRate)
                    .setNumThreads(SherpaModelResolver.effectiveThreads(numThreads))
                    .setDebug(false)
                    // 显式置空 provider 使用默认 cpu：Silero VAD 通常只随 cpu 版 native 分发
                    .setProvider("cpu")
                    .build();
            return new SherpaVadSegmenter(new Vad(config), sampleRate, windowSize);
        }
    }
}

