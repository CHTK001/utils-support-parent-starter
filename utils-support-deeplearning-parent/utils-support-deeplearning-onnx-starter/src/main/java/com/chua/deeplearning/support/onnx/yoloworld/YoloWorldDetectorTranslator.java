package com.chua.deeplearning.support.onnx.yoloworld;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator;
import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * YOLO-World zero-shot detector with 打开-vocabulary 支持.
 * 
 * <p>Uses ClipTextFeatureTranslator to generate CLIP text embeddings at runtime.</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @param raw raw
 * @return 解析类的结果
 * @param ctx ctx
 */
@Slf4j
public class YoloWorldDetectorTranslator implements Translator<Image, DetectedObjects> {

    public static final String[] COCO_80 = {
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

    private static final double DEFAULT_THRESHOLD = 0.25; // 默认阈值
    private static final double NOISE_AREA_RATIO = 0.70; // NOISE_AREA_RATIO
    private static final double NOISE_SCORE_LIMIT = 0.45; // noisescore限制
    private static final double DEFAULT_NMS_THRESHOLD = 0.45; // 默认nms阈值
    private static final int DEFAULT_INPUT_SIZE = 640; // 默认输入大小

    private final List<String> customClasses; // 习俗类
    private final double threshold; // 阈值
    private final double nmsThreshold; // nms阈值
    private final int inputSize; // 输入大小
    /**
     * yoloworlddetectortranslator。
     */
    
    private ClipTextFeatureTranslator clipTextTranslator; // clip文本translator
    /**
     * yoloworlddetectortranslator。
     * @param config 配置
     * @param raw raw
     * @param ctx ctx
     * @return 解析类的结果
     */

    public YoloWorldDetectorTranslator() { this(null); }

    /**
     * 构造方法，创建 YoloWorldDetectorTranslator 实例。
     *
     * @param config 配置，不允许为 null
     */
    public YoloWorldDetectorTranslator(Map<String, ?> config) {
        this.threshold = readDouble(config, "threshold", DEFAULT_THRESHOLD);
        this.nmsThreshold = readDouble(config, "iouThreshold", DEFAULT_NMS_THRESHOLD);
        this.inputSize = readInt(config, "inputSize", DEFAULT_INPUT_SIZE);
        this.customClasses = parseClasses(readArgument(config, "classes"));
    }

    @Override
    public void prepare(TranslatorContext ctx) throws Exception {

        
        if (customClasses.isEmpty()) {
            log.info("[YOLO-World] COCO-80 classes, threshold={}, iou={}", threshold, nmsThreshold);
        } else {
            log.info("[YOLO-World] Custom classes: {}", customClasses);
        }
    }

    /**
     * 解析Classes。
     *
     * @param raw 方法入参 raw
     * @return 结果列表，无数据时为空列表
     */
    private static List<String> parseClasses(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> classes = new ArrayList<>();
        for (String s : raw.split("[,，]")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                classes.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return classes;
    }

    /**
     * Generate CLIP 文本 嵌入 for the given 类.
     * @param ctx ctx
     * @param classes 类
     * @return generate文本嵌入的结果
     */
    private NDArray generateTextEmbeddings(TranslatorContext ctx, List<String> classes) throws Exception {
        NDManager manager = ctx.getNDManager();
        int numClasses = classes.isEmpty() ? 80 : classes.size();
        float[][] embeds = new float[numClasses][];
        
        for (int i = 0; i < numClasses; i++) {
            String className = classes.isEmpty() ? COCO_80[i] : classes.get(i);
            NDList input = clipTextTranslator.processInput(ctx, className);
            float[] emb = clipTextTranslator.processOutput(ctx, input);
            if (emb != null) {
                embeds[i] = emb;
            } else {
 // 降级 转为 zero 向量
                embeds[i] = new float[512];
            }
        }
        
 // Normalize 嵌入
        for (int i = 0; i < embeds.length; i++) {
            float norm = 0f;
            for (float v : embeds[i]) {
                norm += v * v;
            }
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int j = 0; j < embeds[i].length; j++) {
                    embeds[i][j] /= norm;
                }
            }
        }
        
        float[] flat = new float[numClasses * 512];
        for (int i = 0; i < numClasses; i++) {
            System.arraycopy(embeds[i], 0, flat, i * 512, 512);
        }
        
        return manager.create(flat, new Shape(1, numClasses, 512));
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();
        BufferedImage original = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = letterbox(original, inputSize, inputSize);
        ai.djl.modality.cv.Image djlImg = ai.djl.modality.cv.ImageFactory.getInstance().fromImage(resized);
        NDArray array = djlImg.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = array.toType(DataType.FLOAT32, false);
        Shape shape = array.getShape();
        int h = Math.toIntExact(shape.get(0)), w = Math.toIntExact(shape.get(1)), c = Math.toIntExact(shape.get(2));
        float[] hw = array.toFloatArray();
        float[] chw = new float[h * w * c];
        int plane = h * w;
        for (int i = 0; i < hw.length; i++) {
            hw[i] *= 1.0f / 255.0f;
        }
        for (int hi = 0; hi < h; hi++) {
            for (int wi = 0; wi < w; wi++) {
                int hwIdx = hi * w + wi;
                for (int ci = 0; ci < c; ci++) {
                    chw[ci * plane + hwIdx] = hw[hwIdx * c + ci];
                }
            }
        }
        NDArray images = ctx.getNDManager().create(chw, new Shape(1, c, h, w));
        images.setName("images");
        
 // Generate CLIP 文本 嵌入
        List<String> classes = customClasses.isEmpty() ? 
            Arrays.asList(COCO_80) : customClasses;
        NDArray txtFeats = generateTextEmbeddings(ctx, classes);
        txtFeats.setName("txt_feats");
        
        NDList result = new NDList(images, txtFeats);
        return result;
    }

