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
 * 版面分析管线。
 *
 * <p>调度已注册的版面分析 / 文档布局模型（如 layout-lmv3、doc-layout-yolo、
 * pp-doc-layout 等），统一返回翻译器原始输出 {@link Object}。
 * 各模型输出类型不同：layout-lmv3 输出 LayoutLMv3Result（文档区域列表），
 * YOLO 版面模型输出检测框列表。</p>
 *
 * <pre>{@code
 * LayoutPipeline pipeline = LayoutPipeline.builder()
 *         .model("layout-lmv3")
 *         .build();
 * Object result = pipeline.recognizeSingle(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LayoutPipeline {

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
     * 版面模型名称。
     */
    private final String model;

    /**
     * 图像预处理管线，可为 null（不预处理）。
     */
    private final com.chua.common.support.image.ImagePipeline imagePipeline;

    /**
     * 识别管线实例。
     */
    private final Pipeline pipeline;

    /**
     * 构造识别管线。
     *
     * @param model         模型名称
     * @param imagePipeline 图像预处理管线，可为 null
     */
    public LayoutPipeline(String model, com.chua.common.support.image.ImagePipeline imagePipeline) {
        this.engine = AbstractIdentificationEngine.getInstance();
        this.model = Objects.requireNonNull(model, "model");
        this.imagePipeline = imagePipeline;
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
         * @return LayoutPipeline
         */
        public LayoutPipeline build() {
            return new LayoutPipeline(model, null);
        }
    }

    /**
     * 编排识别管线（识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("layout-analyze")
                .task(NODE_RECOGNIZE, ctx -> {
                    LayoutContext lc = current(ctx);
                    if (lc.currentImage() == null) {
                        return null;
                    }
                    lc.addResult(recognizeSingle(lc.currentImage()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 分析单张文档图像版面。
     *
     * @param imageData 图像
     * @return 版面结果（各模型输出类型不同）
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
     * 分析单张文档图像版面（结果为列表，便于统一消费）。
     *
     * @param imageData 图像
     * @return 结果列表
     */
    public List<Object> recognize(byte[] imageData) {
        LayoutContext lc = new LayoutContext(imageData);
        PipelineContext<LayoutContext> ctx = new PipelineContext<>(pipeline.getId(), lc);
        ctx.setAttribute("layout", lc);
        ctx.setNextNodeId(NODE_RECOGNIZE);
        pipeline.resume(ctx);
        return lc.results();
    }

    /**
     * 从管线上下文提取版面上下文。
     *
     * @param ctx 管线上下文
     * @return 上下文
     */
    @SuppressWarnings("unchecked")
    private static LayoutContext current(PipelineContext<?> ctx) {
        return (LayoutContext) ctx.getAttribute("layout");
    }

    /**
     * 枚举可用版面分析模型。
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
            if (RecognitionSupport.contains(name, "layout", "doc-layout", "pp-doc-layout", "ocr-layout", "doclayout")) {
                grouped.computeIfAbsent("layout", k -> new LinkedHashSet<>()).add(entry.modelId());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }
}
