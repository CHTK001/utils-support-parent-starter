package com.chua.deeplearning.support.recognition;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.draw.DrawerPipeline;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.plate.PlateResult;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
* 车牌号识别管线。
*
* <p>基于 {@link Pipeline} 通用管线框架编排：支持直接对车牌图识别，
* 也支持先检测车牌再逐框识别（detect → crop → recognize）。调度已注册的
* 车牌号识别模型（如 crnn-铭牌-rec、yolov5-铭牌-recognize 等），
* 输出 {@link PlateResult}（车牌号 + 颜色）。</p>
*
* <pre>{@code
* PlateNumberPipeline pipeline = PlateNumberPipeline.builder()
*         .model("crnn-plate-rec")
*         .detector("yolo5-plate-detect")
*         .build();
* List&lt;PlateResult&gt; results = pipeline.recognize(imageBytes);
* }</pre>sult&gt; results = pipeline.recognize(imageBytes);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class PlateNumberPipeline {

    /**
    * 节点：裁剪
     */
    private static final String NODE_CROP = "crop";

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
    * 车牌号识别模型名称。
     */
    private final String model;

    /**
    * 车牌检测模型名称，可为 空（直接识别车牌图）。
     */
    private final String detectorModel;

    /**
    * 识别管线实例。
     */
    private final Pipeline pipeline;

    /**
    * 车牌号识别管线回调。
     */
    private PlateNumberPipelineCallback callback;

    /**
    * 构造识别管线。
    *
    * @param model         识别模型
    * @param detectorModel 检测模型，可为 空
     */
    public PlateNumberPipeline(String model, String detectorModel) {
        this.engine = AbstractIdentificationEngine.getInstance();
        this.model = Objects.requireNonNull(model, "model");
        this.detectorModel = detectorModel;
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
        * 识别模型名称。
         */
        private String model;

        /**
        * 检测模型名称。
         */
        private String detectorModel;

        /**
        * 设置识别模型。
        *
        * @param model 识别模型
        * @return this
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
        * 设置检测模型。
        *
        * @param detectorModel 检测模型
        * @return this
         */
        public Builder detector(String detectorModel) {
            this.detectorModel = detectorModel;
            return this;
        }

        /**
        * 构建。
        *
        * @return PlateNumberPipeline
         */
        public PlateNumberPipeline build() {
            return new PlateNumberPipeline(model, detectorModel);
        }
    }

    /**
    * 编排识别管线（裁剪 → 识别 → 收集）。
    *
    * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("plate-number-recognize")
                .task(NODE_CROP, ctx -> {
                    PlateNumberContext pc = current(ctx);
                    if (pc.currentBox() == null) {
                        return null;
                    }
                    pc.currentCrop(RecognitionSupport.crop(pc.imageData(), pc.currentBox()));
                    return null;
                }).taskEnd()
                .task(NODE_RECOGNIZE, ctx -> {
                    PlateNumberContext pc = current(ctx);
                    if (pc.currentCrop() == null) {
                        return null;
                    }
                    PlateResult r = recognizeSingle(pc.currentCrop());
                    if (r != null) {
                        pc.addResult(r);
                        if (callback != null) {
                            callback.onRecognize(pc.currentBox(), r, pc.results().size(), -1);
                        }
                    }
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
    * 识别单张车牌图像。
    *
    * @param imageData 车牌图
    * @return 车牌识别结果
     */
    public PlateResult recognizeSingle(byte[] imageData) {
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
        if (result instanceof PlateResult plate) {
            return plate;
        }
        return null;
    }

    /**
    * 识别图像中的车牌。
    *
    * <p>配置了 detector 时先检测再逐框识别；否则整图直接识别。</p>
    *
    * @param imageData 场景图
    * @return 车牌识别结果列表
     */
    public List<PlateResult> recognize(byte[] imageData) {
        if (detectorModel == null || detectorModel.isBlank()) {
            PlateResult r = recognizeSingle(imageData);
            return r == null ? List.of() : List.of(r);
        }
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> detector =
                (ITranslator<Object, Object>) engine.get(detectorModel, ITranslator.class);
        if (detector == null) {
            PlateResult r = recognizeSingle(imageData);
            return r == null ? List.of() : List.of(r);
        }
        Object boxes = detector.translate(imageData);
        List<PredictRectangle> rects = RecognitionSupport.toRectangles(boxes);
        if (callback != null) {
            callback.onDetect(imageData, rects);
        }
        if (rects.isEmpty()) {
            return List.of();
        }
        PlateNumberContext pc = new PlateNumberContext(imageData, rects);
        while (pc.advance()) {
            PipelineContext<PlateNumberContext> ctx = new PipelineContext<>(pipeline.getId(), pc);
            ctx.setAttribute("plateNumber", pc);
            ctx.setNextNodeId(NODE_CROP);
            pipeline.resume(ctx);
        }
        return pc.results();
    }

    /**
    * 设置车牌号识别管线回调。
    *
    * @param callback 回调实例
     */
    public void setCallback(PlateNumberPipelineCallback callback) {
        this.callback = callback;
    }

    /**
    * 获取车牌号识别管线回调。
    *
    * @return 回调实例，可能为 空
     */
    public PlateNumberPipelineCallback callback() {
        return this.callback;
    }

    /**
    * 从管线上下文提取车牌识别上下文。
    *
    * @param ctx 管线上下文
    * @return 上下文
     */
    @SuppressWarnings("unchecked")
    private static PlateNumberContext current(PipelineContext<?> ctx) {
        return (PlateNumberContext) ctx.getAttribute("plateNumber");
    }

    /**
    * 枚举可用车牌识别 / 检测模型。
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
            if (RecognitionSupport.contains(name, "plate")) {
                String group = RecognitionSupport.contains(name, "rec", "recognize") ? "recognizer" : "detector";
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
    * 创建标注管线，支持一键绘制检测结果。
    *
    * @return DrawerPipeline 实例
     */
    public DrawerPipeline withInitDrawer() {
        return new DrawerPipeline(0.5f);
    }
}
