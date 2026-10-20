package com.chua.deeplearning.support.sherpa;

import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * sherpa-onnx 语音识别一站式门面。
 *
 * <p>定位与 {@code utils-support-deeplearning-onnx-starter} 的 {@code AsrPipeline} 一致：
 * 把「解码 → 分段 → 推理 → 拼接 → 后处理」收敛成一次调用，调用方不必关心
 * VAD 是否启用、长音频要不要切块、结果怎么拼。</p>
 *
 * <p>与直接使用 {@link SherpaRecognizer} 的区别：</p>
 * <ul>
 *   <li>统一按 16kHz 解码并自动重采样，接受 WAV / 任意 JDK 可解码格式；</li>
 *   <li>未配置 VAD 且音频超过上限时，自动按 {@code maxSegmentSeconds} 切块并<b>正确累加时间偏移</b>；</li>
 *   <li>返回前统一做文本后处理（压缩空白、英文句首大写）；</li>
 *   <li>空闲时按需重建底层识别器，避免长期持有的原生句柄泄漏。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>{@code
 * try (SherpaAsrPipeline asr = SherpaAsrPipeline.builder()
 *         .model("/models/sensevoice")
 *         .vadModel("/models/silero_vad.onnx")
 *         .language("zh")
 *         .corrector("macbert4csc")   // 可选：复用 OCR 侧的中文纠错模型修复同音字
 *         .build()) {
 *
 *     String text = asr.transcribe(Path.of("meeting.wav"));
 *
 *     SherpaRecognitionResult detail = asr.recognize(Path.of("meeting.wav"));
 *     detail.getSegments().forEach(s ->
 *             System.out.printf("[%.2f-%.2f] %s%n", s.getStart(), s.getEnd(), s.getText()));
 * }
 * }</pre>
 *
 * <p>线程安全：门面持有一个识别器实例，按「单实例单线程」设计；
 * 并发场景请为每个线程创建独立门面（或使用 SPI 客户端的对象池）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SherpaAsrPipeline implements AutoCloseable {

    /**
     * 默认最大段长（秒）：不配 VAD 时的切块上限，兼顾长音频内存与上下文完整性。
     */
    private static final float DEFAULT_MAX_SEGMENT_SECONDS = 28F;

    /**
     * ASR 场景下的纠错置信度默认阈值。
     *
     * <p>纠错模型（macbert4csc）自身默认 0.7，那是为 OCR 错别字调的；ASR 同音字的纠正
     * 置信度普遍更低，用 0.7 会「一句都不改」。实测 0.3 能正确修复「语因→语言」，
     * 同时对已正确文本不误改。</p>
     */
    private static final float DEFAULT_ASR_CSC_THRESHOLD = 0.3F;

    /**
     * 纠错翻译器读取的阈值系统属性（与 onnx 模块约定一致）。
     */
    private static final String CSC_THRESHOLD_PROPERTY = "deeplearning.csc.threshold";

    /**
     * 模型标识或目录。
     */
    private final String model;

    /**
     * 模型族短标识（可为 {@code null}，由目录结构自动判定）。
     */
    private final String modelType;

    /**
     * 语言提示。
     */
    private final String language;

    /**
     * VAD 模型路径（{@code null} 表示不分段）。
     */
    private final String vadModel;

    /**
     * VAD 语音判定阈值。
     */
    private final float vadThreshold;

    /**
     * 采样率（Hz）。
     */
    private final int sampleRate;

    /**
     * 线程数（{@code <= 0} 表示自动）。
     */
    private final int numThreads;

    /**
     * 推理 provider。
     */
    private final String provider;

    /**
     * 不配 VAD 时的最大段长（秒）。
     */
    private final float maxSegmentSeconds;

    /**
     * 是否启用文本后处理。
     */
    private final boolean postProcess;

    /**
     * 中文纠错模型标识（如 {@code macbert4csc}），{@code null} 表示不纠错。
     */
    private final String correctorModelId;

    /**
     * 纠错置信度阈值（{@code null} 表示用 ASR 场景默认值）。
     */
    private final Float correctorThreshold;

    /**
     * 懒加载的中文纠错翻译器。
     */
    private ITranslator<Object, Object> corrector;

    /**
     * 懒加载的底层识别器。
     */
    private SherpaRecognizer recognizer;

    /**
     * 构造门面。
     *
     * @param builder 构建器
     */
    private SherpaAsrPipeline(Builder builder) {
        this.model = builder.model;
        this.modelType = builder.modelType;
        this.language = builder.language;
        this.vadModel = builder.vadModel;
        this.vadThreshold = builder.vadThreshold;
        this.sampleRate = builder.sampleRate;
        this.numThreads = builder.numThreads;
        this.provider = builder.provider;
        this.maxSegmentSeconds = builder.maxSegmentSeconds;
        this.postProcess = builder.postProcess;
        this.correctorModelId = builder.correctorModelId;
        this.correctorThreshold = builder.correctorThreshold;
    }

    /**
     * 创建门面构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 转写音频文件，返回纯文本。
     *
     * @param audio 音频文件
     * @return 识别文本；无语音时返回空串
     */
    public String transcribe(Path audio) {
        return recognize(audio).getText();
    }

    /**
     * 转写音频字节，返回纯文本。
     *
     * @param audio 音频字节
     * @return 识别文本
     */
    public String transcribe(byte[] audio) {
        return recognize(audio).getText();
    }

    /**
     * 转写音频文件，返回完整结果（文本 + 分段 + 时间戳 + 语种/情感/事件）。
     *
     * @param audio 音频文件
     * @return 识别结果
     */
    public SherpaRecognitionResult recognize(Path audio) {
        if (audio == null || !Files.isRegularFile(audio)) {
            throw new SherpaException("音频文件不存在: " + audio);
        }
        return recognize(SherpaWavUtils.readMono(audio, sampleRate));
    }

    /**
     * 转写音频字节，返回完整结果。
     *
     * @param audio 音频字节
     * @return 识别结果
     */
    public SherpaRecognitionResult recognize(byte[] audio) {
        return recognize(SherpaWavUtils.readMono(audio, sampleRate));
    }

    /**
     * 转写已解码的浮点采样，返回完整结果。
     *
     * <p>分段策略：</p>
     * <ol>
     *   <li>配置了 VAD → 交给 {@link SherpaRecognizer} 按语音段切分并识别；</li>
     *   <li>未配置 VAD 且音频不超过 {@code maxSegmentSeconds} → 整段一次识别；</li>
     *   <li>未配置 VAD 且音频超长 → 按上限切块，逐块识别并累加绝对时间偏移。</li>
     * </ol>
     *
     * @param samples [-1, 1] 浮点采样
     * @return 识别结果
     */
    public SherpaRecognitionResult recognize(float[] samples) {
        if (samples == null || samples.length == 0) {
            throw new SherpaException("音频采样为空");
        }
        long started = System.currentTimeMillis();
        double duration = SherpaWavUtils.durationSeconds(samples.length, sampleRate);
        SherpaRecognizer engine = ensureRecognizer();

        SherpaRecognitionResult raw;
        int maxSegmentSamples = (int) (maxSegmentSeconds * sampleRate);
        if (engine.vadEnabled() || samples.length <= maxSegmentSamples) {
            raw = engine.recognize(samples);
        } else {
            raw = recognizeInChunks(engine, samples, maxSegmentSamples, duration);
        }
        return applyPostProcess(raw, System.currentTimeMillis() - started);
    }

    /**
     * 按固定上限切块识别（未配置 VAD 的超长音频场景）。
     *
     * @param engine            识别器
     * @param samples           完整采样
     * @param maxSegmentSamples 单块最大采样数
     * @param duration          音频总时长（秒）
     * @return 汇总结果
     */
    private SherpaRecognitionResult recognizeInChunks(SherpaRecognizer engine, float[] samples,
                                                     int maxSegmentSamples, double duration) {
        List<SherpaRecognitionResult.Segment> segments = new ArrayList<>();
        int index = 0;
        int cursor = 0;
        for (float[] chunk : SherpaWavUtils.split(samples, maxSegmentSamples)) {
            SherpaRecognitionResult part = engine.recognize(chunk);
            double chunkStart = SherpaWavUtils.durationSeconds(cursor, sampleRate);
            if (part.getSegments() != null && !part.getSegments().isEmpty()) {
                for (SherpaRecognitionResult.Segment segment : part.getSegments()) {
                    segments.add(SherpaRecognitionResult.Segment.builder()
                            .index(index++)
                            .start(chunkStart + segment.getStart())
                            .end(chunkStart + segment.getEnd())
                            .text(segment.getText())
                            .tokens(segment.getTokens())
                            .timestamps(segment.getTimestamps())
                            .language(segment.getLanguage())
                            .emotion(segment.getEmotion())
                            .events(segment.getEvents())
                            .build());
                }
            }
            cursor += chunk.length;
        }
        return buildResult(segments, duration, 0L);
    }

    /**
     * 把分段列表汇总为结果对象。
     *
     * @param segments 分段列表
     * @param duration 音频时长（秒）
     * @param elapsed  耗时（毫秒）
     * @return 汇总结果
     */
    private static SherpaRecognitionResult buildResult(List<SherpaRecognitionResult.Segment> segments,
                                                       double duration, long elapsed) {
        StringBuilder text = new StringBuilder();
        String language = null;
        String emotion = null;
        List<String> events = new ArrayList<>();
        for (SherpaRecognitionResult.Segment segment : segments) {
            if (segment.getText() != null && !segment.getText().isBlank()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(segment.getText().trim());
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
                .duration(duration)
                .elapsedMillis(elapsed)
                .build();
    }

    /**
     * 应用文本后处理（压缩空白与英文句首大写 + 可选中文纠错），并回填耗时。
     *
     * <p>纠错顺序与 OCR 管线一致：先纠错、再压缩空白。纠错失败<b>只降级不阻断</b>——
     * 保留原识别文本，避免因为一个可选后处理把整条转写结果丢掉。</p>
     *
     * @param result  原始结果
     * @param elapsed 总耗时（毫秒）
     * @return 处理后结果（原地修改并返回）
     */
    private SherpaRecognitionResult applyPostProcess(SherpaRecognitionResult result, long elapsed) {
        if (result == null) {
            return null;
        }
        String text = result.getText();
        if (text != null) {
            text = applyCorrector(text);
            if (postProcess) {
                text = postProcess(text);
            }
            result.setText(text);
        }
        result.setElapsedMillis(elapsed);
        return result;
    }

    /**
     * 应用中文纠错（如同音字修复）。
     *
     * <p>ASR 的同音字误判（实测「评审→庭审」「合成→和城市」「语音→语因」）与 OCR 的错别字
     * 属于同类问题，因此复用 OCR 管线同一个 {@code macbert4csc} 纠错模型：从
     * {@link AbstractIdentificationEngine} 按模型标识取 {@link ITranslator}，签名与
     * {@code OcrPipeline.Builder#corrector(String)} 完全一致。</p>
     *
     * <p><b>阈值注意</b>：该纠错模型默认阈值 0.7 是为 OCR 错别字调的。实测 ASR 同音字的
     * 纠正置信度普遍低于 0.7，用默认值会「一句都不改」（表现像纠错没生效）。因此本管线在
     * 未显式指定 {@link Builder#correctorThreshold(float)} 时，会把系统属性
     * {@code deeplearning.csc.threshold} 临时下调到 {@link #DEFAULT_ASR_CSC_THRESHOLD}；
     * 调用结束后恢复原值，避免影响同进程内 OCR 链路的既有行为。</p>
     *
     * <p>未配置、模型未注册、翻译器返回空或抛异常时，一律<b>原样返回</b>输入文本。</p>
     *
     * @param text 识别文本
     * @return 纠错后文本（失败时与原文本相同）
     */
    private String applyCorrector(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        ITranslator<Object, Object> translator = ensureCorrector();
        if (translator == null) {
            return text;
        }
        String previousThreshold = System.getProperty(CSC_THRESHOLD_PROPERTY);
        try {
            applyThresholdOverride();
            Object fixed = translator.translate(text);
            if (fixed == null) {
                return text;
            }
            String corrected = fixed.toString();
            if (corrected.isBlank()) {
                return text;
            }
            if (!corrected.equals(text)) {
                log.debug("[sherpa] 中文纠错: {} -> {}", text, corrected);
            }
            return corrected;
        } catch (Exception e) {
            log.debug("[sherpa] 中文纠错失败，保留原识别文本: {}", e.getMessage());
            return text;
        } finally {
            restoreThreshold(previousThreshold);
        }
    }

    /**
     * 应用阈值覆盖：仅当调用方显式配置了 {@code correctorThreshold} 时才临时写入系统属性。
     *
     * <p>纠错翻译器通过系统属性读取阈值（每次调用读取），因此这里用「临时改属性 + 恢复」
     * 的方式传递 ASR 场景的阈值，不侵入 onnx 模块的代码。</p>
     */
    private void applyThresholdOverride() {
        if (correctorThreshold != null) {
            System.setProperty(CSC_THRESHOLD_PROPERTY, String.valueOf(correctorThreshold));
        } else if (System.getProperty(CSC_THRESHOLD_PROPERTY) == null) {
            // 未显式配置且宿主未设置：用 ASR 场景更敏感的默认值
            System.setProperty(CSC_THRESHOLD_PROPERTY, String.valueOf(DEFAULT_ASR_CSC_THRESHOLD));
        }
    }

    /**
     * 恢复阈值系统属性。
     *
     * <p>只在当前调用确实写过属性时才恢复，避免误清宿主预先设置的值。</p>
     *
     * @param previous 进入前的属性值
     */
    private void restoreThreshold(String previous) {
        if (previous == null) {
            System.clearProperty(CSC_THRESHOLD_PROPERTY);
        } else {
            System.setProperty(CSC_THRESHOLD_PROPERTY, previous);
        }
    }

    /**
     * 确保纠错翻译器已就绪。
     *
     * @return 翻译器；未配置或未注册时返回 {@code null}
     */
    private ITranslator<Object, Object> ensureCorrector() {
        if (corrector != null) {
            return corrector;
        }
        if (correctorModelId == null || correctorModelId.isBlank()) {
            return null;
        }
        synchronized (this) {
            if (corrector == null) {
                try {
                    Object instance = AbstractIdentificationEngine.getInstance()
                            .get(correctorModelId, ITranslator.class);
                    if (instance instanceof ITranslator<?, ?> translator) {
                        @SuppressWarnings("unchecked")
                        ITranslator<Object, Object> typed = (ITranslator<Object, Object>) translator;
                        corrector = typed;
                    } else {
                        log.warn("[sherpa] 纠错模型未注册或不是 ITranslator，已跳过纠错: {}", correctorModelId);
                    }
                } catch (Exception e) {
                    log.warn("[sherpa] 加载纠错模型失败，已跳过纠错: {} -> {}", correctorModelId, e.getMessage());
                }
            }
        }
        return corrector;
    }

    /**
     * 文本后处理：压缩连续空白（含换行）为单个空格，并把英文句首大写。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 处理后的文本
     */
    static String postProcess(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\\s+", " ").trim();
        if (!trimmed.isEmpty() && Character.isLetter(trimmed.charAt(0))) {
            return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
        }
        return trimmed;
    }

    /**
     * 转换为标准异步任务响应（便于复用上层「识别任务」模型）。
     *
     * @param audio 音频文件
     * @param taskId 任务标识
     * @return 任务响应
     */
    public AudioResponse recognizeAsResponse(Path audio, String taskId) {
        try {
            SherpaRecognitionResult result = recognize(audio);
            List<AudioResponse.Segment> segments = new ArrayList<>();
            if (result.getSegments() != null) {
                for (SherpaRecognitionResult.Segment segment : result.getSegments()) {
                    segments.add(AudioResponse.Segment.builder()
                            .id(segment.getIndex())
                            .start(segment.getStart())
                            .end(segment.getEnd())
                            .text(segment.getText())
                            .build());
                }
            }
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(result.getText())
                    .segments(segments)
                    .detectedLanguage(result.getLanguage())
                    .emotion(result.getEmotion())
                    .events(result.getEvents())
                    .build();
        } catch (RuntimeException e) {
            log.error("[sherpa] 门面识别失败: {}", e.getMessage(), e);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 确保底层识别器已就绪。
     *
     * @return 识别器
     */
    private SherpaRecognizer ensureRecognizer() {
        if (recognizer == null) {
            synchronized (this) {
                if (recognizer == null) {
                    recognizer = SherpaRecognizer.builder()
                            .model(model)
                            .modelType(modelType)
                            .language(language)
                            .vad(vadModel)
                            .vadThreshold(vadThreshold)
                            .sampleRate(sampleRate)
                            .numThreads(numThreads)
                            .provider(provider)
                            .build();
                }
            }
        }
        return recognizer;
    }

    /**
     * 是否已启用 VAD 分段。
     *
     * @return true 表示启用
     */
    public boolean vadEnabled() {
        return vadModel != null && !vadModel.isBlank();
    }

    /**
     * 获取配置的中文纠错模型标识。
     *
     * @return 模型标识；未配置时返回 {@code null}
     */
    public String correctorModelId() {
        return correctorModelId;
    }

    /**
     * 探测纠错器是否真正可用（会触发一次懒加载）。
     *
     * <p>供运维自检：配置了 {@code corrector(...)} 但对应模型未注册时，
     * 管线会静默降级为「不纠错」，仅靠日志难以发现；用本方法可显式确认。</p>
     *
     * @return 已就绪的纠错翻译器；不可用时返回 {@code null}
     */
    public ITranslator<Object, Object> peekCorrector() {
        return ensureCorrector();
    }

    /**
     * 释放原生资源。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        }
    }

    /**
     * 门面构建器。
     */
    public static final class Builder {

        /**
         * 模型标识或目录（必填）。
         */
        private String model;

        /**
         * 模型族短标识。
         */
        private String modelType;

        /**
         * 语言提示。
         */
        private String language;

        /**
         * VAD 模型路径。
         */
        private String vadModel;

        /**
         * VAD 语音判定阈值。
         */
        private float vadThreshold = 0.5F;

        /**
         * 采样率。
         */
        private int sampleRate = SherpaWavUtils.DEFAULT_SAMPLE_RATE;

        /**
         * 线程数（{@code <= 0} 自动）。
         */
        private int numThreads;

        /**
         * 推理 provider。
         */
        private String provider;

        /**
         * 不配 VAD 时的最大段长（秒）。
         */
        private float maxSegmentSeconds = DEFAULT_MAX_SEGMENT_SECONDS;

        /**
         * 是否启用文本后处理。
         */
        private boolean postProcess = true;

        /**
         * 中文纠错模型标识。
         */
        private String correctorModelId;

        /**
         * 纠错置信度阈值。
         */
        private Float correctorThreshold;

        /**
         * 设置中文纠错模型（可选，非必填）。
         *
         * <p>与 OCR 管线同签名、同语义：按模型标识从 {@code IdentificationEngine} 取翻译器，
         * 未注册或纠错失败时<b>保留原文</b>，不阻断转写。推荐直接复用 OCR 侧已在用的
         * {@code macbert4csc}（中文拼写纠错）。</p>
         *
         * <pre>{@code
         * SherpaAsrPipeline.builder()
         *         .model("/models/sensevoice")
         *         .corrector("macbert4csc")   // 修复 ASR 同音字，如 评审→庭审、合成→和城市
         *         .build();
         * }</pre>
         *
         * @param modelId 模型标识，如 {@code macbert4csc}；留空关闭
         * @return 当前构建器
         */
        public Builder corrector(String modelId) {
            this.correctorModelId = modelId;
            return this;
        }

        /**
         * 设置纠错置信度阈值（可选）。
         *
         * <p>纠错模型默认阈值 0.7 是为 OCR 错别字调的；ASR 同音字的纠正置信度普遍更低，
         * 用 0.7 会「一句都不改」。本管线未显式设置时默认用 {@code 0.3}。
         * 调高更保守（少误改），调低更激进（多纠错）。</p>
         *
         * @param threshold 阈值（0~1）；大于 1 等价关闭纠错
         * @return 当前构建器
         */
        public Builder correctorThreshold(float threshold) {
            this.correctorThreshold = threshold;
            return this;
        }

        /**
         * 设置模型标识或目录。
         *
         * @param model 模型标识 / 目录
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
         * 设置模型族短标识（如 {@code sensevoice}）。
         *
         * @param modelType 短标识
         * @return 当前构建器
         */
        public Builder modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }

        /**
         * 设置语言提示。
         *
         * @param language 语言代码
         * @return 当前构建器
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * 设置 VAD 模型路径（开启分段识别，长音频强烈建议）。
         *
         * @param vadModel silero_vad.onnx 路径
         * @return 当前构建器
         */
        public Builder vadModel(String vadModel) {
            this.vadModel = vadModel;
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
         * 设置线程数。
         *
         * @param numThreads 线程数，{@code <= 0} 自动
         * @return 当前构建器
         */
        public Builder numThreads(int numThreads) {
            this.numThreads = numThreads;
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
         * 设置不配 VAD 时的最大段长。
         *
         * @param seconds 秒
         * @return 当前构建器
         */
        public Builder maxSegmentSeconds(float seconds) {
            this.maxSegmentSeconds = seconds;
            return this;
        }

        /**
         * 设置是否启用文本后处理。
         *
         * @param postProcess true 表示启用
         * @return 当前构建器
         */
        public Builder postProcess(boolean postProcess) {
            this.postProcess = postProcess;
            return this;
        }

        /**
         * 构建门面。
         *
         * @return 门面实例
         * @throws SherpaException 未指定模型或分段参数非法
         */
        public SherpaAsrPipeline build() {
            if (model == null || model.isBlank()) {
                throw new SherpaException("未指定 sherpa-onnx 模型（model(...) 为必填）");
            }
            if (maxSegmentSeconds <= 0F) {
                throw new SherpaException("maxSegmentSeconds 必须大于 0，当前: " + maxSegmentSeconds);
            }
            return new SherpaAsrPipeline(this);
        }
    }
}
