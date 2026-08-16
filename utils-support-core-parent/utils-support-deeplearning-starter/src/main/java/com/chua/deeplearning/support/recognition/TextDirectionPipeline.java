package com.chua.deeplearning.support.recognition;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文字方向检测管线。
 *
 * <p>调度已注册的文字方向 / 分类模型（如 pp-word-rotate 等）。
 * 各模型输出类型不同，本管线统一返回翻译器原始输出 {@link Object}。</p>
 *
 * <pre>{@code
 * TextDirectionPipeline pipeline = TextDirectionPipeline.builder()
 *         .model("pp-word-rotate")
 *         .build();
 * Object result = pipeline.recognizeSingle(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TextDirectionPipeline {

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
     * 方向模型名称。
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
    public TextDirectionPipeline(String model) {
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
         * @return TextDirectionPipeline
         */
        public TextDirectionPipeline build() {
            return new TextDirectionPipeline(model);
        }
    }

    /**
     * 编排识别管线（识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("text-direction-recognize")
                .task(NODE_RECOGNIZE, ctx -> {
                    TextDirectionContext dc = current(ctx);
                    if (dc.currentImage() == null) {
                        return null;
                    }
                    dc.addResult(recognizeSingle(dc.currentImage()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 检测单图文字方向。
     *
     * @param imageData 图像
     * @return 方向结果（各模型输出类型不同）
     */
    public Object recognizeSingle(byte[] imageData) {
        if (imageData == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) engine.get(model, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + model);
        }
        return translator.translate(imageData);
    }

    /**
     * 检测单张图像文字方向（结果为列表，便于统一消费）。
     *
     * @param imageData 图像
     * @return 结果列表
     */
    public List<Object> recognize(byte[] imageData) {
        TextDirectionContext dc = new TextDirectionContext(imageData);
        PipelineContext<TextDirectionContext> ctx = new PipelineContext<>(pipeline.getId(), dc);
        ctx.setAttribute("direction", dc);
        ctx.setNextNodeId(NODE_RECOGNIZE);
        pipeline.resume(ctx);
        return dc.results();
    }

    /**
     * 从管线上下文提取方向上下文。
     *
     * @param ctx 管线上下文
     * @return 上下文
     */
    @SuppressWarnings("unchecked")
    private static TextDirectionContext current(PipelineContext<?> ctx) {
        return (TextDirectionContext) ctx.getAttribute("direction");
    }

    /**
     * 枚举可用文字方向模型。
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
            // 排除通用分类模型（anime-real-cls / yolo-cls 等含 "cls" 但不属于文字方向）
            boolean direction = RecognitionSupport.contains(name, "word-rotate", "pp-word-rotate", "word_rotate")
                    || (RecognitionSupport.contains(name, "direction") && !RecognitionSupport.contains(name, "cls"))
                    || (RecognitionSupport.contains(name, "ocrv6-det") || RecognitionSupport.contains(name, "paddleocrv6-det"));
            if (direction) {
                grouped.computeIfAbsent("direction", k -> new LinkedHashSet<>()).add(entry.modelId());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }
}
