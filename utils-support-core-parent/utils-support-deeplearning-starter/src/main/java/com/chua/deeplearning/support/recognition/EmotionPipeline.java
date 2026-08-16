package com.chua.deeplearning.support.recognition;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.ai.result.PredictResult;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 情绪识别管线。
 *
 * <p>基于 {@link Pipeline} 通用管线框架编排，参考 {@code OcrEngine} 模式：
 * 调度已注册的情绪识别模型（如 emotion-ferplus、yolo-face-emotion 等），
 * 输出统一的 {@link PredictResult}（value 为情绪标签，confidence 为置信度）。</p>
 *
 * <pre>{@code
 * EmotionPipeline pipeline = EmotionPipeline.builder()
 *         .model("emotion-ferplus")
 *         .build();
 * List&lt;PredictResult&gt; results = pipeline.recognize(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class EmotionPipeline {

    /**
     * 节点：识别
     */
    private static final String NODE_RECOGNIZE = "recognize";

    /**
     * 节点：收集
     */
    private static final String NODE_COLLECT = "collect";

    /**
     * 节点：终止
     */
    private static final String NODE_END = "end";

    /**
     * 识别引擎。
     */
    private final IdentificationEngine engine;

    /**
     * 情绪模型名称。
     */
    private final String model;

    /**
     * 识别管线实例。
     */
    private final Pipeline pipeline;

    /**
     * 构造识别管线。
     *
     * @param model 模型名称
     */
    public EmotionPipeline(String model) {
        this.engine = AbstractIdentificationEngine.getInstance();
        this.model = Objects.requireNonNull(model, "model");
        this.pipeline = buildPipeline();
    }

    /**
     * 构建器。
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 链式构建器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static final class Builder {

        /**
         * 模型名称。
         */
        private String model;

        /**
         * 设置模型名称。
         *
         * @param model 模型
         * @return this
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 构建。
         *
         * @return EmotionPipeline
         */
        public EmotionPipeline build() {
            return new EmotionPipeline(model);
        }
    }

    /**
     * 编排识别管线（识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("emotion-recognize")
                .task(NODE_RECOGNIZE, ctx -> {
                    EmotionContext ec = current(ctx);
                    if (ec.currentImage() == null) {
                        return null;
                    }
                    ec.addResult(recognizeSingle(ec.currentImage()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 识别单图情绪。
     *
     * @param imageData 图像（人脸图或整图）
     * @return 识别结果
     */
    public PredictResult recognizeSingle(byte[] imageData) {
        if (imageData == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) engine.get(model, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + model);
        }
        Object result = translator.translate(imageData);
        return toPredictResult(result);
    }

    /**
     * 识别单张图像情绪（结果为列表，便于统一消费）。
     *
     * @param imageData 图像
     * @return 结果列表
     */
    public List<PredictResult> recognize(byte[] imageData) {
        EmotionContext ec = new EmotionContext(imageData);
        PipelineContext<EmotionContext> ctx = new PipelineContext<>(pipeline.getId(), ec);
        ctx.setAttribute("emotion", ec);
        ctx.setNextNodeId(NODE_RECOGNIZE);
        pipeline.resume(ctx);
        return ec.results();
    }

    /**
     * 检测 + 逐框识别：先检测人脸，再对每张人脸识别。
     *
     * <p>未配置 detector 时退化为整图识别。</p>
     *
     * @param imageData     场景图
     * @param detectorModel 人脸检测模型 ID，可为 null（整图识别）
     * @return 结果列表
     */
    public List<PredictResult> recognizeWithDetector(byte[] imageData, String detectorModel) {
        if (detectorModel == null || detectorModel.isBlank()) {
            return recognize(imageData);
        }
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> detector =
                (ITranslator<Object, Object>) engine.get(detectorModel, ITranslator.class);
        if (detector == null) {
            return recognize(imageData);
        }
        Object boxes = detector.translate(imageData);
        List<PredictRectangle> rects = RecognitionSupport.toRectangles(boxes);
        if (rects.isEmpty()) {
            return List.of();
        }
        List<PredictResult> results = new ArrayList<>();
        for (PredictRectangle rect : rects) {
            byte[] crop = RecognitionSupport.crop(imageData, rect);
            PredictResult r = recognizeSingle(crop);
            if (r != null) {
                results.add(r);
            }
        }
        return results;
    }

    /**
     * 从管线上下文提取情绪上下文。
     *
     * @param ctx 管线上下文
     * @return 上下文
     */
    @SuppressWarnings("unchecked")
    private static EmotionContext current(PipelineContext<?> ctx) {
        return (EmotionContext) ctx.getAttribute("emotion");
    }

    /**
     * 将翻译器输出转换为 PredictResult。
     *
     * @param result 原始输出
     * @return 统一结果
     */
    private static PredictResult toPredictResult(Object result) {
        if (result instanceof PredictResult predict) {
            return predict;
        }
        if (result instanceof com.chua.deeplearning.support.ai.result.HumanPredictResult human) {
            return PredictResult.builder()
                    .value(human.getValue())
                    .confidence(human.getConfidence())
                    .build();
        }
        if (result != null) {
            return PredictResult.builder()
                    .value(result.toString())
                    .confidence(0)
                    .build();
        }
        return null;
    }

    /**
     * 枚举可用情绪识别模型。
     *
     * @return 能力分组 → 模型 ID 列表
     */
    public Map<String, List<String>> listModels() {
        try {
            ModelRegistry.discoverAll();
        } catch (Exception e) {
            log.warn("模型注册表发现失败: {}", e.getMessage());
        }
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (ModelRegistry.Entry entry : ModelRegistry.getAll()) {
            String name = entry.modelId() == null ? "" : entry.modelId().toLowerCase();
            if (RecognitionSupport.contains(name, "emotion", "fer-plus", "ferplus", "fer2013")) {
                grouped.computeIfAbsent("emotion", k -> new LinkedHashSet<>()).add(entry.modelId());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }
}
