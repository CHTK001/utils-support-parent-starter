package com.chua.deeplearning.support.onnx.yolo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 印章检测Translator（SDT 印章检测，YOLO 640 检测，4 类：公章/个人章/审核章/其他）。
 *
 * <p>模型：ModelScope Seal_inspection / SDT/Seal_inspection，YOLO 640 检测，4 类。
 * 嵌入式模型位于 {@code vision/detection/seal/model.onnx}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @param arguments 参数
 * @return resolvenms阈值的结果
 */
public class SealInspectionTranslator extends YoloTranslator {

    /**
      * sealinspectiontranslator。
     */
    private static final List<String> SEAL_4_CLASSES = Arrays.asList("公章", "个人章", "审核章", "其他");

    /**
     * SealInspectionTranslator。
     */
    public SealInspectionTranslator() {
        this(null);
    /**
      * sealinspectiontranslator。
     * @param arguments 参数
     */
    }

    public SealInspectionTranslator(Map<String, ?> arguments) {
        super(resolveInputSize(arguments), resolveThreshold(arguments), resolveNmsThreshold(arguments), SEAL_4_CLASSES, true);
    /**
     * resolve输入大小。
     * @param arguments 参数
     * @return resolve输入大小的结果
     */
    }

    private static int resolveInputSize(Map<String, ?> arguments) {
        if (arguments != null && arguments.containsKey("inputSize")) {
            return Integer.parseInt(arguments.get("inputSize").toString());
        }
        return 640;
    /**
     * resolve阈值。
     * @param arguments 参数
     * @return resolve阈值的结果
     */
    }

    private static float resolveThreshold(Map<String, ?> arguments) {
        if (arguments != null && arguments.containsKey("confThreshold")) {
            return Float.parseFloat(arguments.get("confThreshold").toString());
        }
        return 0.75f;
    }

    private static float resolveNmsThreshold(Map<String, ?> arguments) {
        if (arguments != null && arguments.containsKey("iouThreshold")) {
            return Float.parseFloat(arguments.get("iouThreshold").toString());
        }
        return 0.50f;
    }

    @Override
    protected String getYoloVersion() {
        return "YOLO-Seal";
    }
}