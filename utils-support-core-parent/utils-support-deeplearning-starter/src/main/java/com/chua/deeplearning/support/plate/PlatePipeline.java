package com.chua.deeplearning.support.plate;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.draw.DrawerPipeline;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageCropUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 车牌识别流水线，组合车牌检测和车牌识别两个步骤完成端到端识别。
 *
 * <p>基于 {@link Pipeline} 通用管线框架编排（裁剪 → 识别 → 收集），取代手写循环；
 * 多车牌场景由外层循环驱动，每个车牌一个独立 {@link PipelineContext}。
 * 模型清单通过 {@link #listModels()} 动态获取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PlatePipeline {

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
     * 车牌检测器。
     */
    private final PlateDetector detector;

    /**
     * 车牌识别器。
     */
    private final LicensePlateRecognizer recognizer;

    /**
     * 识别管线实例。
     */
    private final Pipeline pipeline;

    /**
     * 构造车牌流水线。
     *
     * @param detector   车牌检测器
     * @param recognizer 车牌识别器
     */
    public PlatePipeline(PlateDetector detector,
                         LicensePlateRecognizer recognizer) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.recognizer = recognizer;
        this.pipeline = buildPipeline();
    }

    /**
     * 链式构建器。
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 车牌流水线构建器。
     *
     * @since 4.0.0.42
     */
    public static final class Builder {

        /**
         * 车牌检测器。
         */
        private PlateDetector detector;

        /**
         * 车牌识别器。
         */
        private LicensePlateRecognizer recognizer;

        /**
         * 设置车牌检测器。
         *
         * @param detector 检测器
         * @return this
         */
        public Builder detector(PlateDetector detector) {
            this.detector = detector;
            return this;
        }

        /**
         * 按模型 ID 创建检测器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder detector(String modelId) {
            this.detector = PlateDetector.create(modelId);
            return this;
        }

        /**
         * 设置车牌识别器。
         *
         * @param recognizer 识别器
         * @return this
         */
        public Builder recognizer(LicensePlateRecognizer recognizer) {
            this.recognizer = recognizer;
            return this;
        }

        /**
         * 按模型 ID 创建识别器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder recognizer(String modelId) {
            this.recognizer = LicensePlateRecognizer.create(modelId);
            return this;
        }

        /**
         * 构建。
         *
         * @return PlatePipeline
         */
        public PlatePipeline build() {
            return new PlatePipeline(detector, recognizer);
        }
    }

    /**
     * 编排识别管线（裁剪 → 识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("plate-recognize")
                .task(NODE_CROP, ctx -> {
                    PlateContext pc = current(ctx);
                    if (pc.currentBox() == null) {
                        return null;
                    }
                    pc.currentPlate(ImageCropUtils.crop(pc.imageData(), pc.currentBox()));
                    return null;
                }).taskEnd()
                .task(NODE_RECOGNIZE, ctx -> {
                    PlateContext pc = current(ctx);
                    if (pc.currentPlate() == null) {
                        return null;
                    }
                    String plateText = "";
                    String plateColor = "";
                    if (recognizer != null) {
                        PlateResult result = recognizer.recognizePlate(pc.currentPlate());
                        if (result != null) {
                            plateText = result.plateNo();
                            plateColor = result.plateColor();
                        }
                    }
                    pc.addHit(new PlateDetectHit(
                            pc.currentBox(), pc.currentPlate(), plateText, plateColor));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 检测并识别图像中所有车牌。
     *
     * @param imageData 图像数据
     * @return 车牌检测命中列表
     */
    public List<PlateDetectHit> detect(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        PlateContext pc = new PlateContext(imageData, boxes);
        while (pc.advance()) {
            runSingle(pc);
        }
        return pc.hits();
    }

    /**
     * 仅检测车牌边界框（不识别）。
     *
     * @param imageData 图像数据
     * @return 检测框列表
     */
    public List<PredictRectangle> detectBoxes(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        return boxes == null ? List.of() : boxes;
    }

    /**
     * 对单个车牌执行识别管线。
     *
     * @param pc 上下文
     */
    private void runSingle(PlateContext pc) {
        PipelineContext<PlateContext> ctx = new PipelineContext<>(pipeline.getId(), pc);
        ctx.setAttribute("plate", pc);
        ctx.setNextNodeId(NODE_CROP);
        pipeline.resume(ctx);
    }

    /**
     * 从管线上下文提取车牌上下文。
     *
     * @param ctx 管线上下文
     * @return 车牌上下文
     */
    @SuppressWarnings("unchecked")
    private static PlateContext current(PipelineContext<?> ctx) {
        return (PlateContext) ctx.getAttribute("plate");
    }

    /**
     * 枚举可用模型清单。
     *
     * <p>动态从 {@link ModelRegistry} 注册表获取全部模型，按模型名称约定归类
     * （车牌检测 / 车牌识别）。新增模型注册后自动出现在对应分组。</p>
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
     * 按模型名称约定归类车牌模型。
     *
     * @param entry 注册表条目
     * @return 能力分组；无法识别时返回 null
     */
    private static String groupOf(ModelRegistry.Entry entry) {
        String name = entry.modelId() == null ? "" : entry.modelId().toLowerCase();
        if (name.contains("plate") && name.contains("det")) {
            return "detector";
        }
        if (name.contains("plate") && name.contains("rec")) {
            return "recognizer";
        }
        if (name.contains("plate")) {
            return name.contains("rec") ? "recognizer" : "detector";
        }
        return null;
    }

    /**
     * 获取车牌检测器。
     *
     * @return PlateDetector
     */
    public PlateDetector detector() {
        return detector;
    }

    /**
     * 获取车牌识别器。
     *
     * @return LicensePlateRecognizer
     */
    public LicensePlateRecognizer recognizer() {
        return recognizer;
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
