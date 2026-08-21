package com.chua.deeplearning.support.onnx.yolo;

import java.util.Arrays;
import java.util.List;

/**
 * VisDrone 小目标检测 Translator（嵌入式，VisDrone 10 类）。
 *
 * <p>类别：pedestrian / people / bicycle / car / van / truck / tricycle / awning-tricycle / bus / motor。
 * 嵌入式模型位于 {@code vision/visdrone/damoyolo_visdrone.onnx}（待 DAMO-YOLO-TinyNAS checkpoint 转 ONNX 替换）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VisDroneSmallDetectorTranslator extends YoloTranslator {

    /**
     * VisDrone 数据集 10 个类别名称。
     */
    private static final List<String> VISDRONE_10_CLASSES = Arrays.asList(
            "pedestrian", "people", "bicycle", "car", "van",
            "truck", "tricycle", "awning-tricycle", "bus", "motor"
    );

    public VisDroneSmallDetectorTranslator() {
        super(640, 0.10f, 0.50f, VISDRONE_10_CLASSES, true);
    }

    @Override
    protected String getYoloVersion() {
        return "YOLO-VisDrone";
    }
}
