package com.chua.deeplearning.support.sherpa;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * sherpa-onnx 流式（在线）语音识别器。
 *
 * <p>与 {@link SherpaRecognizer} 的离线识别不同，流式识别器维护一个可增量喂入音频的
 * {@link OnlineStream}：麦克风按 100~200ms 切片喂入即可获得实时增量文本，
 * 由 sherpa-onnx 内部完成 fbank 缓存、chunk 编码与 transducer 解码，无需 Java 侧再做
 * 分块状态管理。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * try (SherpaStreamingRecognizer asr = SherpaStreamingRecognizer.builder()
 *         .model("/models/streaming-zipformer")
 *         .build()) {
 *     asr.accept(micChunk);                    // 反复喂入 float[] 切片
 *     String partial = asr.partialText();      // 实时增量文本
 *     if (asr.isEndpoint()) { asr.reset(); }   // 一句话结束
 *     String finalText = asr.finish();         // 收尾并取最终文本
 * }
 * }</pre>
 *
 * <p>线程安全：单个实例内部持有唯一的 {@link OnlineStream}，不支持并发；
 * 每个并发会话请创建独立实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SherpaStreamingRecognizer implements AutoCloseable {

    /**
     * 底层流式识别器。
     */
    private final OnlineRecognizer recognizer;

    /**
     * 当前会话的音频流。
     */
    private OnlineStream stream;

    /**
     * 采样率（Hz）。
     */
    private final int sampleRate;

    /**
     * 模型目录。
     */
    private final Path modelDir;

    /**
     * 是否启用端点检测（一句话结束自动触发 {@link #isEndpoint()}）。
     */
    private final boolean endpointEnabled;

    /**
     * 上一次读取到的文本，用于计算增量。
     */
    private String lastText = "";

    /**
     * 构造流式识别器。
     *
     * @param recognizer      底层识别器
     * @param sampleRate      采样率
     * @param modelDir        模型目录
     * @param endpointEnabled 是否启用端点检测
     */
    private SherpaStreamingRecognizer(OnlineRecognizer recognizer, int sampleRate, Path modelDir,
                                      boolean endpointEnabled) {
        this.recognizer = recognizer;
        this.sampleRate = sampleRate;
        this.modelDir = modelDir;
        this.endpointEnabled = endpointEnabled;
        this.stream = recognizer.createStream();
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
     * 喂入一段音频并立即解码当前可用帧。
     *
     * @param samples [-1, 1] 浮点采样切片（建议 1600~3200 个采样，即 100~200ms）
     */
    public void accept(float[] samples) {
        if (samples == null || samples.length == 0) {
            return;
        }
        stream.acceptWaveform(samples, sampleRate);
        decodeReady();
    }

    /**
     * 喂入音频文件并逐块解码（便于离线回放测试）。
     *
     * @param path      音频文件路径
     * @param chunkSize 每次喂入的采样数
     * @return 最终识别文本
     */
    public String acceptFile(Path path, int chunkSize) {
        float[] samples = SherpaWavUtils.readMono(path, sampleRate);
        int size = chunkSize > 0 ? chunkSize : sampleRate / 10;
        for (int offset = 0; offset < samples.length; offset += size) {
            int length = Math.min(size, samples.length - offset);
            float[] chunk = new float[length];
            System.arraycopy(samples, offset, chunk, 0, length);
            accept(chunk);
        }
        return finish();
    }

    /**
     * 解码当前已就绪的帧（内部循环直到 {@code isReady} 为 false）。
     */
    private void decodeReady() {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
    }

    /**
     * 取当前整句文本（每次覆盖，非增量）。
     *
     * @return 当前识别文本，可能为空串
     */
    public String text() {
        decodeReady();
        OnlineRecognizerResult result = recognizer.getResult(stream);
        String text = result == null ? null : result.getText();
        return text == null ? "" : text.trim();
    }

    /**
     * 取自上次调用以来新增的文本（增量）。
     *
     * <p>流式模型输出的句子会随上下文修正，因此增量按「公共前缀」对齐：
     * 新结果若不以旧结果开头，则整句重发，避免增量内容错位。</p>
     *
     * @return 增量文本；无新增时返回空串
     */
    public String partialText() {
        String current = text();
        if (current.isEmpty()) {
            return "";
        }
        if (current.startsWith(lastText)) {
            String delta = current.substring(lastText.length());
            lastText = current;
            return delta.trim();
        }
        lastText = current;
        return current;
    }

    /**
     * 是否检测到一句话结束（需启用端点检测）。
     *
     * @return true 表示当前句已结束
     */
    public boolean isEndpoint() {
        return endpointEnabled && recognizer.isEndpoint(stream);
    }

    /**
     * 重置当前句的状态（端点命中后调用，开始识别下一句）。
     */
    public void reset() {
        recognizer.reset(stream);
        lastText = "";
    }

    /**
     * 结束输入并返回最终文本。
     *
     * @return 最终识别文本
     */
    public String finish() {
        stream.inputFinished();
        decodeReady();
        String text = text();
        lastText = "";
        return text;
    }

    /**
     * 模型目录。
     *
     * @return 模型目录
     */
    public Path modelDir() {
        return modelDir;
    }

    /**
     * 采样率。
     *
     * @return 采样率（Hz）
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * 释放原生资源。
     */
    @Override
    public void close() {
        try {
            stream.release();
        } catch (RuntimeException e) {
            log.debug("[sherpa] 释放流失败: {}", e.getMessage());
        }
        try {
            recognizer.release();
        } catch (RuntimeException e) {
            log.debug("[sherpa] 释放流式识别器失败: {}", e.getMessage());
        }
    }

    /**
     * 流式识别器构建器。
     */
    public static final class Builder {

        /**
         * 模型标识或目录。
         */
        private String model;

        /**
         * 语言提示。
         */
        private String language;

        /**
         * 推理 provider。
         */
        private String provider;

        /**
         * 线程数。
         */
        private int numThreads;

        /**
         * 采样率。
         */
        private int sampleRate = SherpaWavUtils.DEFAULT_SAMPLE_RATE;

        /**
         * 是否启用端点检测。
         */
        private boolean endpointEnabled = true;

        /**
         * 尾部静音触发端点的时长（秒）。
         */
        private float endpointTrailingSilence = 2.4F;

        /**
         * 模型类型（空表示按 transducer 处理）。
         */
        private String modelType;

        /**
         * 设置模型标识或目录。
         *
         * @param model 模型标识 / 目录 / 模型文件
         * @return 当前构建器
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 设置模型目录。
         *
         * @param model 目录
         * @return 当前构建器
         */
        public Builder model(Path model) {
            this.model = model == null ? null : model.toString();
            return this;
        }

        /**
         * 设置语言提示（仅记录，用于日志/诊断）。
         *
         * <p>sherpa-onnx 的流式模型（Transducer / Zipformer2-CTC）在模型内部固定语言，
         * 不接受运行时语言参数；此处保留该 setter 是为了让 SPI 客户端可以无条件透传
         * {@code language(...)}，不必为流式/离线分别分支。</p>
         *
         * @param language 语言代码
         * @return 当前构建器
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * 设置推理 provider。
         *
         * @param provider cpu / cuda 等
         * @return 当前构建器
         */
        public Builder provider(String provider) {
            this.provider = provider;
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
         * 设置是否启用端点检测。
         *
         * @param endpointEnabled true 表示启用
         * @return 当前构建器
         */
        public Builder endpointEnabled(boolean endpointEnabled) {
            this.endpointEnabled = endpointEnabled;
            return this;
        }

        /**
         * 设置尾部静音触发端点的时长。
         *
         * @param seconds 秒
         * @return 当前构建器
         */
        public Builder endpointTrailingSilence(float seconds) {
            this.endpointTrailingSilence = seconds;
            return this;
        }

        /**
         * 设置模型类型（流式侧仅支持 transducer 与 zipformer2-ctc）。
         *
         * @param modelType 模型类型
         * @return 当前构建器
         */
        public Builder modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }

        /**
         * 构建流式识别器。
         *
         * @return 流式识别器
         * @throws SherpaException 模型或原生库不可用
         */
        public SherpaStreamingRecognizer build() {
            Path dir = SherpaModelResolver.resolveDirectory(model);
            Path tokens = SherpaModelResolver.findTokens(dir);
            if (tokens == null) {
                throw new SherpaException("流式模型目录缺少 tokens.txt: " + dir);
            }
            SherpaNativeLoader.ensureLoaded();

            OnlineModelConfig.Builder modelConfig = OnlineModelConfig.builder()
                    .setTokens(tokens.toString())
                    .setNumThreads(SherpaModelResolver.effectiveThreads(numThreads))
                    .setDebug(false)
                    .setProvider(SherpaModelResolver.effectiveProvider(provider));

            Path encoder = SherpaModelResolver.findFile(dir, "encoder");
            Path decoder = SherpaModelResolver.findFile(dir, "decoder");
            Path joiner = SherpaModelResolver.findFile(dir, "joiner");
            if (encoder != null && decoder != null && joiner != null) {
                modelConfig.setTransducer(OnlineTransducerModelConfig.builder()
                        .setEncoder(encoder.toString())
                        .setDecoder(decoder.toString())
                        .setJoiner(joiner.toString())
                        .build());
            } else {
                Path single = SherpaModelResolver.findFile(dir, "model");
                if (single == null) {
                    throw new SherpaException("流式模型目录结构不符（既非 encoder/decoder/joiner，"
                            + "也无 model*.onnx）: " + dir);
                }
                modelConfig.setZipformer2Ctc(OnlineZipformer2CtcModelConfig.builder()
                        .setModel(single.toString())
                        .build());
            }
            if (modelType != null && !modelType.isBlank()) {
                modelConfig.setModelType(modelType);
            }
            if (language != null && !language.isBlank()) {
                log.info("[sherpa] 流式模型语言由模型自身决定，忽略 language={}（目录 {}）", language, dir);
            }

            EndpointRule rule1 = EndpointRule.builder()
                    .setMustContainNonSilence(false)
                    .setMinTrailingSilence(endpointTrailingSilence)
                    .setMinUtteranceLength(0F)
                    .build();
            EndpointConfig endpointConfig = EndpointConfig.builder().setRule1(rule1).build();

            OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                    .setFeatureConfig(FeatureConfig.builder()
                            .setSampleRate(sampleRate)
                            .setFeatureDim(80)
                            .setDither(0F)
                            .build())
                    .setOnlineModelConfig(modelConfig.build())
                    .setEndpointConfig(endpointConfig)
                    .setEnableEndpoint(endpointEnabled)
                    .setDecodingMethod("greedy_search")
                    .build();
            OnlineRecognizer recognizer;
            try {
                recognizer = new OnlineRecognizer(config);
            } catch (RuntimeException e) {
                throw new SherpaException("创建 sherpa-onnx 流式识别器失败（目录 " + dir + "）: "
                        + e.getMessage(), e);
            }
            return new SherpaStreamingRecognizer(recognizer, sampleRate, dir, endpointEnabled);
        }
    }

    /**
     * 判断目录是否为可用的流式模型目录（供能力探测使用）。
     *
     * @param dir 目录
     * @return true 表示存在 tokens 与至少一个 onnx
     */
    public static boolean isStreamingModelDir(Path dir) {
        return dir != null && Files.isDirectory(dir) && SherpaModelResolver.isUsableModelDir(dir);
    }
}
