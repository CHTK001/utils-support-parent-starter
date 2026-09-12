package com.chua.deeplearning.support.recognition;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.draw.DrawerPipeline;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档解析管线。
 *
 * <p>调度已注册的文档解析 / 多模态理解模型（如 donut、smol-docling-combined 等），
 * 输出结构化文本。各模型输出类型不同，本管线统一返回翻译器原始输出 {@link Object}，
   * 常用模型（donut）输出含 {@code jsonText} 的 donut结果，可调用
 * {@link #recognizeText(byte[])} 提取字符串形式的结果。</p>
 *
 * <pre>{@code
 * DocumentParsePipeline pipeline = DocumentParsePipeline.builder()
 *         .model("donut")
 *         .build();
 * Object result = pipeline.recognizeSingle(imageBytes);
 * String text = pipeline.recognizeText(imageBytes);
 * }</pre>tring text = pipeline.recognizeText(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DocumentParsePipeline {

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
     * 文档解析模型名称。
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
    public DocumentParsePipeline(String model) {
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
         * @return DocumentParsePipeline
         */
        public DocumentParsePipeline build() {
            return new DocumentParsePipeline(model);
        }
    }

    /**
     * 编排识别管线（识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("document-parse")
                .task(NODE_RECOGNIZE, ctx -> {
                    DocumentParseContext dc = current(ctx);
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
     * 解析单张文档图像。
     *
     * @param imageData 图像
     * @return 解析结果（各模型输出类型不同）
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
     * 解析单张文档图像（结果为列表，便于统一消费）。
     *
     * @param imageData 图像
     * @return 结果列表
     */
    public List<Object> recognize(byte[] imageData) {
        DocumentParseContext dc = new DocumentParseContext(imageData);
        PipelineContext<DocumentParseContext> ctx = new PipelineContext<>(pipeline.getId(), dc);
        ctx.setAttribute("document", dc);
        ctx.setNextNodeId(NODE_RECOGNIZE);
        pipeline.resume(ctx);
        return dc.results();
    }

    /**
     * 解析文档并提取字符串文本。
     *
     * <p>支持以下输出形态：</p>
     * <ul>
     *   <li>String 直接返回</li>
     *   <li>DonutResult 返回其 jsonText</li>
     *   <li>其它对象返回 toString</li>
     * </ul>
     *
     * @param imageData 图像
     * @return 文本结果
     */
    public String recognizeText(byte[] imageData) {
        Object result = recognizeSingle(imageData);
        if (result == null) {
            return "";
        }
        if (result instanceof String s) {
            return s;
        }
 // donut 输出 donut结果（含 json文本），通过反射避免子模块依赖
        try {
            Object text = ReflectUtils.invoke(result, "getJsonText", Object.class);
            if (text != null) {
                return text.toString();
            }
        } catch (Exception ignored) {
 // 下降 through
        }
        return result.toString();
    }

    /**
     * 从管线上下文提取文档解析上下文。
     *
     * @param ctx 管线上下文
     * @return 上下文
     */
    @SuppressWarnings("unchecked")
    private static DocumentParseContext current(PipelineContext<?> ctx) {
        return (DocumentParseContext) ctx.getAttribute("document");
    }

    /**
     * 枚举可用文档解析模型。
     *
     * @return 能力分组 → 模型 标识 列表
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
            if (RecognitionSupport.contains(name, "donut", "smol-docling", "docling", "document", "docparse")) {
                grouped.computeIfAbsent("document", k -> new LinkedHashSet<>()).add(entry.modelId());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    /**
     * 创建标注管线，支持一键绘制检测结果。
     *
     * @return DrawerPipeline 实例
     */
    public DrawerPipeline withInitDrawer() {
        return new DrawerPipeline(0.5f);
    }
}
