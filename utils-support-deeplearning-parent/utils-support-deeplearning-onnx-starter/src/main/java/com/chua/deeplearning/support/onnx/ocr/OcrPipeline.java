package com.chua.deeplearning.support.onnx.ocr;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.ocr.OcrContext;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.onnx.ocr.direction.DirectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageCropUtils;
import com.chua.deeplearning.support.utils.OpenCvImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OCR 统一能力门面。
 *
 * <p>基于 {@link Pipeline} 通用管线框架，聚合文字识别全量能力：
 * 方向矫正、文字修复/高清化、检测、识别。单独能力直接委托底层模型实例，
 * 未注入的能力返回安全默认值。</p>
 *
 * <p>管线能力（correct → detect → crop → enhance → recognize → collect）由通用管线框架编排，
 * 多文本块场景由外层循环驱动，每个文本块一个独立 {@link PipelineContext}。</p>
 *
 * <pre>{@code
 * OcrPipeline pipeline = OcrPipeline.builder()
 *         .detector("paddleocrv6-medium-det")
 *         .recognizer("paddleocrv6-medium-rec")
 *         .direction("pp-word-rotate")
 *         .enhancer("text-bsr")        // 文字高清化，默认 2x；可用 "text-bsr:4" 指定 4x
 *         .build();
 *
 * String text = pipeline.recognize(imageBytes);
 * List&lt;OcrResult&gt; results = pipeline.recognizeDetail(imageBytes);
 * byte[] corrected = pipeline.correct(imageBytes);
 * byte[] enhanced = pipeline.enhance(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OcrPipeline {

    /**
     * 节点：矫正
     */
    private static final String NODE_CORRECT = "correct";

    /**
     * 节点：裁剪
     */
    private static final String NODE_CROP = "crop";

    /**
     * 节点：修复
     */
    private static final String NODE_ENHANCE = "enhance";

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
     * 文字检测器。
     */
    private final ImageDetector detector;

    /**
     * 文字识别器。
     */
    private final OcrRecognizer recognizer;

    /**
     * 方向矫正翻译器，可为 null。
     */
    private final ITranslator<Object, Object> direction;

    /**
     * 文字高清化翻译器，可为 null。
     */
    private final ITranslator<Object, Object> enhancer;

    /**
     * 是否在识别管线内对文本块启用文字高清化（textbsr 单帧较慢，默认关闭）。
     */
    private final boolean enhanceInPipeline;

    /**
     * 是否按阅读顺序排序。
     */
    private final boolean sortReadingOrder;

    /**
     * 最低识别置信度（过滤背景防伪/噪声文字，如车票 "CR" 水印）。0 表示不过滤。
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
     * @param direction        方向矫正，可为 null
     * @param enhancer         文字高清化，可为 null
     * @param enhanceInPipeline 是否在管线内启用高清化
     * @param sortReadingOrder 阅读序
     */
    public OcrPipeline(ImageDetector detector, OcrRecognizer recognizer,
                       ITranslator<Object, Object> direction, ITranslator<Object, Object> enhancer,
                       boolean enhanceInPipeline, boolean sortReadingOrder) {
        this(detector, recognizer, direction, enhancer, enhanceInPipeline, sortReadingOrder, 0f);
    }

    /**
     * 构造。
     *
     * @param detector         检测
     * @param recognizer       识别
     * @param direction        方向矫正，可为 null
     * @param enhancer         文字高清化，可为 null
     * @param enhanceInPipeline 是否在管线内启用高清化
     * @param sortReadingOrder 阅读序
     * @param minConfidence    最低识别置信度（0 不过滤）
     */
    public OcrPipeline(ImageDetector detector, OcrRecognizer recognizer,
                       ITranslator<Object, Object> direction, ITranslator<Object, Object> enhancer,
                       boolean enhanceInPipeline, boolean sortReadingOrder, float minConfidence) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.direction = direction;
        this.enhancer = enhancer;
        this.enhanceInPipeline = enhanceInPipeline;
        this.sortReadingOrder = sortReadingOrder;
        this.minConfidence = Math.max(0f, Math.min(1f, minConfidence));
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
         * 方向矫正模型 ID，可为 null。
         */
        private String direction;

        /**
         * 文字高清化模型 ID，可为 null。
         */
        private String enhancer;

        /**
         * 是否按阅读顺序排序。
         */
        private boolean sortReadingOrder = true;

        /**
         * 是否在识别管线内启用文字高清化。
         */
        private boolean enhanceInPipeline;

        /**
         * 最低识别置信度（0 不过滤）。
         */
        private float minConfidence;

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
         * @param modelId 模型 ID，如 "text-bsr"（支持带参数 {@code "text-bsr:4"} 指定放大倍数，默认 2x）
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
         * 是否在识别管线内对文本块启用文字高清化（较慢，默认关闭）。
         *
         * @param enhanceInPipeline true 启用
         * @return this
         */
        public Builder enhanceInPipeline(boolean enhanceInPipeline) {
            this.enhanceInPipeline = enhanceInPipeline;
            return this;
        }

        /**
         * 设置最低识别置信度（过滤背景防伪/噪声文字）。
         *
         * @param minConfidence 0~1，0 不过滤
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
            return new OcrPipeline(detector, recognizer,
                    createTranslator(direction), createTranslator(enhancer),
                    enhanceInPipeline, sortReadingOrder, minConfidence);
        }

        /**
         * 按模型 ID 懒创建翻译器。
         *
         * <p>支持带参数模型 ID：{@code modelId:scale}（如 {@code text-bsr:4}），
         * 通过反射对 TextBsrTranslator.setScale 注入放大倍数。</p>
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
     * 编排识别管线（裁剪 → 裁剪块方向矫正 → 修复 → 识别 → 收集）。
     *
     * <p>顺序：检测（外层）→ 裁剪 → 对每个文字块方向矫正 → 修复 → 识别。
     * 方向矫正在裁剪之后对文字块执行，避免整图矫正误判。</p>
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
                int px = 2;
                    byte[] crop = ImageCropUtils.crop(oc.imageData(),
                            (int) box.x() - px, (int) box.y() - px,
                            (int) box.width() + px * 2, (int) box.height() + px * 2);
                    oc.currentCrop(upscaleIfSmall(crop));
                    return null;
                }).taskEnd()
                .decision("hasCrop", ctx -> current(ctx).currentCrop() != null ? NODE_CORRECT : NODE_END)
                .task(NODE_CORRECT, ctx -> {
                    // 裁剪块方向矫正（0°/180°），仅当方向模型高置信
                    OcrContext oc = current(ctx);
                    byte[] crop = oc.currentCrop();
                    if (direction != null && crop != null) {
                        try {
                            String[] dir = classifyBytesProb(crop);
                            if ("180".equals(dir[0]) && Float.parseFloat(dir[1]) >= 0.6f) {
                                byte[] rotated = rotateBytes(crop, 180);
                                oc.currentCrop(rotated);
                            }
                        } catch (Exception e) {
                            log.debug("[ocr-pipeline] 裁剪块方向矫正跳过: {}", e.getMessage());
                        }
                    }
                    return null;
                }).taskEnd()
                .task(NODE_ENHANCE, ctx -> {
                    OcrContext oc = current(ctx);
                    if (!enhanceInPipeline || enhancer == null) {
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
                            oc.currentRectangle()));
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
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * 详细识别：矫正择优 + 预处理 + 检测框 + 文本。
     *
     * <p>方向模型置信度较低时可能误判（如复杂背景/图表误判 180°），
     * 此处比较矫正前后两个方向，选择识别出有效文字更多的方向。</p>
     *
     * @param imageData 图片
     * @return 结果列表
     */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return recognizeDetailWithImage(imageData).results();
    }

    /**
     * 识别结果（含实际识别使用的图）。
     *
     * @param image 实际识别使用的图（矫正或原图）
     * @param results 识别结果
     */
    public record OcrRecognizeResult(byte[] image, List<OcrResult> results) {
    }

    /**
     * 详细识别：返回实际识别使用的图 + 结果。
     *
     * <p>流程：整图方向矫正 → 检测 → 裁剪 → 裁剪块矫正（0°/180°）→ 修复 → 识别。
     * 整图矫正处理整体旋转（如车票 90/180/270），小块矫正兜底同图混合角度。</p>
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
        // 保持原始检测框（不合并，保证几何信息可还原），仅排序
        List<DetectionInfo> ordered = sortReadingOrder
                ? boxes.stream()
                .sorted(Comparator
                        .comparingDouble(DetectionInfo::y)
                        .thenComparingDouble(DetectionInfo::x))
                .toList()
                : boxes;
        OcrContext oc = new OcrContext(prepared, ordered);
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
     * 裁剪块过小时放大 2 倍（双线性插值），提升 rec 对小字识别率。
     *
     * @param crop 裁剪图
     * @return 放大后图；无需放大时原样返回
     */
    private static byte[] upscaleIfSmall(byte[] crop) {
        if (crop == null) {
            return null;
        }
        try {
            Mat src = OpenCvImageUtils.decode(crop);
            if (src == null || src.empty()) {
                return crop;
            }
            try {
                int h = src.rows();
                if (h >= 40) {
                    return crop;
                }
                return OpenCvImageUtils.upscale(crop, 2);
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
            byte[] result = OpenCvImageUtils.invertIfDark(imageData);
            if (!java.util.Arrays.equals(result, imageData)) {
                log.info("[ocr-pipeline] 检测到深色背景，已反色为白底黑字");
            }
            return result;
        } catch (Exception e) {
            log.debug("[ocr-pipeline] 深背景反色跳过: {}", e.getMessage());
            return imageData;
        }
    }

    /**
     * 方向矫正（单独能力）。
     *
     * <p>横向图（宽&gt;高）：方向模型判 0°/180°；纵向图：先旋转 90° 判 0/180，
     * 最终旋转 90° 或 270°。未配置方向模型时原样返回。</p>
     *
     * @param imageData 图片
     * @return 矫正后图片
     */
    public byte[] correct(byte[] imageData) {
        if (direction == null) {
            return imageData;
        }
        try {
            String[] dir = classifyBytesProb(imageData);
            String cls = dir[0];
            float prob = Float.parseFloat(dir[1]);
            // 置信度低于 0.6 时跳过矫正
            if (prob < 0.6f) {
                return imageData;
            }
            int rotate = 0;
            switch (cls) {
                case "90" -> rotate = 270;  // 顺时针转 270° = 逆时针 90°
                case "180" -> rotate = 180;
                case "270" -> rotate = 90;   // 顺时针转 90°
            }
            if (rotate == 0) {
                return imageData;
            }
            return OpenCvImageUtils.rotate(imageData, rotate);
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
     * 对字节图判方向（0°/180°）。
     *
     * @param imageData 图像字节
     * @return "0" 或 "180"
     */
    private String classifyBytes(byte[] imageData) {
        return classifyBytesProb(imageData)[0];
    }

    /**
     * 旋转图像字节（90/180/270 度）。
     *
     * @param imageData 图像字节
     * @param degree    旋转角度（90/180/270）
     * @return 旋转后 PNG 字节
     */
    private static byte[] rotateBytes(byte[] imageData, int degree) {
        try {
            return OpenCvImageUtils.rotate(imageData, degree);
        } catch (Exception e) {
            return imageData;
        }
    }

    /**
     * 对倾斜文字块执行 deskew（通过 OpenCV warpAffine 旋转扶正）。
     *
     * @param crop  裁剪块 PNG 字节
     * @param angle 旋转角度（度），正=顺时针
     * @return 扶正后 PNG 字节
     */
    public static byte[] deskew(byte[] crop, float angle) {
        try {
            OpenCvImageUtils.load();
            Mat src = OpenCvImageUtils.decode(crop);
            if (src == null || src.empty()) return crop;
            try {
                Point center = new Point(src.cols() / 2.0, src.rows() / 2.0);
                Mat rot = Imgproc.getRotationMatrix2D(center, angle, 1.0);
                Mat dst = new Mat();
                Imgproc.warpAffine(src, dst, rot, src.size(), Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT,
                        new Scalar(255, 255, 255));
                byte[] result = OpenCvImageUtils.encode(dst);
                dst.release();
                rot.release();
                return result;
            } finally {
                src.release();
            }
        } catch (Exception e) {
            log.debug("[ocr-pipeline] deskew 跳过: {}", e.getMessage());
            return crop;
        }
    }

    /**
     * 枚举可用模型清单。
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
        if (cap == com.chua.deeplearning.support.image.ImageDetector.class) {
            return nameContains(name, "ocr", "paddle") ? "detector" : null;
        }
        if (cap == com.chua.deeplearning.support.layout.LayoutDetector.class) {
            return "layout";
        }
        if (cap == com.chua.deeplearning.support.image.ImageClassifier.class
                || cap == com.chua.deeplearning.support.feature.FeatureExtractor.class) {
            return null;
        }
        return nameContains(name, "doc-layout", "ocr-layout", "pp-doc-layout", "layout-lmv3") ? "layout"
                : nameContains(name, "pp-structure", "table-struct", "table-structure") ? "table"
                : nameContains(name, "pp-word-rotate", "word-rotate", "ppocr-cls") ? "direction"
                : nameContains(name, "text-bsr", "text_restore", "textbsr") ? "enhancer"
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
     * BufferedImage 转 PNG 字节。
     *
     * @param img 图像
     * @return PNG 字节
     */
    private static byte[] toBytes(BufferedImage img) {
        return OpenCvImageUtils.encode(OpenCvImageUtils.toMat(img), "png");
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
