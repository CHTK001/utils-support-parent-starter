package com.chua.deeplearning.support.onnx.detr;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import com.chua.deeplearning.support.ai.DetectionConfiguration;

/**
* D-罚金 实时目标检测 Translator（COCO 80 类）。
*
* <p>对应嵌入式权重 vision/detection/dfine_l_obj2coco/model_quantized.onnx
* （int8 动态量化，约 31MB）。输入 pixel_值[N,3,640,640]（仅 /255 缩放），
* 输出 logits[N,300,80] 与 pred_boxes[N,300,4](cxcywh, 归一化到输入图)。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DFineTranslator implements Translator<Image, DetectedObjects> {

    private static final int INPUT_SIZE = 640; // 输入大小
    private static final float SCORE_THRESHOLD = 0.5f; // score阈值

    /** COCO 80 类标准类别名 */
    private static final String[] COCO_LABELS = {

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
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
    };

    /** 外部阈值覆盖（-1 表示未配置，使用内置默认值）。 */
    private float thresholdOverride = -1f;

    /**
    * 取生效阈值。
    *
    * @param def def
    * @return eff阈值的结果
     */
    private float effThreshold(float def) {
        return thresholdOverride > 0 ? thresholdOverride : def;
    }

    private int width; // width
    private int height; // height

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDManager manager = ctx.getNDManager();

        BufferedImage wrapped = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = ImageUtils.resize(wrapped, INPUT_SIZE, INPUT_SIZE,
                java.awt.image.BufferedImage.SCALE_SMOOTH);
        float[] chw = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int idx = 0;
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    int rgb = resized.getRGB(x, y);
                    int v = (rgb >> (16 - 8 * c)) & 0xff;
                    chw[idx++] = v / 255.0f;
                }
            }
        }
        NDArray pixelValues = manager.create(chw, new ai.djl.ndarray.types.Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        pixelValues.setName("pixel_values");
        return new NDList(pixelValues);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = null;
        NDArray boxesNd = null;
        for (NDArray nd : list) {
            String n = nd.getName();
            if ("logits".equals(n)) {
                logits = nd;
            } else if ("pred_boxes".equals(n)) {
                boxesNd = nd;
            }
        }
        if (logits == null) {
            logits = list.get(0);
        }
        if (boxesNd == null) {
            boxesNd = list.size() > 1 ? list.get(1) : null;
        }

        float[] logitArr = logits.toFloatArray();
        long[] lshape = logits.getShape().getShape();
        int queries = (int) lshape[1];
        int classes = (int) lshape[2];
        float[] boxArr = boxesNd.toFloatArray();

        List<String> names = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<ai.djl.modality.cv.output.BoundingBox> boxes = new ArrayList<>();

        for (int q = 0; q < queries; q++) {
            int best = 0;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < classes; c++) {
                float s = sigmoid(logitArr[q * classes + c]);
                if (s > bestScore) {
                    bestScore = s;
                    best = c;
                }
            }
            if (bestScore < effThreshold(SCORE_THRESHOLD)) {
                continue;
            }
            float cx = boxArr[q * 4];
            float cy = boxArr[q * 4 + 1];
            float w = boxArr[q * 4 + 2];
            float h = boxArr[q * 4 + 3];

            // 归一化(相对 640 输入) -> 原图像素（非等比拉伸还原）
            float x1 = (cx - w / 2) * INPUT_SIZE * ((float) width / INPUT_SIZE);
            float y1 = (cy - h / 2) * INPUT_SIZE * ((float) height / INPUT_SIZE);
            float x2 = (cx + w / 2) * INPUT_SIZE * ((float) width / INPUT_SIZE);
            float y2 = (cy + h / 2) * INPUT_SIZE * ((float) height / INPUT_SIZE);

            String label = best < COCO_LABELS.length ? COCO_LABELS[best] : ("class_" + best);
            names.add(label);
            probabilities.add((double) bestScore);
            boxes.add(new Rectangle(
                    Math.max(0f, x1) / width,
                    Math.max(0f, y1) / height,
                    Math.min(x2 - x1, width - Math.max(0f, x1)) / width,
                    Math.min(y2 - y1, height - Math.max(0f, y1)) / height));
        }

        if (names.isEmpty()) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }
        return new DetectedObjects(names, probabilities, boxes);
    }

    /**
    * sigmoid。
    * @param x x
    * @return sigmoid的结果
     */
    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
    /**
    * 创建 Translator（支持外部阈值覆盖）。
    *
    * @param configuration 检测配置（可空）
     */
    public DFineTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        if (null != configuration) {
            float t = configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, -1f);
            if (t > 0) {
                this.thresholdOverride = t;
            }
        }
    }


}
