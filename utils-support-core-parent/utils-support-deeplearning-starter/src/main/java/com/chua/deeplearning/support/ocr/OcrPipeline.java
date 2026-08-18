package com.chua.deeplearning.support.ocr;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageCropUtils;
import com.chua.deeplearning.support.utils.OpenCvImageUtils;
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

@Slf4j
public class OcrPipeline {

    private static final String NODE_CROP = "crop";
    private static final String NODE_CORRECT = "correct";
    private static final String NODE_ENHANCE = "enhance";
    private static final String NODE_RECOGNIZE = "recognize";
    private static final String NODE_COLLECT = "collect";
    private static final String NODE_END = "end";

    private final ImageDetector detector;
    private final OcrRecognizer recognizer;
    private final ITranslator<Object, Object> direction;
    private final ITranslator<Object, Object> enhancer;
    private final boolean enhanceInPipeline;
    private final boolean sortReadingOrder;
    private final float minConfidence;
    private final Pipeline pipeline;

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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ImageDetector detector;
        private OcrRecognizer recognizer;
        private String direction;
        private String enhancer;
        private boolean sortReadingOrder = true;
        private boolean enhanceInPipeline;
        private float minConfidence;

        public Builder detector(ImageDetector detector) {
            this.detector = detector;
            return this;
        }

        public Builder detector(String modelId) {
            this.detector = ImageDetector.create(modelId);
            return this;
        }

        public Builder recognizer(OcrRecognizer recognizer) {
            this.recognizer = recognizer;
            return this;
        }

        public Builder recognizer(String modelId) {
            this.recognizer = OcrRecognizer.create(modelId);
            return this;
        }

        public Builder direction(String modelId) {
            this.direction = modelId;
            return this;
        }

        public Builder enhancer(String modelId) {
            this.enhancer = modelId;
            return this;
        }

        public Builder sortReadingOrder(boolean sortReadingOrder) {
            this.sortReadingOrder = sortReadingOrder;
            return this;
        }

        public Builder enhanceInPipeline(boolean enhanceInPipeline) {
            this.enhanceInPipeline = enhanceInPipeline;
            return this;
        }

        public Builder minConfidence(float minConfidence) {
            this.minConfidence = minConfidence;
            return this;
        }

        public OcrPipeline build() {
            return new OcrPipeline(detector, recognizer,
                    createTranslator(direction), createTranslator(enhancer),
                    enhanceInPipeline, sortReadingOrder, minConfidence);
        }

