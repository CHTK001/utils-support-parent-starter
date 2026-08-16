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
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
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
 *         .enhancer("text-bsr")
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
        this.detector = Objects.requireNonNull(detector, "detector");
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.direction = direction;
        this.enhancer = enhancer;
        this.enhanceInPipeline = enhanceInPipeline;
        this.sortReadingOrder = sortReadingOrder;
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
         * 构建。
         *
         * @return OcrPipeline
         */
        public OcrPipeline build() {
            return new OcrPipeline(detector, recognizer,
                    createTranslator(direction), createTranslator(enhancer),
                    enhanceInPipeline, sortReadingOrder);
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
     * 编排识别管线（矫正 → 裁剪 → 修复 → 识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("ocr-recognize")
                .task(NODE_CORRECT, ctx -> null).taskEnd()
                .task(NODE_CROP, ctx -> {
                    OcrContext oc = current(ctx);
                    if (oc.currentBox() == null) {
                        return null;
                    }
                    oc.currentCrop(ImageCropUtils.crop(oc.imageData(), oc.currentBox()));
                    return null;
                }).taskEnd()
                .decision("hasCrop", ctx -> current(ctx).currentCrop() != null ? NODE_ENHANCE : NODE_END)
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
     * 详细识别：矫正 + 预处理 + 检测框 + 文本。
     *
     * @param imageData 图片
     * @return 结果列表
     */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        byte[] corrected = correct(imageData);
        byte[] prepared = autoInvertIfDark(corrected);
        List<DetectionInfo> boxes = detector.detect(prepared);
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
        OcrContext oc = new OcrContext(prepared, ordered);
        while (oc.advance()) {
            runSingle(oc);
        }
        return oc.results();
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
            nu.pattern.OpenCV.loadLocally();
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageData), Imgcodecs.IMREAD_COLOR);
            if (src == null || src.empty()) {
                return imageData;
            }
            try {
                Mat gray = new Mat();
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
                MatOfDouble mean = new MatOfDouble();
                MatOfDouble std = new MatOfDouble();
                Core.meanStdDev(gray, mean, std);
                double avg = mean.get(0, 0)[0];
                gray.release();
                if (avg >= 128) {
                    return imageData;
                }
                Mat inv = new Mat();
                Core.bitwise_not(src, inv);
                byte[] result = toBytes(inv);
                inv.release();
                log.info("[ocr-pipeline] 检测到深色背景（avg={}），已反色为白底黑字", (int) avg);
                return result;
            } finally {
                src.release();
            }
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
            nu.pattern.OpenCV.loadLocally();
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageData), Imgcodecs.IMREAD_COLOR);
            if (src == null || src.empty()) {
                return imageData;
            }
            try {
                int w = src.cols(), h = src.rows();
                boolean landscape = w > h;
                int rotate = 0;
                if (landscape) {
                    String dir = classify(src);
                    if ("180".equals(dir)) {
                        rotate = 180;
                    }
                } else {
                    Mat tmp = new Mat();
                    Core.rotate(src, tmp, Core.ROTATE_90_CLOCKWISE);
                    String dir = classify(tmp);
                    tmp.release();
                    // 纵向图：旋转90°（顺时针）后判方向
                    // 判 0 → 当前图再顺时针90°即正确（原图是逆时针90°）
                    // 判 180 → 当前图再逆时针90°即正确（原图是顺时针90°）
                    rotate = "180".equals(dir) ? 270 : 90;
                }
                if (rotate == 0) {
                    return imageData;
                }
                Mat out = new Mat();
                switch (rotate) {
                    case 90 -> Core.rotate(src, out, Core.ROTATE_90_CLOCKWISE);
                    case 180 -> Core.rotate(src, out, Core.ROTATE_180);
                    case 270 -> Core.rotate(src, out, Core.ROTATE_90_COUNTERCLOCKWISE);
                }
                byte[] result = toBytes(out);
                out.release();
                return result;
            } finally {
                src.release();
            }
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
     * 方向分类。
     *
     * @param mat 图像
     * @return "0" 或 "180"
     */
    private String classify(Mat mat) {
        MatOfByte mob = new MatOfByte();
        Imgcodecs.imencode(".png", mat, mob);
        Object r = direction.translate(mob.toArray());
        if (r instanceof DirectionInfo info) {
            return info.getName();
        }
        try {
            java.lang.reflect.Method m = r.getClass().getMethod("getName");
            Object v = m.invoke(r);
            return v == null ? "0" : String.valueOf(v);
        } catch (Exception e) {
            return "0";
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
     * Mat 转 PNG 字节。
     *
     * @param mat Mat
     * @return PNG 字节
     */
    private static byte[] toBytes(Mat mat) {
        MatOfByte mob = new MatOfByte();
        Imgcodecs.imencode(".png", mat, mob);
        return mob.toArray();
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
