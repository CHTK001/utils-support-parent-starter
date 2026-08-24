package com.chua.deeplearning.support.onnx.yolo.v10.translator;

import java.util.Arrays;
import java.util.List;

/**
 * YOLOv10 通用检测 Translator（COCO 80 类，640 输入）。
 *
 * <p>YOLOv10 推理后输出为 NMS 后置格式 {@code [1, num_boxes, 6]}：
 * {@code [x1, y1, x2, y2, confidence, class_id]}，复用 {@link DocLayoutYoloTranslator}
 * 的 NMS 后处理逻辑，仅输入尺寸与类别不同。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class YoloV10DetectTranslator extends DocLayoutYoloTranslator {

    /**
     * COCO 80 类。
     */
    public static final List<String> COCO_CLASSES = Arrays.asList(
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
            "toothbrush");

        /**
     * 创建 Translator（支持运行参数覆盖阈值，未提供的键使用内置默认值）。
     *
     * @param configuration 检测配置（可空）
     */
    public YoloV10DetectTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        super(640,
                configuration == null ? 0.2f
                        : configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, 0.2f),
                COCO_CLASSES);
    }

/**
     * 无参构造（640 输入，COCO 80 类）。
     */
    public YoloV10DetectTranslator() {
        super(640, 0.2f, COCO_CLASSES);
    }
}
