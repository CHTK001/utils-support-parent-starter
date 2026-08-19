package com.chua.deeplearning.support.tensorflow.detection;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TensorFlow Object Detection SavedModel Translator。
 * <p>输入 [1,H,W,C] UINT8；解析 detection_boxes / scores / classes。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SavedModelObjectDetectionTranslator implements NoBatchifyTranslator<Image, DetectedObjects> {

    /**
     * 默认最大检测框数
     */
    private static final int DEFAULT_MAX_BOXES = 10;

    /**
     * 默认置信度阈值
     */
    private static final float DEFAULT_THRESHOLD = 0.5f;

    /**
     * 默认输入图像边长（像素）
     */
    private static final int DEFAULT_INPUT_SIZE = 640;

    /**
     * COCO 数据集默认类别映射
     */
    private static final Map<Integer, String> DEFAULT_COCO_CLASSES = defaultCocoClasses();

    /**
     * 类别映射。
     */
    private final Map<Integer, String> classes;

    /**
     * 最大框数。
     */
    private final int maxBoxes;

    /**
     * 置信度阈值。
     */
    private final float threshold;

    /**
     * 输入边长。
     */
    private final int inputSize;

    /**
     * 构造 Translator，使用默认参数。
     */
    public SavedModelObjectDetectionTranslator() {
        this(DEFAULT_MAX_BOXES, DEFAULT_THRESHOLD, DEFAULT_INPUT_SIZE, DEFAULT_COCO_CLASSES);
    }

    /**
     * 构造 Translator。
     *
     * @param maxBoxes  最大检测框数
     * @param threshold 置信度阈值
     * @param inputSize 输入图像边长（像素）
     * @param classes   类别 ID 到名称的映射
     */
    public SavedModelObjectDetectionTranslator(int maxBoxes, float threshold, int inputSize,
                                               Map<Integer, String> classes) {
        this.maxBoxes = maxBoxes;
        this.threshold = threshold;
        this.inputSize = inputSize;
        this.classes = classes == null ? DEFAULT_COCO_CLASSES : classes;
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, inputSize, inputSize);
        array = array.toType(DataType.UINT8, true);
        array = array.expandDims(0);
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        int[] classIds = null;
        float[] probabilities = null;
        NDArray boundingBoxes = null;
        for (NDArray array : list) {
            String name = array.getName();
            if (name == null) {
                continue;
            }
            if ("detection_boxes".equals(name)) {
                boundingBoxes = array.getShape().dimension() > 2 ? array.get(0) : array;
            } else if ("detection_scores".equals(name)) {
                probabilities = (array.getShape().dimension() > 1 ? array.get(0) : array).toFloatArray();
            } else if ("detection_classes".equals(name)) {
                classIds = (array.getShape().dimension() > 1 ? array.get(0) : array)
                        .toType(DataType.INT32, true).toIntArray();
            }
        }
        // 无名输出时按常见顺序兜底
        if (classIds == null || probabilities == null || boundingBoxes == null) {
            if (list.size() >= 3) {
                boundingBoxes = list.get(0).getShape().dimension() > 2 ? list.get(0).get(0) : list.get(0);
                probabilities = (list.get(1).getShape().dimension() > 1 ? list.get(1).get(0) : list.get(1)).toFloatArray();
                classIds = (list.get(2).getShape().dimension() > 1 ? list.get(2).get(0) : list.get(2))
                        .toType(DataType.INT32, true).toIntArray();
            } else {
                return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            }
        }

        List<String> retNames = new ArrayList<>();
        List<Double> retProbs = new ArrayList<>();
        List<BoundingBox> retBB = new ArrayList<>();
        int limit = Math.min(classIds.length, maxBoxes);
        for (int i = 0; i < limit; ++i) {
            int classId = classIds[i];
            double probability = probabilities[i];
            if (classId > 0 && probability > threshold) {
                String className = classes.getOrDefault(classId, "#" + classId);
                float[] box = boundingBoxes.get(i).toFloatArray();
                // TF: [ymin, xmin, ymax, xmax] 归一化
                float yMin = box[0];
                float xMin = box[1];
                float yMax = box[2];
                float xMax = box[3];
                retNames.add(className);
                retProbs.add(probability);
                retBB.add(new Rectangle(xMin, yMin, xMax - xMin, yMax - yMin));
            }
        }
        return new DetectedObjects(retNames, retProbs, retBB);
    }

    /**
     * 构建 COCO 数据集默认类别映射。
     *
     * @return 类别 ID 到名称的映射
     */
    private static Map<Integer, String> defaultCocoClasses() {
        Map<Integer, String> map = new HashMap<>();
        String[] names = {
                "background", "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
                "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse",
                "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie",
                "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
                "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon",
                "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut",
                "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
                "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
                "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        };
        for (int i = 0; i < names.length; i++) {
            map.put(i, names[i]);
        }
        return map;
    }
}
