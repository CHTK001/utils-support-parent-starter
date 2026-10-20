package com.chua.deeplearning.support.sherpa;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * sherpa-onnx 离线语音识别器（原生 API 的 Java 侧封装）。
 *
 * <p>这是本模块的核心：直接调用 sherpa-onnx 官方 JNI（{@link OfflineRecognizer}），
 * 因此 Paraformer / SenseVoice / Whisper / Zipformer-Transducer / Zipformer-CTC 五种
 * 模型族共用同一套调用链，前处理（fbank）、解码与时间戳全部由 sherpa-onnx C++ 层完成，
 * 无需再在 Java 里复刻任何一种模型的推理流程。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * try (SherpaRecognizer recognizer = SherpaRecognizer.builder()
 *         .model("/models/sensevoice")     // 或模型目录 / 模型标识
 *         .vad("/models/silero_vad.onnx")  // 可选：开启 VAD 分段，长音频更稳
 *         .build()) {
 *     SherpaRecognitionResult result = recognizer.recognize(Path.of("a.wav"));
 *     System.out.println(result.getText());
 * }
 * }</pre>
 *
 * <p>线程安全：{@link OfflineRecognizer} 本身可在多线程下创建多个 stream，
 * 但本类为简化状态管理（VAD 实例不可并发复用）按「单实例单线程」设计，
 * 多线程场景请为每个线程创建独立实例（或使用客户端层的对象池）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SherpaRecognizer implements AutoCloseable {

    /**
     * 拼接多段文本时的分隔符。
     */
    private static final String SEGMENT_JOINER = "\n";

    /**
     * 底层离线识别器。
     */
    private final OfflineRecognizer recognizer;

    /**
     * 模型族。
     */
    private final SherpaModelType modelType;

    /**
     * 模型目录。
     */
    private final Path modelDir;

    /**
     * 采样率（Hz）。
     */
    private final int sampleRate;

    /**
     * VAD 分段器（可为 {@code null} 表示不分段）。
     */
    private final SherpaVadSegmenter vad;

    /**
     * 语言提示（SenseVoice / Whisper 使用）。
     */
    private final String language;

    /**
     * 构造识别器。
     *
     * @param recognizer 底层识别器
     * @param modelType  模型族
     * @param modelDir   模型目录
     * @param sampleRate 采样率
     * @param vad        VAD 分段器，可为 {@code null}
     * @param language   语言提示，可为 {@code null}
     */
    private SherpaRecognizer(OfflineRecognizer recognizer, SherpaModelType modelType, Path modelDir,
                             int sampleRate, SherpaVadSegmenter vad, String language) {
        this.recognizer = recognizer;
        this.modelType = modelType;
        this.modelDir = modelDir;
        this.sampleRate = sampleRate;
        this.vad = vad;
        this.language = language;
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
     * 转写音频文件。
     *
     * @param path 音频文件路径（WAV 等 JDK 可解码格式）
     * @return 识别结果
     */
    public SherpaRecognitionResult recognize(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new SherpaException("音频文件不存在: " + path);
        }
        return recognize(SherpaWavUtils.readMono(path, sampleRate));
    }

    /**
     * 转写音频字节。
     *
     * @param audio 音频字节
     * @return 识别结果
     */
    public SherpaRecognitionResult recognize(byte[] audio) {
        return recognize(SherpaWavUtils.readMono(audio, sampleRate));
    }

    /**
     * 转写已解码的浮点采样。
     *
     * <p>有 VAD 时分段解码并把每段时间戳换算为绝对时间；无 VAD 时整段一次解码。
     * 分段结果为空或全部为空文本时，退化为整段解码，保证不丢内容。</p>
     *
     * @param samples [-1, 1] 浮点采样（采样率需与 {@link #sampleRate()} 一致）
     * @return 识别结果
     */
    public SherpaRecognitionResult recognize(float[] samples) {
        if (samples == null || samples.length == 0) {
            throw new SherpaException("音频采样为空");
        }
        long started = System.currentTimeMillis();
        double duration = SherpaWavUtils.durationSeconds(samples.length, sampleRate);
        List<SherpaRecognitionResult.Segment> segments = new ArrayList<>();
        if (vad != null) {
            int index = 0;
            // 用 VAD 给出的真实采样偏移换算绝对时间：段间被丢弃的静音不计入，
            // 若按「已处理长度累加」会让后续段的时间戳持续提前。
            for (SherpaVadSegmenter.VoiceSegment chunk : vad.splitWithOffsets(samples)) {
                double chunkStart = chunk.startSeconds(sampleRate);
                OfflineRecognizerResult raw = decode(chunk.samples());
                String text = raw == null ? null : raw.getText();
                if (text != null && !text.isBlank()) {
                    segments.add(toSegment(index++, chunkStart, chunk.samples(), raw));
                }
            }
        }
        if (segments.isEmpty()) {
            OfflineRecognizerResult raw = decode(samples);
            if (raw != null) {
                segments.add(toSegment(0, 0D, samples, raw));
            }
        }
        return aggregate(segments, duration, System.currentTimeMillis() - started);
    }

    /**
     * 对单段采样执行一次原生解码。
     *
     * @param samples 单段采样
     * @return 原生结果，失败返回 {@code null}
     */
    private OfflineRecognizerResult decode(float[] samples) {
        OfflineStream stream = null;
        try {
            stream = recognizer.createStream();
            stream.acceptWaveform(samples, sampleRate);
            recognizer.decode(stream);
            return recognizer.getResult(stream);
        } catch (RuntimeException e) {
            log.warn("[sherpa] 解码失败（{} 族，{} 采样）: {}", modelType.code(), samples.length, e.getMessage());
            return null;
        } finally {
            if (stream != null) {
                stream.release();
            }
        }
    }

    /**
     * 把原生结果转换为本模块的分段对象。
     *
     * @param index      段序号
     * @param start       段起始时间（秒）
     * @param samples    该段采样
     * @param raw        原生结果
     * @return 分段对象
     */
    private SherpaRecognitionResult.Segment toSegment(int index, double start, float[] samples,
                                                      OfflineRecognizerResult raw) {
        double end = start + SherpaWavUtils.durationSeconds(samples.length, sampleRate);
        return SherpaRecognitionResult.Segment.builder()
                .index(index)
                .start(start)
                .end(end)
                .text(raw.getText() == null ? "" : raw.getText().trim())
                .tokens(raw.getTokens())
                .timestamps(raw.getTimestamps())
                .language(emptyToNull(raw.getLang()))
                .emotion(emptyToNull(raw.getEmotion()))
                .events(splitEvents(raw.getEvent()))
                .build();
    }

    /**
     * 汇总分段结果为整体结果。
     *
     * @param segments 分段列表
     * @param duration 音频时长（秒）
     * @param elapsed  耗时（毫秒）
     * @return 整体结果
     */
    private SherpaRecognitionResult aggregate(List<SherpaRecognitionResult.Segment> segments,
                                              double duration, long elapsed) {
        StringBuilder text = new StringBuilder();
        String language = null;
        String emotion = null;
        List<String> events = new ArrayList<>();
        for (SherpaRecognitionResult.Segment segment : segments) {
            if (segment.getText() != null && !segment.getText().isBlank()) {
                if (text.length() > 0) {
                    text.append(SEGMENT_JOINER);
                }
                text.append(segment.getText());
            }
            if (language == null) {
                language = segment.getLanguage();
            }
            if (emotion == null) {
                emotion = segment.getEmotion();
            }
            if (segment.getEvents() != null) {
                for (String event : segment.getEvents()) {
                    if (!events.contains(event)) {
                        events.add(event);
                    }
                }
            }
        }
        return SherpaRecognitionResult.builder()
                .text(text.toString())
                .language(language)
                .emotion(emotion)
                .events(events.isEmpty() ? null : events)
                .segments(segments)
                .modelType(modelType)
                .duration(duration)
                .elapsedMillis(elapsed)
                .build();
    }

    /**
     * 把空串归一为 {@code null}。
     *
     * @param value 原值
     * @return 归一结果
     */
    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 拆分事件标签（sherpa-onnx 以 {@code <|...|>} 或逗号分隔返回）。
     *
     * @param raw 原始事件串
     * @return 标签列表，无事件时返回 {@code null}
     */
    private static List<String> splitEvents(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> events = new ArrayList<>();
        String cleaned = raw.replace("<|", "").replace("|>", "");
        for (String part : cleaned.split("[,;\\s]+")) {
            String item = part.trim();
            if (!item.isEmpty() && !"unknown".equalsIgnoreCase(item)) {
                events.add(item);
            }
        }
        return events.isEmpty() ? null : events;
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
     * 模型族。
     *
     * @return 模型族
     */
    public SherpaModelType modelType() {
        return modelType;
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
     * 语言提示。
     *
     * @return 语言代码，可为 {@code null}
     */
    public String language() {
        return language;
    }

    /**
     * 是否启用 VAD。
     *
     * @return true 表示分段识别
     */
    public boolean vadEnabled() {
        return vad != null;
    }

    /**
     * 释放原生资源（识别器 + VAD）。
     */
    @Override
    public void close() {
        try {
            recognizer.release();
        } catch (RuntimeException e) {
            log.debug("[sherpa] 释放识别器失败: {}", e.getMessage());
        }
        if (vad != null) {
            try {
                vad.close();
            } catch (RuntimeException e) {
                log.debug("[sherpa] 释放 VAD 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 识别器构建器。
     */
    public static final class Builder {

        /**
         * 模型标识或目录。
         */
        private String model;

        /**
         * 模型族短标识（paraformer / sensevoice / whisper / transducer / zipformer-ctc）。
         */
        private String modelType;

        /**
         * 语言提示。
         */
        private String language;

        /**
         * VAD 模型路径。
         */
        private String vad;

        /**
         * VAD 语音判定阈值。
         */
        private float vadThreshold = 0.5F;

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
         * 显式 modelType（一般留空）。
         */
        private String offlineModelType;

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
         * 设置模型族。
         *
         * @param modelType 短标识，如 {@code sensevoice}
         * @return 当前构建器
         */
        public Builder modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }

        /**
         * 设置语言提示。
         *
         * @param language 语言代码（如 zh / en / auto）
         * @return 当前构建器
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * 设置 VAD 模型路径（开启分段识别）。
         *
         * @param vad silero_vad.onnx 路径
         * @return 当前构建器
         */
        public Builder vad(String vad) {
            this.vad = vad;
            return this;
        }

        /**
         * 设置 VAD 语音判定阈值。
         *
         * @param vadThreshold 阈值（0~1）
         * @return 当前构建器
         */
        public Builder vadThreshold(float vadThreshold) {
            this.vadThreshold = vadThreshold;
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
         * 设置显式 modelType（传给 sherpa-onnx 的 modelType 字段，通常留空）。
         *
         * @param offlineModelType modelType
         * @return 当前构建器
         */
        public Builder offlineModelType(String offlineModelType) {
            this.offlineModelType = offlineModelType;
            return this;
        }

        /**
         * 构建识别器。
         *
         * @return 识别器实例
         * @throws SherpaException 模型或原生库不可用
         */
        public SherpaRecognizer build() {
            Path dir = SherpaModelResolver.resolveDirectory(model);
            assertNotStreamingModel(dir);
            SherpaModelType type = SherpaModelResolver.detect(dir, modelType);
            // 先确保原生库就绪，再组装配置：模型文件缺失与 native 缺失的报错要能区分开
            SherpaNativeLoader.ensureLoaded();
            OfflineRecognizerConfig config = SherpaModelResolver.buildRecognizerConfig(
                    dir, type, normalizeLanguage(language), provider, numThreads, offlineModelType);
            OfflineRecognizer recognizer;
            try {
                recognizer = new OfflineRecognizer(config);
            } catch (RuntimeException e) {
                throw new SherpaException("创建 sherpa-onnx 识别器失败（模型族 " + type.code()
                        + "，目录 " + dir + "）: " + e.getMessage(), e);
            }
            SherpaVadSegmenter segmenter = null;
            if (vad != null && !vad.isBlank()) {
                segmenter = SherpaVadSegmenter.builder()
                        .model(vad)
                        .sampleRate(sampleRate)
                        .threshold(vadThreshold)
                        .numThreads(numThreads)
                        .build();
            }
            return new SherpaRecognizer(recognizer, type, dir, sampleRate, segmenter, language);
        }

        /**
         * 归一化语言代码（sherpa-onnx 的 SenseVoice 约定小写，auto 需原样传递）。
         *
         * @param value 语言代码
         * @return 归一结果
         */
        private static String normalizeLanguage(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }

        /**
         * 拒绝把流式（online）模型目录交给离线识别器。
         *
         * <p>流式模型文件名形如 {@code encoder-epoch-99-avg-1.onnx}（无 int8 后缀也很常见），
         * 与离线 Transducer 目录结构高度相似，仅靠文件名无法区分。误用时 sherpa-onnx 会在
         * 原生层创建失败或输出乱码，因此这里按目录名/文件名中的 streaming 标记提前拦截，
         * 并给出改用 {@link SherpaStreamingRecognizer} 的提示。</p>
         *
         * @param dir 模型目录
         */
        private static void assertNotStreamingModel(Path dir) {
            String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
            if (dirName.contains("streaming")) {
                throw new SherpaException("目录 " + dir + " 看起来是流式（online）模型，"
                        + "请改用 SherpaStreamingRecognizer / SherpaAudioClient.streaming()；"
                        + "离线识别需要 sherpa-onnx 的 offline 模型导出");
            }
        }
    }
}