    /** original宽度 */
    private int originalWidth;
    /** original高度 */
    private int originalHeight;
    /** letterPadX */
    private int letterPadX;
    /** letterPadY */
    private int letterPadY;
    /** letterScale */
    private float letterScale;

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
        Shape shape = output.getShape();
        int numClasses = (int) shape.get(1) - 4;
        int numAnchors = (int) shape.get(2);
        String[] labels = customClasses.isEmpty() ? COCO_80 : buildCustomLabels(numClasses);
        float[] data = output.toFloatArray();
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (int i = 0; i < numAnchors; i++) {
            float bestLogit = Float.NEGATIVE_INFINITY;
            int bestClass = -1;
            for (int c = 0; c < numClasses; c++) {
                float s = data[(4 + c) * numAnchors + i];
                if (s > bestLogit) {
                    bestLogit = s;
                    bestClass = c;
                }
            }
            if (bestLogit < threshold || bestClass < 0) {
                continue;
            }
            String label = labels[bestClass % labels.length];
            if (!customClasses.isEmpty() && !customClasses.contains(label.toLowerCase(Locale.ROOT))) {
                continue;
            }
            float cx = data[0 * numAnchors + i], cy = data[1 * numAnchors + i];
            float bw = data[2 * numAnchors + i], bh = data[3 * numAnchors + i];
            if (bw <= 0 || bh <= 0) {
                continue;
            }
            float x1 = (cx - bw/2 - letterPadX) / letterScale;
            float y1 = (cy - bh/2 - letterPadY) / letterScale;
            float x2 = (cx + bw/2 - letterPadX) / letterScale;
            float y2 = (cy + bh/2 - letterPadY) / letterScale;
            x1 = Math.max(0, Math.min(originalWidth, x1));
            y1 = Math.max(0, Math.min(originalHeight, y1));
            x2 = Math.max(0, Math.min(originalWidth, x2));
            y2 = Math.max(0, Math.min(originalHeight, y2));
            float ww = x2 - x1, hh = y2 - y1;
            if (ww <= 0 || hh <= 0) {
                continue;
            }
            double areaRatio = (double) ww * hh / ((double) originalWidth * originalHeight);
            if (bestLogit < NOISE_SCORE_LIMIT && areaRatio > NOISE_AREA_RATIO) {
                continue;
            }
            boxes.add(new Rectangle(x1/originalWidth, y1/originalHeight, ww/originalWidth, hh/originalHeight));
            names.add(label);
            probs.add((double) bestLogit);
        }
        List<Integer> keep = nms(boxes, probs, nmsThreshold);
        List<String> fn = new ArrayList<>(), fp = new ArrayList<>();
        List<BoundingBox> fb = new ArrayList<>();
        for (int idx : keep) {
            fn.add(names.get(idx));
            probs.add(probs.get(idx));
            fb.add(boxes.get(idx));
        }
        log.info("[YOLO-World] {} -> {} boxes", names.size(), fn.size());
        return new DetectedObjects(fn, probs, fb);
    }

    /**
     * 构建习俗标签。
     * @param numClasses num类
     * @return 构建习俗标签的结果
     */
    private String[] buildCustomLabels(int numClasses) {
        String[] labels = new String[Math.max(numClasses, customClasses.size())];
        for (int i = 0; i < customClasses.size(); i++) {
            labels[i] = customClasses.get(i);
        }
        for (int i = customClasses.size(); i < labels.length; i++) {
            labels[i] = "class_" + i;
        }
        return labels;
    }

    @Override public Batchifier getBatchifier() { return null; }

    private List<Integer> nms(List<BoundingBox> boxes, List<Double> scores, double iouTh) {
        List<Integer> keep = new ArrayList<>();
        if (boxes.isEmpty()) {
            return keep;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        boolean[] suppressed = new boolean[boxes.size()];
        for (int idx : order) {
            if (suppressed[idx]) {
                continue;
            }
            keep.add(idx);
            Rectangle r1 = boxes.get(idx).getBounds();
            double a1 = r1.getWidth() * r1.getHeight();
            for (int j = 0; j < boxes.size(); j++) {
                if (j == idx || suppressed[j]) {
                    continue;
                }
                Rectangle r2 = boxes.get(j).getBounds();
                double ix1 = Math.max(r1.getX(), r2.getX()), iy1 = Math.max(r1.getY(), r2.getY());
                double ix2 = Math.min(r1.getX() + r1.getWidth(), r2.getX() + r2.getWidth());
                double iy2 = Math.min(r1.getY() + r1.getHeight(), r2.getY() + r2.getHeight());
                double inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
                double a2 = r2.getWidth() * r2.getHeight();
                if (inter / (a1 + a2 - inter + 1e-9) > iouTh) {
                    suppressed[j] = true;
                }
            }
        }
        return keep;
    }

    /**
     * letterbox。
     * @param src src
     * @param tw tw
     * @param th th
     * @return letterbox的结果
     */
    private BufferedImage letterbox(BufferedImage src, int tw, int th) {
        int sw = src.getWidth(), sh = src.getHeight();
        float scale = Math.min((float) tw / sw, (float) th / sh);
        int nw = Math.round(sw * scale), nh = Math.round(sh * scale);
        BufferedImage padded = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(114, 114, 114));
        g.fillRect(0, 0, tw, th);
        int px = (tw - nw) / 2, py = (th - nh) / 2;
        this.letterPadX = px;
        this.letterPadY = py;
        this.letterScale = scale;
        g.drawImage(src, px, py, nw, nh, null);
        g.dispose();
        return padded;
    }

    /**
     * 读取参数。
     * @param args 参数
     * @param key 键
     * @return 读取参数的结果
     */
    private static String readArgument(Map<String, ?> args, String key) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }
    /**
     * 读取double。
     * @param args 参数
     * @param key 键
     * @param d d
     * @return 读取double的结果
     */
    private static double readDouble(Map<String, ?> args, String key, double d) {
        String v = readArgument(args, key);
        if (v == null || v.trim().isEmpty()) {
            return d;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return d;
        }
    }
    /**
     * 读取int。
     * @param args 参数
     * @param key 键
     * @param d d
     * @return 读取int的结果
     */
    private static int readInt(Map<String, ?> args, String key, int d) {
        String v = readArgument(args, key);
        if (v == null || v.trim().isEmpty()) {
            return d;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return d;
        }
    }
}
