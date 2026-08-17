package com.chua.deeplearning.support.ocr;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.recognition.TextDirectionPipeline;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageCropUtils;
import com.chua.deeplearning.support.utils.OpenCvImageUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * OCR 组合业务：检测 → 排序 → 裁剪 → 矫正 → 修复 → 识别 → 收集。
 *
 * <p>基于 {@link Pipeline} 通用管线框架编排，取代手写循环；多人脸/多文本块场景由外层循环驱动，
 * 每个文本块一个独立 {@link PipelineContext}。模型清单通过 {@link #listModels()} 动态获取。</p>
 *
 * <pre>{@code
 * OcrPipeline ocr = OcrPipeline.builder()
 *         .detector("paddleocrv6-det")
 *         .recognizer("paddleocrv6-rec")
 *         .directionModel("pp-word-rotate")   // 可选：方向矫正（180° 翻转）
 *         .restorerModel("text-bsr")          // 可选：文字高清修复
 *         .build();
 * List&lt;OcrResult&gt; results = ocr.recognizeDetail(imageBytes);
 * String text = ocr.recognize(imageBytes);
 * Map&lt;String, List&lt;String&gt;&gt; models = ocr.listModels();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OcrPipeline {

    /**
     * 节点：裁剪
     */
    private static final String NODE_CROP = "crop";

    /**
     * 节点：识别
     */
    private static final String NODE_RECOGNIZE = "recognize";

    /**
     * 节点：矫正
     */
    private static final String NODE_ROTATE = "rotate";

    /**
     * 节点：修复
     */
    private static final String NODE_RESTORE = "restore";

    /**
     * 节点：收集
     */
    private static final String NODE_COLLECT = "collect";

    /**
     * 节点：终止
     */
    private static final String NODE_END = "end";

    /**
     * 文字检测器。
     */
    private final ImageDetector detector;

    /**
     * 文字识别器。
     */
    private final OcrRecognizer recognizer;

    /**
     * 方向矫正模型（可选，如 pp-word-rotate）。
     */
    private final String directionModel;

    /**
     * 文字修复模型（可选，如 text-bsr）。
     */
    private final String restorerModel;

    /**
     * 是否按阅读顺序排序（先 y 后 x）。
     */
    private final boolean sortReadingOrder;

    /**
     * 检测置信度阈值（过滤低置信度检测框）。
     */
    private final float minConfidence;

    /**
     * 识别管线实例。
     */
    private final Pipeline pipeline;

    /**
     * 构造。
     *
     * @param detector         检测
     * @param recognizer       识别
     * @param directionModel   方向矫正模型（可为 null）
     * @param restorerModel    文字修复模型（可为 null）
     * @param sortReadingOrder 阅读序
     * @param minConfidence    检测置信度阈值（过滤低置信度检测框）
     */
    public OcrPipeline(ImageDetector detector, OcrRecognizer recognizer,
                      String directionModel, String restorerModel, boolean sortReadingOrder,
                      float minConfidence) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.directionModel = directionModel;
        this.restorerModel = restorerModel;
        this.sortReadingOrder = sortReadingOrder;
        this.minConfidence = minConfidence;
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
         * 检测器。
         */
        private ImageDetector detector;

        /**
         * 识别器。
         */
        private OcrRecognizer recognizer;

        /**
         * 方向矫正模型（可选）。
         */
        private String directionModel;

        /**
         * 文字修复模型（可选）。
         */
        private String restorerModel;

        /**
         * 是否按阅读顺序排序。
         */
        private boolean sortReadingOrder = true;

        /**
         * 检测置信度阈值（默认 0.5）。
         */
        private float minConfidence = 0.5f;

        /**
         * 设置检测器。
         *
         * @param detector 检测
         * @return this
         */
        public Builder detector(ImageDetector detector) {
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
            this.detector = ImageDetector.create(modelId);
            return this;
        }

        /**
         * 设置识别器。
         *
         * @param recognizer 识别
         * @return this
         */
        public Builder recognizer(OcrRecognizer recognizer) {
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
            this.recognizer = OcrRecognizer.create(modelId);
            return this;
        }

        /**
         * 是否按阅读顺序排序结果。
         *
         * @param sortReadingOrder true 排序
         * @return this
         */
        public Builder sortReadingOrder(boolean sortReadingOrder) {
            this.sortReadingOrder = sortReadingOrder;
            return this;
        }

        /**
         * 设置方向矫正模型。
         *
         * @param model 模型 ID（如 pp-word-rotate）
         * @return this
         */
        public Builder directionModel(String model) {
            this.directionModel = model;
            return this;
        }

        /**
         * 设置文字修复模型。
         *
         * @param model 模型 ID（如 text-bsr）
         * @return this
         */
        public Builder restorerModel(String model) {
            this.restorerModel = model;
            return this;
        }

        /**
         * 设置检测置信度阈值（过滤低置信度检测框）。
         *
         * @param minConfidence 阈值 0~1
         * @return this
         */
        public Builder minConfidence(float minConfidence) {
            this.minConfidence = minConfidence;
            return this;
        }

        /**
         * 构建。
         *
         * @return OcrPipeline
         */
        public OcrPipeline build() {
            return new OcrPipeline(detector, recognizer, directionModel, restorerModel, sortReadingOrder, minConfidence);
        }
    }

    /**
     * 编排识别管线（裁剪 → 矫正 → 修复 → 识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("ocr-recognize")
                .task(NODE_CROP, ctx -> {
                    OcrContext oc = current(ctx);
                    if (oc.currentBox() == null) {
                        return null;
                    }
                    oc.currentCrop(ImageCropUtils.crop(oc.imageData(), oc.currentBox()));
                    return null;
                }).taskEnd()
                .task(NODE_ROTATE, ctx -> {
                    OcrContext oc = current(ctx);
                    if (oc.currentCrop() == null || directionModel == null) {
                        return null;
                    }
                    oc.currentCrop(rotateImage(oc.currentCrop()));
                    return null;
                }).taskEnd()
                .task(NODE_RESTORE, ctx -> {
                    OcrContext oc = current(ctx);
                    if (oc.currentCrop() == null || restorerModel == null) {
                        return null;
                    }
                    oc.currentCrop(restoreImage(oc.currentCrop()));
                    return null;
                }).taskEnd()
                .task(NODE_RECOGNIZE, ctx -> {
                    OcrContext oc = current(ctx);
                    if (oc.currentCrop() == null) {
                        return null;
                    }
                    String text = recognizer.recognize(oc.currentCrop());
                    oc.addResult(new OcrResult(
                            text == null ? "" : text,
                            oc.currentBox().confidence(),
                            oc.currentRectangle()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 识别整图文本（拼接）。
     *
     * @param imageData 图片
     * @return 文本
     */
    public String recognize(byte[] imageData) {
        return recognizeDetail(imageData).stream()
                .map(OcrResult::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 详细识别：检测框 + 文本。
     *
     * @param imageData 图片
     * @return 结果列表
     */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        List<DetectionInfo> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        List<DetectionInfo> ordered = sortReadingOrder
                ? boxes.stream()
                .sorted(Comparator
                        .comparingDouble(DetectionInfo::y)
                        .thenComparingDouble(DetectionInfo::x))
                .toList()
                : boxes;
        OcrContext oc = new OcrContext(imageData, ordered);
        while (oc.advance()) {
            runSingle(oc);
        }
        return oc.results();
    }

    /**
     * 对单个文本块执行识别管线。
     *
     * @param oc 上下文
     */
    private void runSingle(OcrContext oc) {
        PipelineContext<OcrContext> ctx = new PipelineContext<>(pipeline.getId(), oc);
        ctx.setAttribute("ocr", oc);
        ctx.setNextNodeId(NODE_CROP);
        pipeline.resume(ctx);
    }

    /**
     * 文字方向矫正：调用方向分类模型（如 pp-word-rotate），检测到 180° 时翻转图像。
     *
     * @param crop 文本块图像
     * @return 矫正后图像；无方向模型或处理失败时原样返回
     */
    private byte[] rotateImage(byte[] crop) {
        try {
            TextDirectionPipeline direction = TextDirectionPipeline.builder()
                    .model(directionModel)
                    .build();
            Object result = direction.recognizeSingle(crop);
            // DirectionInfo 在 onnx 模块，此处用反射读取 name 避免模块依赖
            String name = reflectName(result);
            if ("180".equalsIgnoreCase(name)) {
                return rotate180(crop);
            }
            return crop;
        } catch (Exception e) {
            log.warn("OCR 方向矫正失败（跳过）: {}", e.getMessage());
            return crop;
        }
    }

    /**
     * 反射读取方向结果名称（兼容 DirectionInfo / Map / String）。
     *
     * @param result 方向模型输出
     * @return 方向名称（如 0 / 180）；无法解析返回 null
     */
    private static String reflectName(Object result) {
        if (result == null) {
            return null;
        }
        try {
            if (result instanceof String s) {
                return s;
            }
            if (result instanceof Map<?, ?> map) {
                Object v = map.get("name");
                return v == null ? null : String.valueOf(v);
            }
            java.lang.reflect.Method m = result.getClass().getMethod("getName");
            Object v = m.invoke(result);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 文字修复：调用文字高清模型（如 text-bsr）提升模糊文字清晰度。
     *
     * @param crop 文本块图像
     * @return 修复后图像（PNG）；无修复模型或处理失败时原样返回
     */
    private byte[] restoreImage(byte[] crop) {
        try {
            ITranslator<Object, Object> translator =
                    (ITranslator<Object, Object>) AbstractIdentificationEngine.getInstance()
                            .get(restorerModel, ITranslator.class);
            if (translator == null) {
                return crop;
            }
            Object out = translator.translate(crop);
            if (out instanceof java.awt.image.BufferedImage image) {
                return OpenCvImageUtils.encode(OpenCvImageUtils.toMat(image));
            }
            return crop;
        } catch (Exception e) {
            log.warn("OCR 文字修复失败（跳过）: {}", e.getMessage());
            return crop;
        }
    }

    /**
     * 将图像旋转 180°。
     *
     * @param imageData 图像字节
     * @return 旋转后图像字节
     */
    private static byte[] rotate180(byte[] imageData) {
        try {
            return OpenCvImageUtils.rotate(imageData, 180);
        } catch (Exception e) {
            log.warn("OCR 图像翻转失败（跳过）: {}", e.getMessage());
            return imageData;
        }
    }

    /**
     * 从管线上下文提取 OCR 上下文。
     *
     * @param ctx 管线上下文
     * @return OCR 上下文
     */
    @SuppressWarnings("unchecked")
    private static OcrContext current(PipelineContext<?> ctx) {
        return (OcrContext) ctx.getAttribute("ocr");
    }

    /**
     * 枚举可用模型清单。
     *
     * <p>动态从 {@link ModelRegistry} 注册表获取全部模型，按能力接口（capabilityInterface）
     * 与模型名称约定归类（文字检测 / 文字识别 / 版面分析 / 表格结构 / 方向分类）。
     * 新增模型注册后自动出现在对应分组。</p>
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
     * 按能力接口与名称约定归类 OCR 模型。
     *
     * @param entry 注册表条目
     * @return 能力分组；无法识别时返回 null
     */
    private static String groupOf(ModelRegistry.Entry entry) {
        String name = entry.modelId() == null ? "" : entry.modelId().toLowerCase();
        Class<?> cap = entry.capabilityInterface();
        if (cap == com.chua.deeplearning.support.layout.LayoutDetector.class) {
            return "layout";
        }
        if (cap == com.chua.deeplearning.support.image.ImageDetector.class) {
            return nameContains(name, "ocr", "paddle") ? "detector" : null;
        }
        // 分类模型不属于 OCR 能力
        if (cap == com.chua.deeplearning.support.image.ImageClassifier.class) {
            return null;
        }
        if (cap == com.chua.deeplearning.support.feature.FeatureExtractor.class) {
            return null;
        }
        // 能力接口未声明时的名称约定回退
        return nameContains(name, "doc-layout", "ocr-layout", "pp-doc-layout", "layout-lmv3") ? "layout"
                : nameContains(name, "pp-structure", "table-struct", "table-structure") ? "table"
                : nameContains(name, "pp-word-rotate", "word-rotate") ? "direction"
                : nameContains(name, "svtr", "extractor", "ocr-rec", "paddle-ocr-rec", "ocr_rec", "rec_infer", "pp-ocr-rec") ? "recognizer"
                : nameContains(name, "ocr-det", "ocr_det", "ocr-detector", "ocr-detection") ? "detector"
                : nameContains(name, "paddleocrv6-det", "paddleocrv6-rec") ? (name.contains("det") ? "detector" : "recognizer")
                : null;
    }

    /**
     * 名称是否包含任一关键字。
     *
     * @param name 名称（小写）
     * @param keys 关键字
     * @return 命中任一返回 true
     */
    private static boolean nameContains(String name, String... keys) {
        for (String key : keys) {
            if (name.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测器。
     *
     * @return ImageDetector
     */
    public ImageDetector detector() {
        return detector;
    }

    /**
     * 识别器。
     *
     * @return OcrRecognizer
     */
    public OcrRecognizer recognizer() {
        return recognizer;
    }
}
