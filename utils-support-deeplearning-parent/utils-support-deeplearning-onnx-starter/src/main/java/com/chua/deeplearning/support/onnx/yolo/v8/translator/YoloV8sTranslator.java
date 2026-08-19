package com.chua.deeplearning.support.onnx.yolo.v8.translator;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.modality.cv.output.DetectedObjects;
import com.chua.deeplearning.support.utils.NMSUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * YOLOv8s COCO ͨ用Ŀ标检测 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class YoloV8sTranslator implements Translator<Image, DetectedObjects> {

    /** 类别名称列表 */
    /** Classes */
    public static final List<String> CLASSES = List.of(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
    );

    /** 输入尺寸 */
    /** Input_size */
    private static final int INPUT_SIZE = 640;
    /** 默认阈值 */
    /** Default_threshold */
    private static final float DEFAULT_THRESHOLD = 0.25f;
    /** 默认 NMS 阈值 */
    /** Default_nms_threshold */
    private static final float DEFAULT_NMS_THRESHOLD = 0.45f;

    /** 阈值 */
    private final float threshold;
    /** NMS 阈值 */
    /** NMS阈值 */
    private final float nmsThreshold;
    /** 类别名称列表 */
    /** Classes */
    private final List<String> classes;

    /** 图像宽度 */
    /** 图片宽度 */
    private int imageWidth;
    /** 图像高度 */
    /** 图片高度 */
    private int imageHeight;

    /** 创建 YoloV8sTranslator 实例 */
    public YoloV8sTranslator() {
        this(DEFAULT_THRESHOLD, DEFAULT_NMS_THRESHOLD, CLASSES);
    }

    /**
     * 创建 YoloV8sTranslator 实例
     * @param threshold threshold
     * @param float float
     * @param List List
     * @param classes classes
     */
    public YoloV8sTranslator(float threshold, float nmsThreshold, List<String> classes) {
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = new ArrayList<>(classes);
        System.out.println("YOLOv8s 初始化: input=" + INPUT_SIZE + ", threshold=" + threshold + ", nms=" + nmsThreshold + ", classes=" + classes.size());
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();

        Image resized = input.resize(INPUT_SIZE, INPUT_SIZE, true);
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = toNormalizedChw(ctx, array);

        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
        NDArray output = list.get(0);

        long batchSize = output.getShape().get(0);
        long f0 = output.getShape().get(1);
        long f1 = output.getShape().get(2);

        float[] data = output.toType(DataType.FLOAT32, false).toFloatArray();
        int stride0 = (int) (f1);
        int stride1 = 1;

        long numBoxes, numFeatures;
        boolean needTranspose = f0 < f1;
        if (needTranspose) {
            numBoxes = f1;
            numFeatures = f0;
        } else {
            numBoxes = f0;
            numFeatures = f1;
        }

        int expectedFeatures = 4 + classes.size();
        if (numFeatures != expectedFeatures) {
            System.out.println("Feature mismatch: actual=" + numFeatures + ", expected=" + expectedFeatures);
        }

        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<ai.djl.modality.cv.output.BoundingBox> boxes = new ArrayList<>();

        for (int i = 0; i < numBoxes; i++) {
            float cx, cy, w, h;
            if (needTranspose) {
                cx = data[i * (int) numFeatures + 0];
                cy = data[i * (int) numFeatures + 1];
                w = data[i * (int) numFeatures + 2];
                h = data[i * (int) numFeatures + 3];
            } else {
                cx = data[0 * (int) numBoxes + i];
                cy = data[1 * (int) numBoxes + i];
                w = data[2 * (int) numBoxes + i];
                h = data[3 * (int) numBoxes + i];
            }

            int classId = 0;
            float maxScore = 0f;
            for (int c = 0; c < classes.size() && (4 + c) < numFeatures; c++) {
                float score;
                if (needTranspose) {
                    score = data[i * (int) numFeatures + (4 + c)];
                } else {
                    score = data[(4 + c) * (int) numBoxes + i];
                }
                score = sigmoid(score);
                if (score > maxScore) {
                    maxScore = score;
                    classId = c;
                }
            }

            if (maxScore < threshold) {
                continue;
            }
            double x0 = cx - w / 2.0;
            double y0 = cy - h / 2.0;
            double x1 = cx + w / 2.0;
            double y1 = cy + h / 2.0;

            x0 = Math.max(0, Math.min(INPUT_SIZE, x0));
            y0 = Math.max(0, Math.min(INPUT_SIZE, y0));
            x1 = Math.max(0, Math.min(INPUT_SIZE, x1));
            y1 = Math.max(0, Math.min(INPUT_SIZE, y1));

            double scaleX = (double) imageWidth / INPUT_SIZE;
            double scaleY = (double) imageHeight / INPUT_SIZE;

            double origX = x0 * scaleX;
            double origY = y0 * scaleY;
            double origW = (x1 - x0) * scaleX;
            double origH = (y1 - y0) * scaleY;

            origX = Math.max(0, Math.min(imageWidth, origX));
            origY = Math.max(0, Math.min(imageHeight, origY));
            origW = Math.max(1, Math.min(imageWidth - origX, origW));
            origH = Math.max(1, Math.min(imageHeight - origY, origH));

            boxes.add(new ai.djl.modality.cv.output.Rectangle(origX, origY, origW, origH));
            names.add(classes.get(classId));
            probs.add((double) maxScore);
        }

        List<Integer> keep = NMSUtils.nms(boxes, probs, nmsThreshold);

        List<String> finalNames = new ArrayList<>();
        List<Double> finalProbs = new ArrayList<>();
        List<ai.djl.modality.cv.output.BoundingBox> finalBoxes = new ArrayList<>();

        for (int idx : keep) {
            finalNames.add(names.get(idx));
            finalProbs.add(probs.get(idx));
            finalBoxes.add(boxes.get(idx));
        }

        System.out.println("YOLOv8s done: " + boxes.size() + " boxes -> " + finalNames.size() + " (NMS)");
        return new DetectedObjects(finalNames, finalProbs, finalBoxes);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /** Sigmoid */
    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    /** ToNormalizedChw */
    private NDArray toNormalizedChw(TranslatorContext ctx, NDArray array) {
        Shape shape = array.getShape();
        if (shape.dimension() != 3) {
            throw new IllegalArgumentException("YOLO 仅支鎸?HWC 格式，shape=" + shape);
        }

        int height = (int) shape.get(0);
        int width = (int) shape.get(1);
        int channels = (int) shape.get(2);
        float[] source = array.toType(DataType.FLOAT32, false).toFloatArray();
        float[] chw = new float[source.length];
        int planeSize = height * width;

        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                int hwOffset = h * width + w;
                int sourceOffset = hwOffset * channels;
                for (int c = 0; c < channels; c++) {
                    chw[c * planeSize + hwOffset] = source[sourceOffset + c] / 255.0f;
                }
            }
        }

        return ctx.getNDManager().create(chw, new Shape(1, channels, height, width));
    }
}
