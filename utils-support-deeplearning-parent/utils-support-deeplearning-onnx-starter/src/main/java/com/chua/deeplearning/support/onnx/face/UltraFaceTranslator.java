package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UltraFace/UltraLight        ONNX Translator   
 *
 * <p>             `face_detection_sdk`        `slim / RFB / mobilenet`                            
 *                 raw boxes + class scores (+ landmarks)             anchor                         
 * output0     scores          </p>
 *
 * @author CH
 * @since 2025-01-20
 */
public class UltraFaceTranslator implements Translator<Image, DetectedObjects> {

    /** 默认方差数组 */
    /** Default_variance */
    private static final double[] DEFAULT_VARIANCE = {0.1d, 0.2d};
    /** BGR 通道均值 */
    /** Bgr_mean */
    private static final float[] BGR_MEAN = {104f, 117f, 123f};

    /** 置信度阈值 */
    /** Confthresh */
    private final double confThresh;
    /** NMS 阈值 */
    /** NMSthresh */
    private final double nmsThresh;
    /** Top-K 采样数量 */
    /** 顶部K */
    private final int topK;
    /** 输入宽度 */
    private final int inputWidth;
    /** 输入高度 */
    private final int inputHeight;
    /** 方差数组 */
    /** Variance */
    private final double[] variance;
    /** 缩放系数数组 */
    /** Scales */
    private final int[][] scales;
    /** 步数数组 */
    /** Steps */
    private final int[] steps;

    /** 创建 UltraFaceTranslator 实例 */
    public UltraFaceTranslator() {
        this((String) null);
    }

    /**
     * 创建 UltraFaceTranslator 实例
     * @param configuration configuration
     */
    public UltraFaceTranslator(DetectionConfiguration configuration) {
        this(configuration == null ? null : configuration.loadModelName());
    }

    /**
     * 创建 UltraFaceTranslator 实例
     * @param confThresh confThresh
     * @param double double
     */
    public UltraFaceTranslator(double confThresh, double nmsThresh) {
        this(confThresh, nmsThresh, "slim");
    }

    /**
     * 创建 UltraFaceTranslator 实例
     * @param confThresh confThresh
     * @param double double
     * @param int int
     * @param inputSize inputSize
     */
    public UltraFaceTranslator(double confThresh, double nmsThresh, int[] inputSize) {
        this(confThresh, nmsThresh, inputSize[0], inputSize[1], DEFAULT_VARIANCE,
                new int[][]{{10, 16, 24}, {32, 48}, {64, 96}, {128, 192, 256}},
                new int[]{8, 16, 32, 64}, 200);
    }

    /**
     * 创建 UltraFaceTranslator 实例
     * @param modelName modelName
     */
    private UltraFaceTranslator(String modelName) {
        this(0.2d, 0.3d, modelName);
    }

    /**
     * 创建 UltraFaceTranslator 实例
     * @param confThresh confThresh
     * @param double double
     * @param String String
     */
    private UltraFaceTranslator(double confThresh, double nmsThresh, String modelName) {
        String normalized = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        if ("scrfd_2_5g_bnkps".equals(normalized) || "scrfd_2.5g_bnkps".equals(normalized)
                || "scrfd_500m_bnkps".equals(normalized) || "scrfd_500m".equals(normalized)
                || "scrfd_1g_bnkps".equals(normalized) || "scrfd_1g".equals(normalized)) {
            this.confThresh = confThresh;
            this.nmsThresh = nmsThresh;
            this.topK = 200;
            this.inputWidth = 640;
            this.inputHeight = 640;
            this.variance = DEFAULT_VARIANCE;
            this.scales = new int[][]{{10, 16, 24}, {32, 48}, {64, 96}, {128, 192, 256}};
            this.steps = new int[]{8, 16, 32, 64};
            return;
        }
        if ("mobilenet".equals(normalized)) {
            this.confThresh = confThresh;
            this.nmsThresh = nmsThresh;
            this.topK = 200;
            this.inputWidth = 320;
            this.inputHeight = 240;
            this.variance = DEFAULT_VARIANCE;
            this.scales = new int[][]{{16, 32}, {64, 128}, {256, 512}};
            this.steps = new int[]{8, 16, 32};
            return;
        }

        this.confThresh = confThresh;
        this.nmsThresh = nmsThresh;
        this.topK = 200;
        this.inputWidth = 320;
        this.inputHeight = 240;
        this.variance = DEFAULT_VARIANCE;
        this.scales = new int[][]{{10, 16, 24}, {32, 48}, {64, 96}, {128, 192, 256}};
        this.steps = new int[]{8, 16, 32, 64};
    }

