package com.chua.deeplearning.support.onnx.yolo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 火灾烟雾检测Translator（嵌入式，火/烟 2 类）。
 *
 * <p>模型：fiacecson20/cctv-ai-fire-smoke，YOLOv8n 320 检测，2 类：fire/smoke。
 * 嵌入式模型位于 {@code vision/fire-smoke/yolov8n/model.onnx}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @param arguments 参数
 * @return resolve输入大小的结果
 */
public class FireSmokeDetectorTranslator extends YoloTranslator {

    /**
      * firesmokedetectortranslator。
     */
    private static final List<String> FIRE_SMOKE_2_CLASSES = Arrays.asList("fire", "smoke");

    /**
     * FireSmokeDetectorTranslator。
     */
    public FireSmokeDetectorTranslator() {
        this(null);
    /**
      * firesmokedetectortranslator。
     * @param arguments 参数
     * @return resolve输入大小的结果
     */
    }

    public FireSmokeDetectorTranslator(Map<String, ?> arguments) {
        super(resolveInputSize(arguments), resolveThreshold(arguments), resolveNmsThreshold(arguments), FIRE_SMOKE_2_CLASSES, true);
    }

    private static int resolveInputSize(Map<String, ?> arguments) {
        if (arguments != null && arguments.containsKey("inputSize")) {
            return Integer.parseInt(arguments.get("inputSize").toString());
        }
        // 嵌入式权重(vision/fire-smoke/yolov8n/model.onnx)为 640 导出，默认对齐 640
        return 640;
    }

    /**
     * resolve阈值。
     * @param arguments 参数
     * @return resolve阈值的结果
     */
    private static float resolveThreshold(Map<String, ?> arguments) {
        if (arguments != null && arguments.containsKey("confThreshold")) {
            return Float.parseFloat(arguments.get("confThreshold").toString());
        }
        return 0.45f;
    }

    /**
     * resolvenms阈值。
     * @param arguments 参数
     * @return resolvenms阈值的结果
     */
    private static float resolveNmsThreshold(Map<String, ?> arguments) {
        if (arguments != null && arguments.containsKey("iouThreshold")) {
            return Float.parseFloat(arguments.get("iouThreshold").toString());
        }
        return 0.50f;
    }

    @Override
    protected String getYoloVersion() {
        return "YOLO-FireSmoke";
    }
}
