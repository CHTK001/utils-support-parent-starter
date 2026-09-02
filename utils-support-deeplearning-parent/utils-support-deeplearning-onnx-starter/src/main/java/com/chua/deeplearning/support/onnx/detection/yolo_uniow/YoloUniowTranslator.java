package com.chua.deeplearning.support.onnx.detection.yolo_uniow;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * YOLO-UniOW 通用开世界目标检测（Open-World Object Detection）Translator。
 *
 * <p>YOLO-UniOW（清华 THU-MIG，arxiv 2412.20645）基于 YOLO-World + YOLOv10，
 * 支持开世界动态词表检测。ONNX 部署模型输入 {@code images(1,3,640,640)} +
 * {@code text_features(1,N,512)}（类别文本嵌入，需与模型文本编码器一致），
 * 输出 3 个尺度特征图 {@code (1,H,W, 4*reg_max + N)}——前 64 通道为 bbox
 * DFL 分布 logits（reg_max=16，4 边×16 bin），后 N 通道为类别 logits。</p>
 *
 * <p>解码流程与 AXERA-TECH/YOLO-UniOW（easydeploy 部署）保持一致：
 * DFL softmax 积分 → dist2bbox（ltrb → xywh，anchor 中心网格偏移 0.5）→
 * 乘以 stride 还原像素 → sigmoid 类别分数 → 阈值过滤 → NMS。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class YoloUniowTranslator implements Translator<Image, DetectedObjects> {

    private static final Logger log = LoggerFactory.getLogger(YoloUniowTranslator.class);

    /** 输入尺寸 */
    private static final int INPUT_SIZE = 640;

    /** 默认置信度阈值 */
    private static final float DEFAULT_THRESHOLD = 0.25f;

    /** 默认 NMS IoU 阈值 */
    private static final float DEFAULT_NMS = 0.5f;

    /** DFL 积分 bin 数 */
    private static final int REG_MAX = 16;

    /** 特征图 stride（640 输入：80/40/20 网格） */
    private static final int[] STRIDES = {8, 16, 32};

    /** 文本嵌入 classpath 资源路径（LVIS 1203 类，1203×512） */
    private static final String EMBEDDINGS_RESOURCE =
            "vision/detection/yolo_uniow/class_embeddings_1203x512.f32";

    /** 类别名 classpath 资源路径 */
    private static final String CLASS_NAMES_RESOURCE =
            "vision/detection/yolo_uniow/class.names.txt";

    /** 回退类别 */
    private static final List<String> DEFAULT_CLASSES = List.of("dog", "horse", "sheep", "cow");

    private final float threshold;
    private final float nmsThreshold;
    private final List<String> classes;
    private float[] textFeatures;

    /** 原始图像尺寸（用于结果坐标还原） */
    private int imageWidth;
    private int imageHeight;

    /** 创建 YoloUniowTranslator 实例（默认阈值 0.25 / NMS 0.5） */
    public YoloUniowTranslator() {
        this(DEFAULT_THRESHOLD, DEFAULT_NMS);
    }

    /**
     * 创建 YoloUniowTranslator 实例。
     *
     * @param threshold    置信度阈值
     * @param nmsThreshold NMS IoU 阈值
     */
    public YoloUniowTranslator(float threshold, float nmsThreshold) {
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = Collections.unmodifiableList(loadClassNames());
        this.textFeatures = loadEmbeddings(EMBEDDINGS_RESOURCE);
        log.info("YoloUniowTranslator 初始化: classes={} ({} 类), 文本特征维度 {}",
                classes, classes.size(), textFeatures.length);
    }

    /**
     * 指定外部嵌入文件（覆盖 classpath 资源）。
     *
     * @param path 嵌入文件路径（float32 数组，N×512）
     * @return this
     */
    public YoloUniowTranslator embeddings(Path path) {
        this.textFeatures = loadEmbeddings(path);
        return this;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();

        // 与部署参考（AXERA cv2.resize 直接拉伸）保持一致：不保比例
        Image resized = input.resize(INPUT_SIZE, INPUT_SIZE, false);
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        NDArray images = toNormalizedChw(ctx, array);

        int numClasses = classes.size();
        if (textFeatures.length < numClasses * 512) {
            throw new IllegalStateException("文本嵌入长度不足: " + textFeatures.length);
        }
        NDArray feats = ctx.getNDManager().create(textFeatures,
                new Shape(1, numClasses, 512));
        return new NDList(images, feats);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (list.size() < 3) {
            throw new IllegalArgumentException("YOLO-UniOW 输出应为 3 个尺度特征图, actual=" + list.size());
        }
        int numClasses = classes.size();
        int expectedChannels = 4 * REG_MAX + numClasses;
        int totalAnchors = 0;
        for (NDArray out : list) {
            Shape s = out.getShape();
            totalAnchors += (int) (s.get(1) * s.get(2));
        }

        float[] clsData = new float[totalAnchors * numClasses];
        float[] boxData = new float[totalAnchors * 4];
        int anchorOffset = 0;

        for (int level = 0; level < 3; level++) {
            NDArray out = list.get(level).toType(DataType.FLOAT32, false);
            Shape s = out.getShape();
            int h = (int) s.get(1);
            int w = (int) s.get(2);
            int stride = STRIDES[level];
            int numAnchors = h * w;
            int channels = (int) s.get(3);
            if (channels != expectedChannels) {
                out.close();
                throw new IllegalArgumentException("YOLO-UniOW 输出通道不匹配: expected="
                        + expectedChannels + ", actual=" + channels);
            }
            float[] data = out.toFloatArray();
            out.close();

            for (int idx = 0; idx < numAnchors; idx++) {
                int gx = idx % w;
                int gy = idx / w;
                float anchorCx = (gx + 0.5f) * stride;
                float anchorCy = (gy + 0.5f) * stride;
                int base = idx * channels;

                // DFL 积分：4 边 × 16 bin（softmax × bin 索引）
                float[] ltrbGrid = new float[4];
                for (int e = 0; e < 4; e++) {
                    int binBase = base + e * REG_MAX;
                    float maxLogit = Float.NEGATIVE_INFINITY;
                    for (int b = 0; b < REG_MAX; b++) {
                        maxLogit = Math.max(maxLogit, data[binBase + b]);
                    }
                    float sum = 0f;
                    float weighted = 0f;
                    for (int b = 0; b < REG_MAX; b++) {
                        float p = (float) Math.exp(data[binBase + b] - maxLogit);
                        sum += p;
                        weighted += p * b;
                    }
                    ltrbGrid[e] = sum > 0f ? weighted / sum : 0f;
                }

                // dist2bbox（grid 单元）→ xywh 像素：anchor 中心 ± ltrb，× stride
                float l = ltrbGrid[0], t = ltrbGrid[1], r = ltrbGrid[2], b = ltrbGrid[3];
                float cx = (anchorCx + (r - l) / 2f * stride);
                float cy = (anchorCy + (b - t) / 2f * stride);
                float bw = (l + r) * stride;
                float bh = (t + b) * stride;

                int ai = anchorOffset + idx;
                boxData[ai * 4] = cx;
                boxData[ai * 4 + 1] = cy;
                boxData[ai * 4 + 2] = bw;
                boxData[ai * 4 + 3] = bh;

                int clsBase = base + 4 * REG_MAX;
                for (int c = 0; c < numClasses; c++) {
                    float logit = data[clsBase + c];
                    clsData[ai * numClasses + c] = 1f / (1f + (float) Math.exp(-logit));
                }
            }
            anchorOffset += numAnchors;
        }
        return decodeDetections(clsData, boxData);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    // ==================== NMS 与结果组装 ====================

    /**
     * 按分数阈值过滤 + NMS，组装 DetectedObjects。
     */
    private DetectedObjects decodeDetections(float[] clsData, float[] boxData) {
        int numAnchors = clsData.length / classes.size();
        int numClasses = classes.size();
        float scaleX = (float) imageWidth / INPUT_SIZE;
        float scaleY = (float) imageHeight / INPUT_SIZE;

        List<Integer> candIdx = new ArrayList<>();
        List<Float> candScores = new ArrayList<>();
        List<Integer> candClasses = new ArrayList<>();
        for (int i = 0; i < numAnchors; i++) {
            float bestScore = 0f;
            int bestClass = 0;
            for (int c = 0; c < numClasses; c++) {
                float sc = clsData[i * numClasses + c];
                if (sc > bestScore) {
                    bestScore = sc;
                    bestClass = c;
                }
            }
            if (bestScore >= threshold) {
                candIdx.add(i);
                candScores.add(bestScore);
                candClasses.add(bestClass);
            }
        }
        if (candIdx.isEmpty()) {
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        Integer[] order = new Integer[candIdx.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Float.compare(candScores.get(b), candScores.get(a)));

        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        List<float[]> kept = new ArrayList<>();

        for (Integer oi : order) {
            int anchor = candIdx.get(oi);
            int cls = candClasses.get(oi);
            float score = candScores.get(oi);

            float cx = boxData[anchor * 4];
            float cy = boxData[anchor * 4 + 1];
            float bw = boxData[anchor * 4 + 2];
            float bh = boxData[anchor * 4 + 3];

            float x0 = Math.max(0, Math.min(imageWidth, (cx - bw / 2f) * scaleX));
            float y0 = Math.max(0, Math.min(imageHeight, (cy - bh / 2f) * scaleY));
            float x1 = Math.max(0, Math.min(imageWidth, (cx + bw / 2f) * scaleX));
            float y1 = Math.max(0, Math.min(imageHeight, (cy + bh / 2f) * scaleY));
            if (x1 <= x0 || y1 <= y0) {
                continue;
            }

            boolean suppressed = false;
            for (float[] k : kept) {
                if (iou(x0, y0, x1, y1, k[0], k[1], k[2], k[3]) > nmsThreshold) {
                    suppressed = true;
                    break;
                }
            }
            if (suppressed) {
                continue;
            }
            kept.add(new float[]{x0, y0, x1, y1});

            names.add(classes.get(cls));
            probs.add((double) score);
            boxes.add(new Rectangle(x0 / imageWidth, y0 / imageHeight,
                    (x1 - x0) / imageWidth, (y1 - y0) / imageHeight));
        }

        return new DetectedObjects(names, probs, boxes);
    }

    private static float iou(float ax0, float ay0, float ax1, float ay1,
                             float bx0, float by0, float bx1, float by1) {
        float ix0 = Math.max(ax0, bx0);
        float iy0 = Math.max(ay0, by0);
        float ix1 = Math.min(ax1, bx1);
        float iy1 = Math.min(ay1, by1);
        float inter = Math.max(0, ix1 - ix0) * Math.max(0, iy1 - iy0);
        float union = (ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter;
        return union > 0 ? inter / union : 0f;
    }

    // ==================== 资源加载 ====================

    /**
     * 从 classpath 加载类别名列表。
     */
    private List<String> loadClassNames() {
        List<String> result = new ArrayList<>();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CLASS_NAMES_RESOURCE)) {
            if (is == null) {
                log.warn("classpath 未找到 {}, 使用默认类别 {}", CLASS_NAMES_RESOURCE, DEFAULT_CLASSES);
                return new ArrayList<>(DEFAULT_CLASSES);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        result.add(line);
                    }
                }
            }
            if (result.isEmpty()) {
                log.warn("{} 为空, 使用默认类别", CLASS_NAMES_RESOURCE);
                return new ArrayList<>(DEFAULT_CLASSES);
            }
        } catch (IOException e) {
            log.warn("加载 {} 失败: {}, 使用默认类别", CLASS_NAMES_RESOURCE, e.getMessage());
            return new ArrayList<>(DEFAULT_CLASSES);
        }
        return result;
    }

    /**
     * 从 classpath 资源加载文本嵌入（float32 数组）。
     */
    private float[] loadEmbeddings(String resource) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("classpath 未找到文本嵌入资源: " + resource);
            }
            return readFloats(is);
        } catch (IOException e) {
            throw new IllegalStateException("加载文本嵌入失败: " + resource, e);
        }
    }

    /**
     * 从外部文件加载文本嵌入（float32 数组）。
     */
    private float[] loadEmbeddings(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            return readFloats(is);
        } catch (IOException e) {
            throw new IllegalStateException("加载文本嵌入文件失败: " + path, e);
        }
    }

    private static float[] readFloats(InputStream is) throws IOException {
        // 可靠读取全部字节（available() 在流上不可靠）
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        byte[] all = bos.toByteArray();
        if (all.length % 4 != 0) {
            throw new IOException("浮点数据长度不是 4 的倍数: " + all.length);
        }
        float[] arr = new float[all.length / 4];
        // numpy .tobytes() 平台小端（x86 little-endian）
        java.nio.ByteBuffer.wrap(all)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(arr);
        return arr;
    }

    /**
     * HWC(RGB) → CHW(BGR) 并归一化到 [0,1]。
     * <p>mmdet/YOLO 系模型训练输入为 BGR（OpenCV 惯例），
     * DJL {@code Image.toNDArray} 返回 RGB，需反转通道。</p>
     */
    private NDArray toNormalizedChw(TranslatorContext ctx, NDArray array) {
        Shape shape = array.getShape();
        if (shape.dimension() != 3) {
            throw new IllegalArgumentException("仅支持 HWC 格式, shape=" + shape);
        }
        int height = (int) shape.get(0);
        int width = (int) shape.get(1);
        int channels = (int) shape.get(2);
        if (channels != 3) {
            throw new IllegalArgumentException("仅支持 3 通道图像, channels=" + channels);
        }
        float[] source = array.toType(DataType.FLOAT32, false).toFloatArray();
        float[] chw = new float[source.length];
        int planeSize = height * width;
        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                int hwOffset = h * width + w;
                int sourceOffset = hwOffset * channels;
                // RGB -> BGR 通道反转
                chw[0 * planeSize + hwOffset] = source[sourceOffset + 2] / 255.0f;
                chw[1 * planeSize + hwOffset] = source[sourceOffset + 1] / 255.0f;
                chw[2 * planeSize + hwOffset] = source[sourceOffset + 0] / 255.0f;
            }
        }
        return ctx.getNDManager().create(chw, new Shape(1, channels, height, width));
    }
}
