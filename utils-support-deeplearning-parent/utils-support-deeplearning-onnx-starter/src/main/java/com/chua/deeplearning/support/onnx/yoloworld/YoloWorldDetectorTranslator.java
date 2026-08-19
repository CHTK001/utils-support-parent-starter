package com.chua.deeplearning.support.onnx.yoloworld;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
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
import com.chua.common.support.utils.StringUtils;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * YOLO-World 开放词表检测器（嵌入式友好）。
 *
 * <p>支持文本提示（中/英文）的零样本目标检测，模型体积小（YOLOv8s ~23MB，YOLOv8l ~88MB），
 * 适合嵌入式/边缘部署。</p>
 *
 * <p>输入格式（端到端 ONNX 导出）：</p>
 * <ul>
 *   <li>images: [1, 3, 640, 640] float32（归一化后 RGB）</li>
 *   <li>input_ids: [num_classes, max_token_len] int64</li>
 *   <li>attention_mask: [num_classes, max_token_len] int64</li>
 * </ul>
 *
 * <p>输出格式：pred_boxes [1, num_boxes, 4] + pred_scores [1, num_boxes, num_classes]</p>
 *
 * <p>候选类别通过 {@code candidates} 参数指定（逗号分隔），支持中英文混合。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // 方式 1：SPI
 * ImageDetector det = ImageDetector.create("yolov8s-world", "");
 *
 * // 方式 2：引擎 + 参数
 * ModelSetting s = ModelSetting.builder()
 *     .argument("candidates", "人,汽车,自行车,猫,狗")
 *     .build();
 * ImageDetector det = ImageDetector.create("yolov8s-world", s);
 * DetectionInfo info = det.detect(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class YoloWorldDetectorTranslator implements Translator<Image, DetectedObjects> {

    /** 默认候选列表 */
    private static final List<String> DEFAULT_CANDIDATES = List.of("person", "car", "bicycle", "cat", "dog");

    /** 默认阈值 */
    private static final double DEFAULT_THRESHOLD = 0.10;

    /** 默认 NMS 阈值 */
    private static final double DEFAULT_NMS_THRESHOLD = 0.50;

    /** 默认输入尺寸 */
    private static final int DEFAULT_INPUT_SIZE = 640;

    /** 默认最大 token 长度 */
    private static final int DEFAULT_MAX_TOKEN_LEN = 77;

    /** 分词器 */
    private HuggingFaceTokenizer tokenizer;

    /** 请求的候选类别列表（可能中英文混合） */
    private final List<String> requestedCandidates;

    /** 阈值 */
    private final double threshold;

    /** NMS 阈值 */
    private final double nmsThreshold;

    /** 输入尺寸 */
    private final int inputSize;

    /** 候选输入标识 */
    private long[][] candidateInputIds;

    /** 候选注意力掩码 */
    private long[][] candidateAttentionMasks;

    /** 候选输出标签（最终映射） */
    private List<String> candidateOutputLabels;

    /** 原始宽度 */
    private int originalWidth;

    /** 原始高度 */
    private int originalHeight;

    /**
     * 默认构造函数。
     */
    public YoloWorldDetectorTranslator() {
        this(DetectionConfiguration.DEFAULT);
    }

    /**
     * 构造函数，指定检测配置。
     *
     * @param configuration 检测配置
     */
    public YoloWorldDetectorTranslator(DetectionConfiguration configuration) {
        DetectionConfiguration cfg = configuration == null ? DetectionConfiguration.DEFAULT : configuration;
        this.threshold = readDouble(cfg.systemOption(), "threshold", DEFAULT_THRESHOLD);
        this.nmsThreshold = readDouble(cfg.systemOption(), "iouThreshold", DEFAULT_NMS_THRESHOLD);
        this.inputSize = readInt(cfg.systemOption(), "inputSize", DEFAULT_INPUT_SIZE);
        this.requestedCandidates = parseCandidates(readArgument(cfg.systemOption(), "candidates"));
    }

    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());

        // 加载 tokenizer（支持中英文多语言）
        Path tokenizerPath = resolveFile(modelRoot, "tokenizer.json");
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optPadding(true)
                .optMaxLength(DEFAULT_MAX_TOKEN_LEN)
                .build();

        // 解析候选类别
        candidateOutputLabels = requestedCandidates.isEmpty() ? DEFAULT_CANDIDATES : requestedCandidates;

        log.info("[YOLO-World] 候选类别: {}", candidateOutputLabels);
        log.info("[YOLO-World] 阈值: {}, NMS: {}, 输入尺寸: {}", threshold, nmsThreshold, inputSize);

        // 预编码候选类别
        buildTextInputs();
    }

    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();
        NDManager manager = ctx.getNDManager();

        // 输入 token ids（形状 [num_classes, max_token_len]）
        NDArray inputIds = manager.create(flattenLong(candidateInputIds))
                .reshape(candidateInputIds.length, candidateInputIds[0].length);
        inputIds.setName("input_ids");

        // 注意力掩码
        NDArray attentionMask = manager.create(flattenLong(candidateAttentionMasks))
                .reshape(candidateAttentionMasks.length, candidateAttentionMasks[0].length);
        attentionMask.setName("attention_mask");
        // 图像预处理：letterbox resize + normalize
        NDArray pixelValues = preprocessImage(ctx, input);
        pixelValues.setName("pixel_values");

        return new NDList(inputIds, attentionMask, pixelValues);
    }

    @Override
    public DetectedObjects processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        // YOLO-World 端到端输出：pred_boxes [1, N, 4] + pred_scores [1, N, num_classes]
        NDArray predBoxes = null;
        NDArray predScores = null;

        for (NDArray array : list) {
            if (array == null) {
                continue;
            }
            Shape shape = array.getShape();
            if (shape.dimension() == 3) {
                long lastDim = shape.get(2);
                if (lastDim == 4 && predBoxes == null) {
                    predBoxes = array;
                } else if (lastDim > 4 && predScores == null) {
                    predScores = array;
                }
            }
        }

        if (predBoxes == null || predScores == null) {
            log.warn("[YOLO-World] 输出格式不识别");
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        float[] boxesData = predBoxes.toFloatArray();
        float[] scoresData = predScores.toFloatArray();

        Shape boxesShape = predBoxes.getShape();
        Shape scoresShape = predScores.getShape();

        int numBoxes = Math.toIntExact(boxesShape.get(1));
        int numClasses = candidateOutputLabels.size();

        // boxes 数据布局: [1, numBoxes, 4] -> flatten 后 [numBoxes * 4]
        // scores 数据布局: [1, numBoxes, numClasses] -> flatten 后 [numBoxes * numClasses]
        float scaleX = (float) originalWidth / inputSize;
        float scaleY = (float) originalHeight / inputSize;

        List<String> classNames = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        for (int i = 0; i < numBoxes; i++) {
            // 读取 box: [x1, y1, x2, y2]（YOLO-World 格式）
            float x1 = boxesData[i * 4] * scaleX;
            float y1 = boxesData[i * 4 + 1] * scaleY;
            float x2 = boxesData[i * 4 + 2] * scaleX;
            float y2 = boxesData[i * 4 + 3] * scaleY;

            // 找最高类别
            int bestClass = -1;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < numClasses; c++) {
                float s = scoresData[i * numClasses + c];
                if (s > bestScore) {
                    bestScore = s;
                    bestClass = c;
                }
            }

            if (bestScore < threshold || bestClass < 0 || bestClass >= candidateOutputLabels.size()) {
                continue;
            }

            // clip 到原图范围
            x1 = Math.max(0, Math.min(originalWidth, x1));
            y1 = Math.max(0, Math.min(originalHeight, y1));
            x2 = Math.max(0, Math.min(originalWidth, x2));
            y2 = Math.max(0, Math.min(originalHeight, y2));

            float w = x2 - x1;
            float h = y2 - y1;
            if (w <= 0 || h <= 0) {
                continue;
            }

            // 转为 Rectangle（x, y, w, h 归一化 [0,1]）
            double rx = x1 / originalWidth;
            double ry = y1 / originalHeight;
            double rw = w / originalWidth;
            double rh = h / originalHeight;

            boxes.add(new Rectangle(rx, ry, rw, rh));
            classNames.add(candidateOutputLabels.get(bestClass));
            probabilities.add((double) bestScore);
        }

        // NMS
        List<Integer> keep = nms(boxes, probabilities, nmsThreshold);

        List<String> finalNames = new ArrayList<>();
        List<Double> finalProbs = new ArrayList<>();
        List<BoundingBox> finalBoxes = new ArrayList<>();

        for (int idx : keep) {
            finalNames.add(classNames.get(idx));
            finalProbs.add(probabilities.get(idx));
            finalBoxes.add(boxes.get(idx));
        }

        log.debug("[YOLO-World] 检测到 {} 个目标", finalNames.size());
        return new DetectedObjects(finalNames, finalProbs, finalBoxes);
    }

    @Override
    @javax.annotation.Nullable
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    // ==================== 工具方法 ====================

    /**
     * 将 long[][] 扁平化为 long[]。
     */
    private static long[] flattenLong(long[][] array) {
        int rows = array.length;
        if (rows == 0) {
            return new long[0];
        }
        int cols = array[0].length;
        long[] result = new long[rows * cols];
        for (int i = 0; i < rows; i++) {
            System.arraycopy(array[i], 0, result, i * cols, cols);
        }
        return result;
    }

    // ==================== 文本编码 ====================

    /**
     * 预编码候选类别为 token ids。
     */
    private void buildTextInputs() {
        int n = candidateOutputLabels.size();
        long[][] ids = new long[n][DEFAULT_MAX_TOKEN_LEN];
        long[][] masks = new long[n][DEFAULT_MAX_TOKEN_LEN];

        for (int i = 0; i < n; i++) {
            String label = candidateOutputLabels.get(i);
            // YOLO-World 使用 "object: label" 格式作为 prompt
            String prompt = "object: " + label;
            Encoding encoding = tokenizer.encode(prompt);

            long[] tokenIds = encoding.getIds();
            long[] mask = encoding.getAttentionMask();

            for (int j = 0; j < Math.min(tokenIds.length, DEFAULT_MAX_TOKEN_LEN); j++) {
                ids[i][j] = tokenIds[j];
                masks[i][j] = mask[j];
            }
        }

        this.candidateInputIds = ids;
        this.candidateAttentionMasks = masks;
    }

    // ==================== 图像预处理 ====================

    /**
     * Letterbox resize + normalize。
     */
    private NDArray preprocessImage(TranslatorContext ctx, Image input) {
        BufferedImage original = (BufferedImage) input.getWrappedImage();

        // Letterbox resize
        BufferedImage resized = letterbox(original, inputSize, inputSize);

        // 转 NDArray [H, W, 3]
        NDArray array = ImageFactory.getInstance().fromImage(resized)
                .toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        // 归一化到 [0, 1]
        array = array.toType(DataType.FLOAT32, false).div(255.0f);

        // HWC -> CHW + expand batch
        Shape shape = array.getShape();
        int h = Math.toIntExact(shape.get(0));
        int w = Math.toIntExact(shape.get(1));
        int c = Math.toIntExact(shape.get(2));
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

        return ctx.getNDManager().create(chw, new Shape(1, c, h, w));
    }

    /**
     * Letterbox resize（保持宽高比，灰色填充）。
     */
    private BufferedImage letterbox(BufferedImage src, int targetW, int targetH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        float scale = Math.min((float) targetW / srcW, (float) targetH / srcH);
        int newW = Math.round(srcW * scale);
        int newH = Math.round(srcH * scale);

        BufferedImage padded = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(114, 114, 118)); // YOLO 默认灰蓝色填充
        g.fillRect(0, 0, targetW, targetH);
        int padX = (targetW - newW) / 2;
        int padY = (targetH - newH) / 2;
        g.drawImage(src, padX, padY, newW, newH, null);
        g.dispose();
        return padded;
    }

    // ==================== 工具方法 ====================

    /**
     * 将二维 long 数组展平为一维。
     *
     * @param data 二维 long 数组
     * @return 一维 long 数组
     */
    private static long[] flattenLong(long[][] data) {
        long[] flat = new long[data.length * data[0].length];
        int idx = 0;
        for (long[] row : data) {
            System.arraycopy(row, 0, flat, idx, row.length);
            idx += row.length;
        }
        return flat;
    }

    // ==================== NMS ====================

    private List<Integer> nms(List<BoundingBox> boxes, List<Double> scores, float iouThreshold) {
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
            double area1 = r1.getWidth() * r1.getHeight();

            for (int j = idx + 1; j < boxes.size(); j++) {
                if (suppressed[j]) {
                    continue;
                }
                Rectangle r2 = boxes.get(j).getBounds();
                double ix1 = Math.max(r1.getX(), r2.getX());
                double iy1 = Math.max(r1.getY(), r2.getY());
                double ix2 = Math.min(r1.getX() + r1.getWidth(), r2.getX() + r2.getWidth());
                double iy2 = Math.min(r1.getY() + r1.getHeight(), r2.getY() + r2.getHeight());
                double inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
                double area2 = r2.getWidth() * r2.getHeight();
                double iou = inter / (area1 + area2 - inter);
                if (iou > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return keep;
    }

    // ==================== 工具方法 ====================

    private static List<String> parseCandidates(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String item : raw.split("[,，、]")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String readArgument(Map<String, ?> args, String key) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static double readDouble(Map<String, ?> args, String key, double defaultVal) {
        String v = readArgument(args, key);
        if (StringUtils.isBlank(v)) {
            return defaultVal;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static int readInt(Map<String, ?> args, String key, int defaultVal) {
        String v = readArgument(args, key);
        if (StringUtils.isBlank(v)) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Path.of(".");
        }
        return Files.isRegularFile(modelPath) ? modelPath.getParent() : modelPath;
    }

    private static Path resolveFile(Path root, String fileName) throws IOException {
        if (root == null) {
            throw new IOException("模型根路径为空");
        }
        Path file = root.resolve(fileName);
        if (Files.exists(file)) {
            return file;
        }
        // 尝试上级目录
        Path parent = root.getParent();
        if (parent != null) {
            Path parentFile = parent.resolve(fileName);
            if (Files.exists(parentFile)) {
                return parentFile;
            }
        }
        throw new IOException("文件不存在: " + file);
    }
}
