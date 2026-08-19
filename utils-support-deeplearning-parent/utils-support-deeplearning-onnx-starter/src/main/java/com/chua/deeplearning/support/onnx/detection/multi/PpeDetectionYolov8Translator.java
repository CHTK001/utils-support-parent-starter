package com.chua.deeplearning.support.onnx.detection.multi;

import java.util.Arrays;
import java.util.List;

/**
 * 个人防护装备 (PPE) 多类检测 Translator（YOLOv8n @ 640, 3 类）。
 *
 * <p>用于工地 / 工厂 / 物流场景的安全装备合规检测。
 *
 * <h2>应用场景</h2>
 * <ul>
 *   <li>建筑工地实时安全帽佩戴合规检测（helmet / no-helmet）</li>
 *   <li>道路施工 / 环卫工人反光衣合规检测（vest）</li>
 *   <li>工厂车间异常作业行为预警</li>
 * </ul>
 *
 * <h2>注册</h2>
 * <pre>
 *   reg("yolov8n-ppe",
 *       "com.chua.deeplearning.support.onnx.detection.multi.PpeDetectionYolov8Translator",
 *       Image.class, DetectedObjects.class, ImageDetector.class,
 *       "vision/ppe/yolov8n/model.onnx");
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PpeDetectionYolov8Translator extends AbstractMultiClassYolov8Translator {

    /**
     * 类别资源路径（classpath）。
     */
    public static final String CLASS_NAMES_RESOURCE = "vision/ppe/yolov8n/class.names.txt";

    /**
     * 默认 3 类。
     */
    public static final List<String> DEFAULT_CLASSES = Arrays.asList(
            "helmet",
            "vest",
            "no-helmet"
    );

    /** 创建 PpeDetectionYolov8Translator 实例 */
    public PpeDetectionYolov8Translator() {
        super();
    }

    /**
     * 创建 PpeDetectionYolov8Translator 实例
     * @param inputSize inputSize
     * @param float float
     * @param float float
     */
    public PpeDetectionYolov8Translator(int inputSize, float threshold, float nmsThreshold) {
        super(inputSize, threshold, nmsThreshold);
    }

    @Override
    /** ClassNamesResourcePath */
    protected String classNamesResourcePath() {
        return CLASS_NAMES_RESOURCE;
    }

    @Override
    /** DefaultClassNames */
    protected List<String> defaultClassNames() {
        return DEFAULT_CLASSES;
    }
}
