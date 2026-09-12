package com.chua.deeplearning.support.recognition;

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
* 表格结构识别管线。
*
* <p>调度已注册的表格结构识别模型（如 pp-structure-v2、table-struct 等）。
* 各模型输出类型不同（如 table结构结果、对象 等），本管线统一返回
* 翻译器原始输出 {@link Object}，调用方按模型对应类型强转。</p>
*
* <pre>{@code
* TableStructurePipeline pipeline = TableStructurePipeline.builder()
*         .model("pp-structure-v2")
*         .build();
* Object result = pipeline.recognizeSingle(imageBytes);
* }</pre>ult = pipeline.recognizeSingle(imageBytes);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class TableStructurePipeline {

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
    * 表格结构模型名称。
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
    public TableStructurePipeline(String model) {
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
        * @return TableStructurePipeline
         */
        public TableStructurePipeline build() {
            return new TableStructurePipeline(model);
        }
    }

    /**
    * 编排识别管线（识别 → 收集）。
    *
    * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("table-structure-recognize")
                .task(NODE_RECOGNIZE, ctx -> {
                    TableStructureContext tc = current(ctx);
                    if (tc.currentImage() == null) {
                        return null;
                    }
                    tc.addResult(recognizeSingle(tc.currentImage()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
    * 识别单图表格结构。
    *
    * @param imageData 图像
    * @return 表格结构结果（各模型输出类型不同）
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
    * 识别单张图像表格结构（结果为列表，便于统一消费）。
    *
    * @param imageData 图像
    * @return 结果列表
     */
    public List<Object> recognize(byte[] imageData) {
        TableStructureContext tc = new TableStructureContext(imageData);
        PipelineContext<TableStructureContext> ctx = new PipelineContext<>(pipeline.getId(), tc);
        ctx.setAttribute("table", tc);
        ctx.setNextNodeId(NODE_RECOGNIZE);
        pipeline.resume(ctx);
        return tc.results();
    }

    /**
    * 从管线上下文提取表格结构上下文。
    *
    * @param ctx 管线上下文
    * @return 上下文
     */
    @SuppressWarnings("unchecked")
    private static TableStructureContext current(PipelineContext<?> ctx) {
        return (TableStructureContext) ctx.getAttribute("table");
    }

    /**
    * 枚举可用表格结构模型。
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
            if (RecognitionSupport.contains(name, "table-struct", "table-structure", "pp-structure", "table_struct")) {
                grouped.computeIfAbsent("table", k -> new LinkedHashSet<>()).add(entry.modelId());
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
