package com.chua.deeplearning.support.onnx.detection.multi;

import java.util.Arrays;
import java.util.List;

/**
 * 火灾烟雾检测 Translator（YOLOv8n @ 640, 2 类 fire/smoke）。
 *
 * <p>用于早期火灾预警与烟雾识别。
 *
 * <h2>应用场景</h2>
 * <ul>
 *   <li>森林防火高空瞭望塔 / 摄像头实时烟火识别</li>
 *   <li>工厂 / 仓储 / 厨房烟雾告警</li>
 *   <li>电动车 / 充电桩自燃早期识别</li>
 * </ul>
 *
 * <h2>注册</h2>
 * <pre>
 *   reg("yolov8n-fire-smoke",
 *       "com.chua.deeplearning.support.onnx.detection.multi.FireSmokeDetectionYolov8Translator",
 *       Image.class, DetectedObjects.class, ImageDetector.class,
 *       "vision/fire-smoke/yolov8n/model.onnx");
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FireSmokeDetectionYolov8Translator extends AbstractMultiClassYolov8Translator {

    /**
     * 类别资源路径（classpath）。
     */
    public static final String CLASS_NAMES_RESOURCE = "vision/fire-smoke/yolov8n/class.names.txt";

    /**
     * 默认 2 类。
     */
    public static final List<String> DEFAULT_CLASSES = Arrays.asList(
            "fire",
            "smoke"
    );

    public FireSmokeDetectionYolov8Translator() {
        super();
    }

    public FireSmokeDetectionYolov8Translator(int inputSize, float threshold, float nmsThreshold) {
        super(inputSize, threshold, nmsThreshold);
    }

    @Override
    protected String classNamesResourcePath() {
        return CLASS_NAMES_RESOURCE;
    }

    @Override
    protected List<String> defaultClassNames() {
        return DEFAULT_CLASSES;
    }
}
