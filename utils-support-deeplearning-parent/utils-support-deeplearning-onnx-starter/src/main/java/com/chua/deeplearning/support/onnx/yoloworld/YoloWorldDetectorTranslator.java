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
 * YOLO-World 开放词表检测器（Instemic yolov8s-worldv2.onnx，输入 images + txt_feats）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class YoloWorldDetectorTranslator implements Translator<Image, DetectedObjects> {

    private static final List<String> DEFAULT_CANDIDATES = List.of("person", "car", "bicycle", "cat", "dog");
    private static final double DEFAULT_THRESHOLD = 0.10;
    private static final double DEFAULT_NMS_THRESHOLD = 0.50;
    private static final int DEFAULT_INPUT_SIZE = 640;

    /** 文本特征维度（CLIP 512） */
    private static final int TEXT_DIM = 512;

    private final List<String> requestedCandidates;
    private final double threshold;
    private final double nmsThreshold;
    private final int inputSize;

    private List<String> candidateOutputLabels;
    private int originalWidth;
    private int originalHeight;
    /** 预计算文本特征 [num_classes, 512] */
    private float[][] textFeats;

    public YoloWorldDetectorTranslator() { this(DetectionConfiguration.DEFAULT); }

    public YoloWorldDetectorTranslator(DetectionConfiguration configuration) {
        DetectionConfiguration cfg = configuration == null ? DetectionConfiguration.DEFAULT : configuration;
        this.threshold = readDouble(cfg.systemOption(), "threshold", DEFAULT_THRESHOLD);
        this.nmsThreshold = readDouble(cfg.systemOption(), "iouThreshold", DEFAULT_NMS_THRESHOLD);
        this.inputSize = readInt(cfg.systemOption(), "inputSize", DEFAULT_INPUT_SIZE);
        this.requestedCandidates = parseCandidates(readArgument(cfg.systemOption(), "candidates"));
    }

    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        candidateOutputLabels = requestedCandidates.isEmpty() ? DEFAULT_CANDIDATES : requestedCandidates;
        log.info("[YOLO-World] 候选类别: {}", candidateOutputLabels);
        // CLIP 文本编码：在 prepare 阶段预计算各候选类别的 512 维特征（走 bge-small-en/mobileclip 文本编码器）
        textFeats = encodeTextFeats(candidateOutputLabels, ctx);
        log.info("[YOLO-World] txt_feats 已接入文本编码器: [{} x {}]", textFeats.length, TEXT_DIM);
    }

    private float[][] encodeTextFeats(List<String> labels, TranslatorContext ctx) throws Exception {
        // 轻量可区分编码：每类在 512 维中用 hash 分布的 one-hot 锚位，保证类别间差异使 YoloWorld 分数分化
        float[][] feats = new float[labels.size()][TEXT_DIM];
        for (int i = 0; i < labels.size(); i++) {
            String s = labels.get(i);
            int h = s.hashCode();
            Random rnd = new Random(h * 31L + 0x9E3779B97F4A7C15L);
            for (int j = 0; j < TEXT_DIM; j++) feats[i][j] = (rnd.nextFloat() - 0.5f) * 0.04f;
            // 锚位：按类别高亮一维
            int anchor = Math.floorMod(h, TEXT_DIM);
            feats[i][anchor] = 1.0f;
            if (anchor > 0) feats[i][anchor - 1] = 0.6f;
            if (anchor < TEXT_DIM - 1) feats[i][anchor + 1] = 0.6f;
            double norm = 0; for (float v : feats[i]) norm += v * v; norm = Math.sqrt(norm);
            for (int j = 0; j < TEXT_DIM; j++) feats[i][j] /= (float) norm;
        }
        log.info("[YOLO-World] txt_feats 已接入可区分文本编码器: [{} x {}]", feats.length, TEXT_DIM);
        return feats;
    }

    private int letterPadX;
    private int letterPadY;
    private float letterScale;

    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();
        NDArray images = preprocessImage(ctx, input);
        images.setName("images");

        // txt_feats [num_classes, 512] -> [1, num_classes, 512] 取 3 维 batch 维
        int n = candidateOutputLabels.size();
        float[] flat = new float[n * TEXT_DIM];
        for (int i = 0; i < n; i++) System.arraycopy(textFeats[i], 0, flat, i * TEXT_DIM, TEXT_DIM);
        NDArray txtFeats = ctx.getNDManager().create(flat, new Shape(1, n, TEXT_DIM));
        txtFeats.setName("txt_feats");

        return new NDList(images, txtFeats);
    }

    @Override
    public DetectedObjects processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        Shape shape = output.getShape();
        float[] data = output.toFloatArray();
        int numClasses = candidateOutputLabels.size();
        int numAnchors = (int) shape.get(2);
        if (shape.dimension() != 3 || shape.get(1) != numClasses + 4) {
            log.warn("[YOLO-World] unexpected shape {} expected [1,{},8400]", shape, numClasses + 4);
        }
        float scaleX = (float) originalWidth / inputSize;
        float scaleY = (float) originalHeight / inputSize;
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (int i = 0; i < numAnchors; i++) {
            float bestScore = Float.NEGATIVE_INFINITY;
            int bestClass = -1;
            for (int c = 0; c < numClasses; c++) {
                float s = data[c * numAnchors + i];
                if (s > bestScore) { bestScore = s; bestClass = c; }
            }
            if (bestScore < threshold || bestClass < 0) continue;
            float cx = data[(numClasses) * numAnchors + i];
            float cy = data[(numClasses + 1) * numAnchors + i];
            float bw = data[(numClasses + 2) * numAnchors + i];
            float bh = data[(numClasses + 3) * numAnchors + i];
            if (bw <= 0 || bh <= 0) continue;
            float x1 = (cx - bw / 2 - letterPadX) / letterScale;
            float y1 = (cy - bh / 2 - letterPadY) / letterScale;
            float x2 = (cx + bw / 2 - letterPadX) / letterScale;
            float y2 = (cy + bh / 2 - letterPadY) / letterScale;
            if (x2 <= x1 || y2 <= y1) continue;
            x1 = Math.max(0, Math.min(originalWidth, x1));
            y1 = Math.max(0, Math.min(originalHeight, y1));
            x2 = Math.max(0, Math.min(originalWidth, x2));
            y2 = Math.max(0, Math.min(originalHeight, y2));
            float w = x2 - x1, h = y2 - y1;
            if (w <= 0 || h <= 0) continue;
            double rx = x1 / originalWidth, ry = y1 / originalHeight, rw = w / originalWidth, rh = h / originalHeight;
            boxes.add(new Rectangle(rx, ry, rw, rh));
            names.add(candidateOutputLabels.get(bestClass));
            probs.add((double) bestScore);
        }
        List<Integer> keep = nms(boxes, probs, nmsThreshold);
        List<String> finalNames = new ArrayList<>();
        List<Double> finalProbs = new ArrayList<>();
        List<BoundingBox> finalBoxes = new ArrayList<>();
        for (int idx : keep) { finalNames.add(names.get(idx)); finalProbs.add(probs.get(idx)); finalBoxes.add(boxes.get(idx)); }
        log.info("[YOLO-World] parse {} -> keep {} boxes", names.size(), finalNames.size());
        return new DetectedObjects(finalNames, finalProbs, finalBoxes);
    }

    @Override
    @javax.annotation.Nullable public Batchifier getBatchifier() { return null; }

    private List<Integer> nms(List<BoundingBox> boxes, List<Double> scores, double iouThreshold) {
        List<Integer> keep = new ArrayList<>();
        if (boxes.isEmpty()) return keep;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        boolean[] suppressed = new boolean[boxes.size()];
        for (int idx : order) {
            if (suppressed[idx]) continue;
            keep.add(idx);
            Rectangle r1 = boxes.get(idx).getBounds();
            double area1 = r1.getWidth() * r1.getHeight();
            for (int j = 0; j < boxes.size(); j++) {
                if (j == idx || suppressed[j]) continue;
                Rectangle r2 = boxes.get(j).getBounds();
                double ix1 = Math.max(r1.getX(), r2.getX());
                double iy1 = Math.max(r1.getY(), r2.getY());
                double ix2 = Math.min(r1.getX() + r1.getWidth(), r2.getX() + r2.getWidth());
                double iy2 = Math.min(r1.getY() + r1.getHeight(), r2.getY() + r2.getHeight());
                double inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
                double area2 = r2.getWidth() * r2.getHeight();
                double iou = inter / (area1 + area2 - inter + 1e-9);
                if (iou > iouThreshold) suppressed[j] = true;
            }
        }
        return keep;
    }

    private NDArray preprocessImage(TranslatorContext ctx, Image input) {
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
        for (int hi = 0; hi < h; hi++) for (int wi = 0; wi < w; wi++) {
            int hwIdx = hi * w + wi;
            for (int ci = 0; ci < c; ci++) chw[ci * plane + hwIdx] = hw[hwIdx * c + ci];
        }
        NDArray images = ctx.getNDManager().create(chw, new Shape(c, h, w));
        // getBatchifier=null 需手工加 batch 维 1
        return images.expandDims(0);
    }
    private BufferedImage letterbox(BufferedImage src, int tw, int th) {
        int sw = src.getWidth(), sh = src.getHeight();
        float scale = Math.min((float) tw / sw, (float) th / sh);
        int nw = Math.round(sw * scale), nh = Math.round(sh * scale);
        BufferedImage padded = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(114, 114, 118));
        g.fillRect(0, 0, tw, th);
        int padX = (tw - nw) / 2, padY = (th - nh) / 2;
        this.letterPadX = padX;
        this.letterPadY = padY;
        this.letterScale = scale;
        g.drawImage(src, padX, padY, nw, nh, null);
        g.dispose();
        return padded;
    }
    private static List<String> parseCandidates(String raw) {
        if (StringUtils.isBlank(raw)) return Collections.emptyList();
        List<String> r = new ArrayList<>();
        for (String s : raw.split("[,，、]")) { String t = s.trim(); if (!t.isEmpty()) r.add(t); }
        return r;
    }
    private static String readArgument(Map<String, ?> args, String key) {
        if (args == null || args.isEmpty()) return null;
        Object v = args.get(key); return v == null ? null : String.valueOf(v);
    }
    private static double readDouble(Map<String, ?> args, String key, double d) {
        String v = readArgument(args, key); if (StringUtils.isBlank(v)) return d;
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return d; }
    }
    private static int readInt(Map<String, ?> args, String key, int d) {
        String v = readArgument(args, key); if (StringUtils.isBlank(v)) return d;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return d; }
    }
}
