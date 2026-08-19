package com.chua.deeplearning.support.ocr;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.ocr.DrawerPipeline;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageCropUtils;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
 * OCR 统一能力门面：检测 → 裁剪 → 方向矫正 → 修复 → 识别。
 *
 * <p>基于 {@link Pipeline} 通用管线框架，聚合文字识别全量能力。
 * 方向矫正、文字修复、检测、识别均由模型 ID 动态加载，支持引擎无关的 SPI 扩展。</p>
 *
 * <pre>{@code
 * OcrPipeline ocr = OcrPipeline.builder()
 *         .detector("paddleocrv6-det")
 *         .recognizer("paddleocrv6-rec")
 *         .direction("pp-word-rotate")
 *         .build();
 *
 * String text = ocr.recognize(imageBytes);
 * List<OcrResult> results = ocr.recognizeDetail(imageBytes);
 * byte[] corrected = ocr.correct(imageBytes);
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
     * 节点：方向矫正
     */
    private static final String NODE_CORRECT = "correct";

    /**
     * 节点：文字高清修复
     */
    private static final String NODE_ENHANCE = "enhance";

    /**
     * 节点：文字识别
     */
    private static final String NODE_RECOGNIZE = "recognize";

    /**
     * 节点：收集结果
     */
    private static final String NODE_COLLECT = "collect";

    /**
     * 节点：终止
     */
    private static final String NODE_END = "end";

    /**
     * 文字检测器
     */
    private final ImageDetector detector;

    /**
     * 文字识别器
     */
    private final OcrRecognizer recognizer;

    /**
     * 方向矫正翻译器，可为 null
     */
    private final ITranslator<Object, Object> direction;

    /**
     * 文字高清化翻译器，可为 null
     */
    private final ITranslator<Object, Object> enhancer;

    /**
     * 是否在识别管线内启用文字高清化
     */
    private final boolean enhanceInPipeline;

    /**
     * 是否按阅读顺序排序
     */
    private final boolean sortReadingOrder;

    /**
     * 最低识别置信度（0 不过滤）
     */
    private final float minConfidence;

    /**
     * 裁剪框四周扩展像素数
     */
    private final int cropPadding;

    /**
     * 裁剪块最小高度（低于此值自动放大 2 倍），单位像素
     */
    private final int cropMinHeight;

    /**
     * 大角度矫正阈值（度）：检测框角度绝对值超过该值时，
     * 按旋转矩形扶正裁剪（cropRotated），否则轴对齐裁剪直接 rec。
     * 小于 0 表示禁用大角度矫正。
     */
    private final float cropRotateThreshold;

    /**
     * 质量门控：是否在识别前评估图像清晰度，质量差（模糊）时
     * 跳过方向矫正并启用文字高清修复。
     */
    private final boolean qualityGate;

    /**
     * 清晰度阈值：模糊度评分低于该值判定为模糊（经验值 100）。
     */
    private final float blurThreshold;

    /**
     * 检测输出是否应用 sigmoid（部分模型输出 logits 需激活，默认 false）
     */
    private final boolean sigmoidDetect;

    /**
     * 识别输出是否应用 sigmoid（默认 false）
     */
    private final boolean sigmoidRecognize;

    /**
     * 识别管线实例
     */
    private final Pipeline pipeline;

    /**
     * 构造 OCR 管线。
     *
     * @param detector          文字检测器
     * @param recognizer        文字识别器
     * @param direction         方向矫正翻译器，可为 null
     * @param enhancer          文字高清化翻译器，可为 null
     * @param enhanceInPipeline 是否在识别管线内启用文字高清化
     * @param sortReadingOrder  是否按阅读顺序排序
     * @param minConfidence     最低识别置信度
     * @param cropPadding       裁剪框四周扩展像素数
     * @param cropMinHeight     裁剪块最小高度
     * @param cropRotateThreshold 大角度矫正阈值（度），负值禁用
     * @param qualityGate         是否启用质量门控（模糊图优先进修复）
     * @param blurThreshold       清晰度阈值（低于视为模糊）
     */
    public OcrPipeline(ImageDetector detector, OcrRecognizer recognizer,
                       ITranslator<Object, Object> direction, ITranslator<Object, Object> enhancer,
                       boolean enhanceInPipeline, boolean sortReadingOrder, float minConfidence,
                       int cropPadding, int cropMinHeight, float cropRotateThreshold,
                       boolean qualityGate, float blurThreshold,
                       boolean sigmoidDetect, boolean sigmoidRecognize) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.direction = direction;
        this.enhancer = enhancer;
        this.enhanceInPipeline = enhanceInPipeline;
        this.sortReadingOrder = sortReadingOrder;
        this.minConfidence = Math.max(0f, Math.min(1f, minConfidence));
        this.cropPadding = Math.max(0, cropPadding);
        this.cropMinHeight = Math.max(1, cropMinHeight);
        this.cropRotateThreshold = cropRotateThreshold;
        this.qualityGate = qualityGate;
        this.blurThreshold = blurThreshold;
        this.sigmoidDetect = sigmoidDetect;
        this.sigmoidRecognize = sigmoidRecognize;
        this.pipeline = buildPipeline();
    }

    /**
     * 构建器。
     *
     * @return Builder 实例
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
         * 检测器
         */
        private ImageDetector detector;

        /**
         * 识别器
         */
        private OcrRecognizer recognizer;

        /**
         * 方向矫正模型 ID，可为 null
         */
        private String direction;

        /**
         * 文字高清化模型 ID，可为 null
         */
        private String enhancer;

        /**
         * 是否按阅读顺序排序
         */
        private boolean sortReadingOrder = true;

        /**
         * 是否在识别管线内启用文字高清化
         */
        private boolean enhanceInPipeline;

        /**
         * 最低识别置信度
         */
        private float minConfidence;

        /**
         * 裁剪框四周扩展像素数（默认 2）
         */
        private int cropPadding = 2;

        /**
         * 裁剪块最小高度，低于此值自动放大 2 倍（默认 40）
         */
        private int cropMinHeight = 40;

        /**
         * 大角度矫正阈值（度）：检测框角度绝对值超过该值时按旋转矩形扶正裁剪。
         * 默认 25°：rec 对 ±20° 内倾斜鲁棒（实测 -17° 直接识别即正确），
         * 仅对超过 25° 的大倾斜做旋转扶正。
         */
        private float cropRotateThreshold = 25f;

        /**
         * 质量门控：模糊图（清晰度低于 blurThreshold）跳过方向矫正并优先进修复（默认关闭）
         */
        private boolean qualityGate;

        /**
         * 清晰度阈值：模糊度评分低于该值判定为模糊（默认 100，与 OpencvImageQualityAssessor 一致）
         */
        private float blurThreshold = 100f;

        /**
         * 检测输出是否应用 sigmoid（默认 false）
         */
        private boolean sigmoidDetect;

        /**
         * 识别输出是否应用 sigmoid（默认 false）
         */
        private boolean sigmoidRecognize;

        /**
         * 设置检测器。
         *
         * @param detector 检测器实例
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
         * @param recognizer 识别器实例
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
         * 按模型 ID 设置方向矫正器。
         *
         * @param modelId 模型 ID，如 "pp-word-rotate"
         * @return this
         */
        public Builder direction(String modelId) {
            this.direction = modelId;
            return this;
        }

        /**
         * 按模型 ID 设置文字高清化器。
         *
         * @param modelId 模型 ID，如 "text-bsr"
         * @return this
         */
        public Builder enhancer(String modelId) {
            this.enhancer = modelId;
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
         * 是否在管线内启用文字高清化。
         *
         * @param enhanceInPipeline true 启用
         * @return this
         */
        public Builder enhanceInPipeline(boolean enhanceInPipeline) {
            this.enhanceInPipeline = enhanceInPipeline;
            return this;
        }

        /**
         * 设置最低识别置信度。
         *
         * @param minConfidence 阈值 0~1
         * @return this
         */
        public Builder minConfidence(float minConfidence) {
            this.minConfidence = minConfidence;
            return this;
        }

        /**
         * 设置裁剪框四周扩展像素数。
         *
         * @param cropPadding 扩展像素数
         * @return this
         */
        public Builder cropPadding(int cropPadding) {
            this.cropPadding = cropPadding;
            return this;
        }

        /**
         * 设置裁剪块最小高度（低于此值自动放大 2 倍）。
         *
         * @param cropMinHeight 最小高度（像素）
         * @return this
         */
        public Builder cropMinHeight(int cropMinHeight) {
            this.cropMinHeight = cropMinHeight;
            return this;
        }

        /**
         * 设置大角度矫正阈值（度）。
         * <p>检测框角度绝对值超过该值时，按旋转矩形扶正裁剪后识别；
         * 低于该值直接轴对齐裁剪识别（依赖 rec 对 ±20° 倾斜的鲁棒性）。
         * 设为负值可禁用大角度矫正。</p>
         *
         * @param cropRotateThreshold 阈值（度），如 15
         * @return this
         */
        public Builder cropRotateThreshold(float cropRotateThreshold) {
            this.cropRotateThreshold = cropRotateThreshold;
            return this;
        }

        /**
         * 启用质量门控。
         * <p>识别前先评估图像清晰度（Laplacian 方差），若模糊则：① 跳过方向矫正
         * （方向模型对模糊图分类不可靠，实测会把横排模糊图误判 90° 旋转）；
         * ② 若配置了 enhancer，自动启用文字高清修复后再识别。</p>
         *
         * @param qualityGate true 启用
         * @return this
         */
        public Builder qualityGate(boolean qualityGate) {
            this.qualityGate = qualityGate;
            return this;
        }

        /**
         * 设置清晰度阈值（模糊度评分低于该值判定为模糊）。
         *
         * @param blurThreshold 阈值，默认 100
         * @return this
         */
        public Builder blurThreshold(float blurThreshold) {
            this.blurThreshold = blurThreshold;
            return this;
        }

        /**
         * 设置检测输出是否应用 sigmoid。
         *
         * @param sigmoidDetect true 应用 sigmoid
         * @return this
         */
        public Builder sigmoidDetect(boolean sigmoidDetect) {
            this.sigmoidDetect = sigmoidDetect;
            return this;
        }

        /**
         * 设置识别输出是否应用 sigmoid。
         *
         * @param sigmoidRecognize true 应用 sigmoid
         * @return this
         */
        public Builder sigmoidRecognize(boolean sigmoidRecognize) {
            this.sigmoidRecognize = sigmoidRecognize;
            return this;
        }

        /**
         * 构建。
         *
         * @return OcrPipeline
         */
        public OcrPipeline build() {
            return new OcrPipeline(detector, recognizer,
                    createTranslator(direction), createTranslator(enhancer),
                    enhanceInPipeline, sortReadingOrder, minConfidence,
                    cropPadding, cropMinHeight, cropRotateThreshold,
                    qualityGate, blurThreshold,
                    sigmoidDetect, sigmoidRecognize);
        }

        /**
         * 按模型 ID 懒创建翻译器。
         *
         * @param modelId 模型 ID，可为 null
         * @return 翻译器或 null
         */
        @SuppressWarnings("unchecked")
        private static ITranslator<Object, Object> createTranslator(String modelId) {
            if (modelId == null || modelId.isBlank()) {
                return null;
            }
            return (ITranslator<Object, Object>) AbstractIdentificationEngine.getInstance()
                    .get(modelId, ITranslator.class);
        }
    }

    /**
     * 编排识别管线（裁剪 → 修复 → 识别 → 收集）。
     *
     * <p>顺序：检测（外层）→ 裁剪 → 修复 → 识别。小角度框（|angle| ≦ 阈值，默认 15°）
     * 轴对齐裁剪直接识别，依赖 rec 对 ±20° 内倾斜鲁棒；超过阈值的大角度框
     * 按旋转矩形中心扶正后裁剪（cropRotated），避免倾斜文字识别失败。</p>
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
                    DetectionInfo box = oc.currentBox();
                    // 大角度（超过阈值）：按旋转矩形扶正裁剪，避免倾斜文字识别失败
                    if (cropRotateThreshold >= 0 && Math.abs(box.angle()) > cropRotateThreshold) {
                        oc.currentCrop(upscaleIfSmall(
                                ImageUtils.cropRotated(oc.imageData(),
                                        box.cx(), box.cy(), box.rw(), box.rh(), box.angle()),
                                cropMinHeight));
                        return null;
                    }
                    // 小角度：轴对齐裁剪直接 rec（rec 对 ±20° 内倾斜鲁棒，实测无需 deskew）
                    int px = cropPadding;
                    byte[] crop = ImageCropUtils.crop(oc.imageData(),
                            (int) box.x() - px, (int) box.y() - px,
                            (int) box.width() + px * 2, (int) box.height() + px * 2);
                    // 裁剪块过小时放大 2 倍，提升 rec 对小字识别率
                    oc.currentCrop(upscaleIfSmall(crop, cropMinHeight));
                    return null;
                }).taskEnd()
                .decision("hasCrop", ctx -> current(ctx).currentCrop() != null ? NODE_ENHANCE : NODE_END)
                .task(NODE_ENHANCE, ctx -> {
                    OcrContext oc = current(ctx);
                    if ((!enhanceInPipeline && !(qualityGate && oc.blurry())) || enhancer == null) {
                        return null;
                    }
                    byte[] crop = oc.currentCrop();
                    if (crop != null) {
                        try {
                            Object r = enhancer.translate(crop);
                            if (r instanceof BufferedImage img) {
                                oc.currentCrop(toBytes(img));
                            } else if (r instanceof byte[] bytes) {
                                oc.currentCrop(bytes);
                            }
                        } catch (Exception e) {
                            log.debug("[ocr-pipeline] 文字高清化失败，使用原图: {}", e.getMessage());
                        }
                    }
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
                            oc.currentRectangle(),
                            oc.currentBox().angle()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 识别整图文本（方向矫正 → 检测 → 修复 → 识别 → 拼接）。
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
        return recognizeDetailWithImage(imageData).results();
    }

    /**
     * 详细识别：返回实际识别使用的图 + 结果。
     *
     * <p>流程：整图方向矫正 → 检测 → 裁剪 → 裁剪块矫正（0°/180°）→ 修复 → 识别。</p>
     *
     * @param imageData 图片
     * @return 矫正后图 + 结果
     */
    public OcrRecognizeResult recognizeDetailWithImage(byte[] imageData) {
        byte[] corrected = correct(imageData);
        List<OcrResult> result = recognizeFrom(corrected);
        return new OcrRecognizeResult(corrected, result);
    }

    /**
     * 识别结果（含实际识别使用的图）。
     *
     * @param image   实际识别使用的图（矫正或原图）
     * @param results 识别结果
     * @author CH
     * @since 4.0.0.42
     */
    public record OcrRecognizeResult(byte[] image, List<OcrResult> results) {
    }

    /**
     * 对指定图执行完整识别（预处理 → 检测 → 裁剪 → 识别）。
     *
     * @param imageData 图片
     * @return 结果列表
     */
    private List<OcrResult> recognizeFrom(byte[] imageData) {
        byte[] prepared = autoInvertIfDark(imageData);
        List<DetectionInfo> boxes = detector.detect(prepared);
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        // 检测置信度阈值过滤
        if (minConfidence > 0f) {
            boxes = boxes.stream()
                    .filter(b -> b.confidence() >= minConfidence)
                    .toList();
            if (boxes.isEmpty()) {
                return List.of();
            }
        }
        // 保持原始检测框（不合并，保证几何信息可还原），仅排序
        List<DetectionInfo> ordered = sortReadingOrder
                ? boxes.stream()
                .sorted(Comparator
                        .comparingDouble(DetectionInfo::y)
                        .thenComparingDouble(DetectionInfo::x))
                .toList()
                : boxes;
        OcrContext oc = new OcrContext(prepared, ordered);
        // 质量门控：模糊图强制启用文字高清修复，提升低质量文字识别率
        if (qualityGate && enhancer != null) {
            oc.blurry(ImageUtils.isBlurry(prepared, blurThreshold));
        }
        while (oc.advance()) {
            runSingle(oc);
        }
        List<OcrResult> results = oc.results();
        if (minConfidence > 0f) {
            return results.stream()
                    .filter(r -> r.confidence() >= minConfidence)
                    .toList();
        }
        return results;
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
     * 方向矫正（单独能力）。
     *
     * <p>使用配置的方向模型对整图判方向（0°/90°/180°/270°），
     * 未配置方向模型时原样返回。</p>
     *
     * @param imageData 图片
     * @return 矫正后图片
     */
    public byte[] correct(byte[] imageData) {
        if (direction == null) {
            return imageData;
        }
        // 质量门控：模糊图方向模型分类不可靠，跳过方向矫正（避免误旋转）
        if (qualityGate && enhancer != null && ImageUtils.isBlurry(imageData, blurThreshold)) {
            log.debug("[ocr-pipeline] 图像模糊，跳过方向矫正");
            return imageData;
        }
        try {
            String[] dir = classifyBytesProb(imageData);
            String cls = dir[0];
            float prob = Float.parseFloat(dir[1]);
            // 置信度低于 0.6 时跳过矫正，避免误旋转
            if (prob < 0.6f) {
                return imageData;
            }
            int rotate = 0;
            switch (cls) {
                case "90" -> rotate = 270;
                case "180" -> rotate = 180;
                case "270" -> rotate = 90;
                default -> {
                    // 0° 无需旋转
                }
            }
            if (rotate == 0) {
                return imageData;
            }
            return ImageUtils.rotate(imageData, rotate);
        } catch (Exception e) {
            log.warn("[ocr-pipeline] 方向矫正失败，使用原图: {}", e.getMessage());
            return imageData;
        }
    }

    /**
     * 文字高清化（单独能力）。
     *
     * @param imageData 图片
     * @return 修复后图片，未配置时原样返回
     */
    public byte[] enhance(byte[] imageData) {
        if (enhancer == null) {
            return imageData;
        }
        try {
            Object r = enhancer.translate(imageData);
            if (r instanceof BufferedImage img) {
                return toBytes(img);
            }
            if (r instanceof byte[] bytes) {
                return bytes;
            }
            return imageData;
        } catch (Exception e) {
            log.warn("[ocr-pipeline] 文字高清化失败，使用原图: {}", e.getMessage());
            return imageData;
        }
    }

    /**
     * 方向分类（含概率）。
     *
     * @param imageData 图像字节
     * @return {方向, 概率}
     */
    private String[] classifyBytesProb(byte[] imageData) {
        Object r = direction.translate(imageData);
        if (r instanceof DirectionInfo info) {
            return new String[]{info.getName(), String.valueOf(info.getProbability())};
        }
        try {
            java.lang.reflect.Method gm = r.getClass().getMethod("getName");
            java.lang.reflect.Method pm = r.getClass().getMethod("getProbability");
            Object v = gm.invoke(r);
            Object p = pm.invoke(r);
            return new String[]{v == null ? "0" : String.valueOf(v), p == null ? "0" : String.valueOf(p)};
        } catch (Exception e) {
            return new String[]{"0", "0"};
        }
    }

/**
     * 旋转图像字节。
     *
     * @param imageData 图像字节
     * @param degree    旋转角度
     * @return 旋转后 PNG 字节
     */
    private static byte[] rotateBytes(byte[] imageData, int degree) {
        try {
            return ImageUtils.rotate(imageData, degree);
        } catch (Exception e) {
            return imageData;
        }
    }

    /**
     * 裁剪块过小时放大 2 倍，提升 rec 对小字识别率。
     *
     * @param crop     裁剪图
     * @param minHeight 最小高度阈值（像素）
     * @return 放大后图；无需放大时原样返回
     */
    private static byte[] upscaleIfSmall(byte[] crop, int minHeight) {
        if (crop == null) {
            return null;
        }
        try {
            Mat src = ImageUtils.decode(crop);
            if (src == null || src.empty()) {
                return crop;
            }
            try {
                int h = src.rows();
                if (h >= minHeight) {
                    return crop;
                }
                return ImageUtils.upscale(crop, 2);
            } finally {
                src.release();
            }
        } catch (Exception e) {
            log.debug("[ocr-pipeline] 裁剪放大跳过: {}", e.getMessage());
            return crop;
        }
    }

    /**
     * 深背景自动反色（白底黑字），提升 OCR 识别率。
     *
     * <p>计算图像平均亮度，若低于阈值（深色背景 + 亮色文字），反色为
     * 白底黑字，匹配 PP-OCR 训练分布。浅背景原样返回。</p>
     *
     * @param imageData 图片
     * @return 处理后图片
     */
    private static byte[] autoInvertIfDark(byte[] imageData) {
        try {
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                return imageData;
            }
            try {
                Mat gray = new Mat();
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
                if (Core.mean(gray).val[0] < 128) {
                    // 平均亮度低于 128，判定为深色背景，反色为白底
                    Core.bitwise_not(src, src);
                    MatOfByte mob = new MatOfByte();
                    org.opencv.imgcodecs.Imgcodecs.imencode(".png", src, mob);
                    return mob.toArray();
                }
                return imageData;
            } finally {
                src.release();
            }
        } catch (Exception e) {
            log.debug("[ocr-pipeline] 深背景反色跳过: {}", e.getMessage());
            return imageData;
        }
    }

    /**
     * BufferedImage 转 PNG 字节。
     *
     * @param img 图像
     * @return PNG 字节
     */
    private static byte[] toBytes(BufferedImage img) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("图像编码失败", e);
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
     * <p>动态从 {@link ModelRegistry} 注册表获取全部模型，按能力接口与模型名称约定归类。</p>
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
        if (cap == com.chua.deeplearning.support.image.ImageClassifier.class) {
            return null;
        }
        if (cap == com.chua.deeplearning.support.feature.FeatureExtractor.class) {
            return null;
        }
        return nameContains(name, "doc-layout", "ocr-layout", "pp-doc-layout", "layout-lmv3") ? "layout"
                : nameContains(name, "pp-structure", "table-struct", "table-structure") ? "table"
                : nameContains(name, "pp-word-rotate", "word-rotate", "doc-orientation") ? "direction"
                : nameContains(name, "svtr", "extractor", "ocr-rec", "paddle-ocr-rec", "ocr_rec", "rec_infer", "pp-ocr-rec") ? "recognizer"
                : nameContains(name, "ocr-det", "ocr_det", "ocr-detector", "ocr-detection") ? "detector"
                : nameContains(name, "paddleocrv6-det", "paddleocrv6-rec") ? (name.contains("det") ? "detector" : "recognizer")
                : null;
    }

    /**
     * 名称是否包含任一关键字。
     *
     * @param name 名称（小写）
     * @param keys 关键字列表
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
     * 获取检测器。
     *
     * @return ImageDetector
     */
    public ImageDetector detector() {
        return detector;
    }

    /**
     * 获取识别器。
     *
     * @return OcrRecognizer
     */
    public OcrRecognizer recognizer() {
        return recognizer;
    }

    /**
     * 检测输出是否应用 sigmoid。
     *
     * @return true 应用
     */
    public boolean sigmoidDetect() {
        return sigmoidDetect;
    }

    /**
     * 识别输出是否应用 sigmoid。
     *
     * @return true 应用
     */
    public boolean sigmoidRecognize() {
        return sigmoidRecognize;
    }

    /**
     * 绘制检测结果标注图（含旋转框 + 识别文字）。
     *
     * <p>对输入图执行检测+识别管线，返回标注了旋转框和识别文字的图片。
     * 仅标注有对应识别结果的检测框（无识别结果的框不标注）。</p>
     *
     * @param imageData 原图
     * @return 标注后 JPEG 字节
     */
    public byte[] toDrawer(byte[] imageData) {
        byte[] corrected = correct(imageData);
        List<DetectionInfo> allBoxes = detector.detect(corrected);
        // 整图识别模式（一体化 OCR 模型 det+rec 同时出框与文本），避免管线二次裁剪导致识别失真
        List<OcrResult> results = recognizer.recognizeDetail(imageData);
        return withInitDrawer().target(corrected).boxes(allBoxes, results).done();
    }

    /**
     * 创建标注管线，支持自定义绘制流程。
     *
     * <p>通过 {@link DrawerPipeline#target(byte[])} 设置矫正图，
     * {@link DrawerPipeline#boxes(List, List)} 一键注入检测框与标签，
     * {@link DrawerPipeline#done()} 完成绘制。绘制过程可用
     * {@link DrawerPipeline#onProcess(java.util.function.BiConsumer)} 观察进度。</p>
     *
     * @return DrawerPipeline 实例
     */
    public DrawerPipeline withInitDrawer() {
        return new DrawerPipeline(minConfidence);
    }
}
