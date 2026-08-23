package com.chua.deeplearning.support.onnx.yoloworld;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.utils.StringUtils;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * YOLO-World 开放词表检测器（ultralytics yolov8s-worldv2.onnx，COCO-80 CLIP 文本特征已烘焙进检测头）。
 *
 * <p>输入：images [1,3,640,640]（无需 txt_feats）；输出：output0 [1, 4+80, 8400]，
 * 前 4 通道为 bbox(cx,cy,w,h)，后 80 通道为类别置信度（ultralytics 导出时已含 sigmoid，无需再激活）。
 * {@code candidates} 参数作为 COCO-80 内的过滤白名单，仅保留命中的类别。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class YoloWorldDetectorTranslator implements Translator<Image, DetectedObjects> {

    /** COCO-80 标准类别（ultralytics 官方顺序），文本特征已在导出时烘焙进权重 */
    private static final String[] COCO_80 = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
            "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
            "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
            "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
            "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich",
            "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
            "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
            "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    };

    private static final double DEFAULT_THRESHOLD = 0.30;
    /** 低置信度框若几乎覆盖全图（面积占比超此值）视为背景噪声丢弃 */
    private static final double FULL_IMAGE_NOISE_AREA_RATIO = 0.70;
    /** 适用全图噪声规则的置信度上限（高置信度全图框如特写照保留） */
    private static final double FULL_IMAGE_NOISE_SCORE_LIMIT = 0.45;
    private static final double DEFAULT_NMS_THRESHOLD = 0.50;
    private static final int DEFAULT_INPUT_SIZE = 640;

    private final Set<String> candidateFilter;
    private final double threshold;
    private final double nmsThreshold;
    private final int inputSize;

    private int originalWidth;
    private int originalHeight;
    private int letterPadX;
    private int letterPadY;
    private float letterScale;

    public YoloWorldDetectorTranslator() { this(DetectionConfiguration.DEFAULT); }

    public YoloWorldDetectorTranslator(DetectionConfiguration configuration) {
        DetectionConfiguration cfg = configuration == null ? DetectionConfiguration.DEFAULT : configuration;
        this.threshold = readDouble(cfg.systemOption(), "threshold", DEFAULT_THRESHOLD);
        this.nmsThreshold = readDouble(cfg.systemOption(), "iouThreshold", DEFAULT_NMS_THRESHOLD);
        this.inputSize = readInt(cfg.systemOption(), "inputSize", DEFAULT_INPUT_SIZE);
        this.candidateFilter = parseCandidates(readArgument(cfg.systemOption(), "candidates"));
    }

    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        if (candidateFilter.isEmpty()) {
            log.info("[YOLO-World] 类别: COCO-80 全量, threshold={}, iou={}", threshold, nmsThreshold);
        } else {
            log.info("[YOLO-World] COCO-80 过滤白名单: {}, threshold={}, iou={}", candidateFilter, threshold, nmsThreshold);
        }
    }

    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();
        BufferedImage original = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = letterbox(original, inputSize, inputSize);
        ai.djl.modality.cv.Image djlImg = ai.djl.modality.cv.ImageFactory.getInstance().fromImage(resized);
        NDArray array = djlImg.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = array.toType(DataType.FLOAT32, false).div(255.0f);
        Shape shape = array.getShape();
        int h = Math.toIntExact(shape.get(0)), w = Math.toIntExact(shape.get(1)), c = Math.toIntExact(shape.get(2));
        float[] hw = array.toFloatArray();
        float[] chw = new float[h * w * c];
        int plane = h * w;
        for (int hi = 0; hi < h; hi++) {
            for (int wi = 0; wi < w; wi++) {
                int hwIdx = hi * w + wi;
                for (int ci = 0; ci < c; ci++) {
                    chw[ci * plane + hwIdx] = hw[hwIdx * c + ci];
                }
            }
        }
        NDArray images = ctx.getNDManager().create(chw, new Shape(c, h, w)).expandDims(0);
        images.setName("images");
        return new NDList(images);
    }

    @Override
    public DetectedObjects processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        Shape shape = output.getShape();
        // [1, 4+nc, anchors]，前 4 通道 bbox(cx,cy,w,h)
        int numClasses = (int) shape.get(1) - 4;
        int numAnchors = (int) shape.get(2);
        if (numClasses != COCO_80.length) {
            log.warn("[YOLO-World] unexpected numClasses {} expected {}", numClasses, COCO_80.length);
        }
        float[] data = output.toFloatArray();
        if (Boolean.getBoolean("yoloworld.probe")) {
            StringBuilder sb = new StringBuilder("[YOLO-World][probe] shape=").append(output.getShape())
                    .append(" dtype=").append(output.getDataType()).append('\n');
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            int nz = 0;
            for (float v : data) {
                mn = Math.min(mn, v);
                mx = Math.max(mx, v);
                if (v != 0) { nz++; }
            }
            sb.append("[YOLO-World][probe] total=").append(data.length)
                    .append(" min=").append(mn).append(" max=").append(mx).append(" nonzero=").append(nz).append('\n');
            for (int cIdx : new int[]{0, 1, 2, 3, 4, 40, numClasses - 1}) {
                float cmx = -Float.MAX_VALUE;
                for (int i = 0; i < numAnchors; i++) { cmx = Math.max(cmx, data[cIdx * numAnchors + i]); }
                sb.append("[YOLO-World][probe] channel ").append(cIdx).append(" max=").append(cmx).append('\n');
            }
            System.out.print(sb);
        }
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (int i = 0; i < numAnchors; i++) {
            float bestLogit = Float.NEGATIVE_INFINITY;
            int bestClass = -1;
            for (int cIdx = 0; cIdx < numClasses; cIdx++) {
                float s = data[(4 + cIdx) * numAnchors + i];
                if (s > bestLogit) { bestLogit = s; bestClass = cIdx; }
            }
            // ultralytics 导出的 ONNX 类别通道已是 sigmoid 后的概率，直接作为置信度使用
            float score = bestLogit;
            if (score < threshold || bestClass < 0) { continue; }
            String label = COCO_80[bestClass % COCO_80.length];
            if (!candidateFilter.isEmpty() && !candidateFilter.contains(label)) { continue; }
            float cx = data[0 * numAnchors + i];
            float cy = data[1 * numAnchors + i];
            float bw = data[2 * numAnchors + i];
            float bh = data[3 * numAnchors + i];
            if (bw <= 0 || bh <= 0) { continue; }
            // letterbox 反算到原图坐标
            float x1 = (cx - bw / 2 - letterPadX) / letterScale;
            float y1 = (cy - bh / 2 - letterPadY) / letterScale;
            float x2 = (cx + bw / 2 - letterPadX) / letterScale;
            float y2 = (cy + bh / 2 - letterPadY) / letterScale;
            x1 = Math.max(0, Math.min(originalWidth, x1));
            y1 = Math.max(0, Math.min(originalHeight, y1));
            x2 = Math.max(0, Math.min(originalWidth, x2));
            y2 = Math.max(0, Math.min(originalHeight, y2));
            float w = x2 - x1, h = y2 - y1;
            if (w <= 0 || h <= 0) { continue; }
            // 全图级弱置信度噪声过滤：低分框几乎覆盖全图（如文字图误检为 laptop/person）直接丢弃
            double areaRatio = (double) w * h / ((double) originalWidth * originalHeight);
            if (score < FULL_IMAGE_NOISE_SCORE_LIMIT && areaRatio > FULL_IMAGE_NOISE_AREA_RATIO) {
                continue;
            }
            boxes.add(new Rectangle(x1 / originalWidth, y1 / originalHeight, w / originalWidth, h / originalHeight));
            names.add(label);
            probs.add((double) score);
        }
        List<Integer> keep = nms(boxes, probs, nmsThreshold);
        List<String> finalNames = new ArrayList<>();
        List<Double> finalProbs = new ArrayList<>();
        List<BoundingBox> finalBoxes = new ArrayList<>();
        for (int idx : keep) {
            finalNames.add(names.get(idx));
            finalProbs.add(probs.get(idx));
            finalBoxes.add(boxes.get(idx));
        }
        log.info("[YOLO-World] parse {} -> keep {} boxes", names.size(), finalNames.size());
        return new DetectedObjects(finalNames, finalProbs, finalBoxes);
    }

    @Override
    @javax.annotation.Nullable
    public Batchifier getBatchifier() { return null; }

    /**
     * 非极大值抑制，按分数降序保留未被更高分框压制的目标。
     *
     * @param boxes        候选框集合（归一化坐标）
     * @param scores       与候选框一一对应的置信度
     * @param iouThreshold IoU 超过该阈值即视为重叠并抑制
     * @return 保留的候选框下标列表
     */
    private List<Integer> nms(List<BoundingBox> boxes, List<Double> scores, double iouThreshold) {
        List<Integer> keep = new ArrayList<>();
        if (boxes.isEmpty()) { return keep; }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) { order.add(i); }
        order.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        boolean[] suppressed = new boolean[boxes.size()];
        for (int idx : order) {
            if (suppressed[idx]) { continue; }
            keep.add(idx);
            Rectangle r1 = boxes.get(idx).getBounds();
            double area1 = r1.getWidth() * r1.getHeight();
            for (int j = 0; j < boxes.size(); j++) {
                if (j == idx || suppressed[j]) { continue; }
                Rectangle r2 = boxes.get(j).getBounds();
                double ix1 = Math.max(r1.getX(), r2.getX());
                double iy1 = Math.max(r1.getY(), r2.getY());
                double ix2 = Math.min(r1.getX() + r1.getWidth(), r2.getX() + r2.getWidth());
                double iy2 = Math.min(r1.getY() + r1.getHeight(), r2.getY() + r2.getHeight());
                double inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
                double area2 = r2.getWidth() * r2.getHeight();
                double iou = inter / (area1 + area2 - inter + 1e-9);
                if (iou > iouThreshold) { suppressed[j] = true; }
            }
        }
        return keep;
    }

    private BufferedImage letterbox(BufferedImage src, int tw, int th) {
        int sw = src.getWidth(), sh = src.getHeight();
        float scale = Math.min((float) tw / sw, (float) th / sh);
        int nw = Math.round(sw * scale), nh = Math.round(sh * scale);
        BufferedImage padded = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(114, 114, 114));
        g.fillRect(0, 0, tw, th);
        int padLeft = (tw - nw) / 2, padTop = (th - nh) / 2;
        this.letterPadX = padLeft;
        this.letterPadY = padTop;
        this.letterScale = scale;
        g.drawImage(src, padLeft, padTop, nw, nh, null);
        g.dispose();
        return padded;
    }

    private static Set<String> parseCandidates(String raw) {
        if (StringUtils.isBlank(raw)) { return Collections.emptySet(); }
        Set<String> r = new HashSet<>();
        for (String s : raw.split("[,，、]")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) { r.add(t); }
        }
        return r;
    }

    private static String readArgument(Map<String, ?> args, String key) {
        if (args == null || args.isEmpty()) { return null; }
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static double readDouble(Map<String, ?> args, String key, double d) {
        String v = readArgument(args, key);
        if (StringUtils.isBlank(v)) { return d; }
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return d; }
    }

    private static int readInt(Map<String, ?> args, String key, int d) {
        String v = readArgument(args, key);
        if (StringUtils.isBlank(v)) { return d; }
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return d; }
    }
}