        @SuppressWarnings("unchecked")
        private static ITranslator<Object, Object> createTranslator(String modelId) {
            if (modelId == null || modelId.isBlank()) return null;
            return (ITranslator<Object, Object>) AbstractIdentificationEngine.getInstance()
                    .get(modelId, ITranslator.class);
        }
    }

    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("ocr-recognize")
                .task(NODE_CROP, ctx -> {
                    OcrContext oc = current(ctx);
                    if (oc.currentBox() == null) return null;
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
                    OcrContext oc = current(ctx);
                    byte[] crop = oc.currentCrop();
                    if (direction != null && crop != null) {
                        try {
                            String[] dir = classifyBytesProb(crop);
                            if ("180".equals(dir[0]) && Float.parseFloat(dir[1]) >= 0.6f) {
                                oc.currentCrop(rotateBytes(crop, 180));
                            }
                        } catch (Exception e) {
                            log.debug("[ocr-pipeline] 裁剪块方向矫正跳过: {}", e.getMessage());
                        }
                    }
                    return null;
                }).taskEnd()
                .task(NODE_ENHANCE, ctx -> {
                    OcrContext oc = current(ctx);
                    if (!enhanceInPipeline || enhancer == null) return null;
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
                    if (oc.currentCrop() == null) return null;
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

    public String recognize(byte[] imageData) {
        return recognizeDetail(imageData).stream()
                .map(OcrResult::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return recognizeDetailWithImage(imageData).results();
    }

    public OcrRecognizeResult recognizeDetailWithImage(byte[] imageData) {
        byte[] corrected = correct(imageData);
        List<OcrResult> result = recognizeFrom(corrected);
        return new OcrRecognizeResult(corrected, result);
    }

    public record OcrRecognizeResult(byte[] image, List<OcrResult> results) {}

    private List<OcrResult> recognizeFrom(byte[] imageData) {
        byte[] prepared = autoInvertIfDark(imageData);
        List<DetectionInfo> boxes = detector.detect(prepared);
        if (boxes == null || boxes.isEmpty()) return List.of();
        if (minConfidence > 0f) {
            boxes = boxes.stream()
                    .filter(b -> b.confidence() >= minConfidence)
                    .toList();
            if (boxes.isEmpty()) return List.of();
        }
        List<DetectionInfo> ordered = sortReadingOrder
                ? boxes.stream()
                .sorted(Comparator.comparingDouble(DetectionInfo::y).thenComparingDouble(DetectionInfo::x))
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

    private void runSingle(OcrContext oc) {
        PipelineContext<OcrContext> ctx = new PipelineContext<>(pipeline.getId(), oc);
        ctx.setAttribute("ocr", oc);
        ctx.setNextNodeId(NODE_CROP);
        pipeline.resume(ctx);
    }

    public byte[] correct(byte[] imageData) {
        if (direction == null) return imageData;
        try {
            String[] dir = classifyBytesProb(imageData);
            String cls = dir[0];
            float prob = Float.parseFloat(dir[1]);
            if (prob < 0.6f) return imageData;
            int rotate = switch (cls) {
                case "90" -> 270;
                case "180" -> 180;
                case "270" -> 90;
                default -> 0;
            };
            if (rotate == 0) return imageData;
            return OpenCvImageUtils.rotate(imageData, rotate);
        } catch (Exception e) {
            log.warn("[ocr-pipeline] 方向矫正失败，使用原图: {}", e.getMessage());
            return imageData;
        }
    }

    public byte[] enhance(byte[] imageData) {
        if (enhancer == null) return imageData;
        try {
            Object r = enhancer.translate(imageData);
            if (r instanceof BufferedImage img) return toBytes(img);
            if (r instanceof byte[] bytes) return bytes;
            return imageData;
        } catch (Exception e) {
            log.warn("[ocr-pipeline] 文字高清化失败，使用原图: {}", e.getMessage());
            return imageData;
        }
    }

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

    private static byte[] rotateBytes(byte[] imageData, int degree) {
        try {
            return OpenCvImageUtils.rotate(imageData, degree);
        } catch (Exception e) {
            return imageData;
        }
    }

    private static byte[] upscaleIfSmall(byte[] crop) {
        if (crop == null) return null;
        try {
            Mat src = OpenCvImageUtils.decode(crop);
            if (src == null || src.empty()) return crop;
            try {
                int h = src.rows();
                if (h >= 40) return crop;
                return OpenCvImageUtils.upscale(crop, 2);
            } finally {
                src.release();
            }
        } catch (Exception e) {
            log.debug("[ocr-pipeline] 裁剪放大跳过: {}", e.getMessage());
            return crop;
        }
    }

    private static byte[] autoInvertIfDark(byte[] imageData) {
        try {
            Mat src = OpenCvImageUtils.decode(imageData);
            if (src == null || src.empty()) return imageData;
            try {
                Mat gray = new Mat();
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
                if (Core.mean(gray).val[0] < 128) {
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

    private static byte[] toBytes(BufferedImage img) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("图像编码失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static OcrContext current(PipelineContext<?> ctx) {
        return (OcrContext) ctx.getAttribute("ocr");
    }

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

    private static String groupOf(ModelRegistry.Entry entry) {
        String name = entry.modelId() == null ? "" : entry.modelId().toLowerCase();
        Class<?> cap = entry.capabilityInterface();
        if (cap == com.chua.deeplearning.support.layout.LayoutDetector.class) return "layout";
        if (cap == com.chua.deeplearning.support.image.ImageDetector.class) {
            return nameContains(name, "ocr", "paddle") ? "detector" : null;
        }
        if (cap == com.chua.deeplearning.support.image.ImageClassifier.class) return null;
        if (cap == com.chua.deeplearning.support.feature.FeatureExtractor.class) return null;
        return nameContains(name, "doc-layout", "ocr-layout", "pp-doc-layout", "layout-lmv3") ? "layout"
                : nameContains(name, "pp-structure", "table-struct", "table-structure") ? "table"
                : nameContains(name, "pp-word-rotate", "word-rotate", "doc-orientation") ? "direction"
                : nameContains(name, "svtr", "extractor", "ocr-rec", "paddle-ocr-rec", "ocr_rec", "rec_infer", "pp-ocr-rec") ? "recognizer"
                : nameContains(name, "ocr-det", "ocr_det", "ocr-detector", "ocr-detection") ? "detector"
                : nameContains(name, "paddleocrv6-det", "paddleocrv6-rec") ? (name.contains("det") ? "detector" : "recognizer")
                : null;
    }

    private static boolean nameContains(String name, String... keys) {
        for (String key : keys) {
            if (name.contains(key)) return true;
        }
        return false;
    }

    public ImageDetector detector() { return detector; }
    public OcrRecognizer recognizer() { return recognizer; }
}