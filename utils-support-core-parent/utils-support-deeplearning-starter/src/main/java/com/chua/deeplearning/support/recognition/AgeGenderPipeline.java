package com.chua.deeplearning.support.recognition;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.ai.result.HumanPredictResult;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.draw.DrawerPipeline;
import com\.chua\.deeplearning\.support\.engine\.ModelRegistry;
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
 * 年龄 / 性别 / 种族识别管线。
 *
 * <p>基于 {@link Pipeline} 通用管线框架编排，参考 {@code OcrPipeline} 模式：
 * 支持直接对单图识别，也支持先检测人脸再逐框识别。通过 {@link IdentificationEngine}
 * 调度已注册的年龄 / 性别 / 种族模型（如 age-race-gender、age-gender-onnx、
 * google-net-age-recognition 等），输出统一的 {@link HumanPredictResult}。</p>
 *
 * <pre>{@code
 * AgeGenderPipeline pipeline = AgeGenderPipeline.builder()
 *         .model("age-race-gender")
 *         .build();
 * List&lt;HumanPredictResult&gt; results = pipeline.recognize(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AgeGenderPipeline {

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
     * 年龄 / 性别 / 种族模型名称。
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
    public AgeGenderPipeline(String model) {
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
         * @return AgeGenderPipeline
         */
        public AgeGenderPipeline build() {
            return new AgeGenderPipeline(model);
        }
    }

    /**
     * 编排识别管线（识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("age-gender-recognize")
                .task(NODE_RECOGNIZE, ctx -> {
                    AgeGenderContext gc = current(ctx);
                    if (gc.currentImage() == null) {
                        return null;
                    }
                    gc.addResult(recognizeSingle(gc.currentImage()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 识别单图年龄 / 性别 / 种族。
     *
     * @param imageData 图像（人脸图或整图）
     * @return 识别结果
     */
    public HumanPredictResult recognizeSingle(byte[] imageData) {
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
        return toHumanPredictResult(result);
    }

    /**
     * 识别单张图像的年龄 / 性别 / 种族（结果为列表，便于统一消费）。
     *
     * @param imageData 图像
     * @return 结果列表
     */
    public List<HumanPredictResult> recognize(byte[] imageData) {
        AgeGenderContext gc = new AgeGenderContext(imageData);
        PipelineContext<AgeGenderContext> ctx = new PipelineContext<>(pipeline.getId(), gc);
        ctx.setAttribute("ageGender", gc);
        ctx.setNextNodeId(NODE_RECOGNIZE);
        pipeline.resume(ctx);
        return gc.results();
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
    public List<HumanPredictResult> recognizeWithDetector(byte[] imageData, String detectorModel) {
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
        List<HumanPredictResult> results = new ArrayList<>();
        for (PredictRectangle rect : rects) {
            byte[] crop = RecognitionSupport.crop(imageData, rect);
            HumanPredictResult r = recognizeSingle(crop);
            if (r != null) {
                results.add(r);
            }
        }
        return results;
    }

    /**
     * 从管线上下文提取年龄性别上下文。
     *
     * @param ctx 管线上下文
     * @return 上下文
     */
    @SuppressWarnings("unchecked")
    private static AgeGenderContext current(PipelineContext<?> ctx) {
        return (AgeGenderContext) ctx.getAttribute("ageGender");
    }

    /**
     * 将翻译器输出转换为 HumanPredictResult。
     *
     * @param result 原始输出
     * @return 统一结果
     */
    private static HumanPredictResult toHumanPredictResult(Object result) {
        if (result instanceof HumanPredictResult human) {
            return human;
        }
        if (result instanceof com.chua.deeplearning.support.ai.result.PredictResult predict) {
            return HumanPredictResult.builder()
                    .value(predict.value())
                    .confidence((float) predict.confidence())
                    .build();
        }
        if (result != null) {
            return HumanPredictResult.builder()
                    .value(result.toString())
                    .confidence(0f)
                    .build();
        }
        return null;
    }

    /**
     * 枚举可用年龄 / 性别 / 种族模型。
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
            String group = groupOf(entry);
            if (group != null) {
                grouped.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(entry.modelId());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    /**
     * 按名称约定归类年龄 / 性别 / 种族模型。
     *
     * @param entry 注册表条目
     * @return 能力分组；无法识别时返回 null
     */
    private static String groupOf(ModelRegistry.Entry entry) {
        String name = entry.modelId() == null ? "" : entry.modelId().toLowerCase();
        if (RecognitionSupport.contains(name, "age-gender", "age_race_gender", "race-gender")) {
            return "ageGender";
        }
        if (RecognitionSupport.contains(name, "age-recognition", "age-recog", "yolo-face-age")) {
            return "age";
        }
        if (RecognitionSupport.contains(name, "gender-recognition", "gender-recog")) {
            return "gender";
        }
        return null;
    }

    /**
     * 创建标注管线，支持一键绘制检测结果。
     *
     * @return DrawerPipeline 实例
     */
    public DrawerPipeline withInitDrawer() {
        return new DrawerPipeline(0.5f);
    }
\}