    /**
     * 创建 UltraFaceTranslator 实例
     * @param confThresh confThresh
     * @param nmsThresh nmsThresh
     * @param inputWidth inputWidth
     * @param inputHeight inputHeight
     * @param variance variance
     * @param scales scales
     * @param steps steps
     * @param topK topK
     */
    private UltraFaceTranslator(double confThresh, double nmsThresh, int inputWidth, int inputHeight,
                                double[] variance, int[][] scales, int[] steps, int topK) {
        this.confThresh = confThresh;
        this.nmsThresh = nmsThresh;
        this.topK = topK;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.variance = variance;
        this.scales = scales;
        this.steps = steps;
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        Object wrapped = input.getWrappedImage();
        if (!(wrapped instanceof java.awt.image.BufferedImage bufferedImage)) {
            throw new IllegalArgumentException("不支持的图像类型: " + wrapped.getClass().getName());
        }
        // AWT 缩放（ONNX Runtime 引擎的 NDArray 不支持 resize/transpose/flip）
        java.awt.image.BufferedImage resized = ImageUtils.resize(
                bufferedImage, inputWidth, inputHeight, org.opencv.imgproc.Imgproc.INTER_LINEAR);
        int[] rgb = resized.getRGB(0, 0, inputWidth, inputHeight, null, 0, inputWidth);

        // 模型输入为 BGR 归一化（减 BGR_MEAN），无 ImageNet/255 缩放（原生 onnx 用像素直接减）
        float[] data = new float[3 * inputWidth * inputHeight];
        int total = inputWidth * inputHeight;
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            // 通道顺序 BGR：b→ch0, g→ch1, r→ch2
            data[i] = (p & 0xff) - BGR_MEAN[0];
            data[i + total] = ((p >> 8) & 0xff) - BGR_MEAN[1];
            data[i + 2 * total] = ((p >> 16) & 0xff) - BGR_MEAN[2];
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, inputHeight, inputWidth));
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        if (list == null || list.size() < 2) {
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        // 直读 flat float[]，避免 ORT 引擎不支持的 squeeze（会递归 StackOverflow）
        NDArray rawBoxes = list.get(0);
        NDArray rawScores = list.get(1);
        long[] boxShape = rawBoxes.getShape().getShape();
        long[] scoreShape = rawScores.getShape().getShape();
        // 兼容 [1,N,4]/[N,4]/[N,4] 布局，取最大维作候选数
        int candidateCount = 1;
        for (long d : scoreShape) {
            candidateCount = Math.max(candidateCount, (int) d);
        }
        float[] boxArray = rawBoxes.toFloatArray();
        float[] scoreArray = rawScores.toFloatArray();
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        double[][] priors = boxRecover(inputWidth, inputHeight, scales, steps);

        for (int i = 0; i < candidateCount; i++) {
            double probability = scoreArray[i * 2 + 1];
            if (probability < confThresh) {
                continue;
            }

            double[] prior = priors[i];
            double x = boxArray[i * 4];
            double y = boxArray[i * 4 + 1];
            double w = boxArray[i * 4 + 2];
            double h = boxArray[i * 4 + 3];

            double decodedW = Math.exp(w * variance[1]) * prior[2];
            double decodedH = Math.exp(h * variance[1]) * prior[3];
            double decodedX = x * variance[0] * prior[2] + prior[0] - decodedW * 0.5d;
            double decodedY = y * variance[0] * prior[3] + prior[1] - decodedH * 0.5d;

            Rectangle rectangle = new Rectangle(
                    clip(decodedX),
                    clip(decodedY),
                    clipSize(decodedX, decodedW),
                    clipSize(decodedY, decodedH));

            if (rectangle.getWidth() <= 0d || rectangle.getHeight() <= 0d) {
                continue;
            }

            candidates.add(new Candidate(rectangle, probability));
        }

        candidates.sort((left, right) -> Double.compare(right.probability(), left.probability()));
        int limit = Math.min(candidates.size(), topK);
        for (int i = 0; i < limit; i++) {
            Candidate candidate = candidates.get(i);
            boolean keep = true;
            for (BoundingBox existing : boxes) {
                if (existing.getIoU(candidate.rectangle()) > nmsThresh) {
                    keep = false;
                    break;
                }
            }
            if (!keep) {
                continue;
            }
            names.add("face");
            probs.add(candidate.probability());
            boxes.add(candidate.rectangle());
        }
        return new DetectedObjects(names, probs, boxes);
    }

    /** SqueezeBatch */
    private NDArray squeezeBatch(NDArray array) {
        if (array != null && array.getShape().dimension() == 3 && array.getShape().get(0) == 1) {
            return array.squeeze(0);
        }
        return array;
    }

    /** BoxRecover */
    private double[][] boxRecover(int width, int height, int[][] scales, int[] steps) {
        List<double[]> defaultBoxes = new ArrayList<>();
        for (int index = 0; index < steps.length; index++) {
            int step = steps[index];
            int hRatio = (int) Math.ceil((double) height / step);
            int wRatio = (int) Math.ceil((double) width / step);
            for (int h = 0; h < hRatio; h++) {
                for (int w = 0; w < wRatio; w++) {
                    for (int scale : scales[index]) {
                        double skx = scale * 1.0d / width;
                        double sky = scale * 1.0d / height;
                        double cx = (w + 0.5d) * step / width;
                        double cy = (h + 0.5d) * step / height;
                        defaultBoxes.add(new double[]{cx, cy, skx, sky});
                    }
                }
            }
        }

        double[][] boxes = new double[defaultBoxes.size()][4];
        for (int i = 0; i < defaultBoxes.size(); i++) {
            boxes[i] = defaultBoxes.get(i);
        }
        return boxes;
    }

    /** Clip */
    private double clip(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    /** Clip获取大小 */
    private double clipSize(double origin, double size) {
        return Math.max(0d, Math.min(1d - clip(origin), size));
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        // ONNX Runtime 的 NDArray 不支持 Stack，单图推理不批处理
        return null;
    }

    /** Candidate */
    private record Candidate(Rectangle rectangle, double probability) {
    }
}

