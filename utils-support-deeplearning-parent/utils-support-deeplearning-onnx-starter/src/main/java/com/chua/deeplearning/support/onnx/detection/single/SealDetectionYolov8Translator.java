package com.chua.deeplearning.support.onnx.detection.single;

/**
 * 中文印章检测 Translator（yolov8n @ 640, 1 类 seal）。
 *
 * <p>基于 YOLOv8n 微调的中文印章检测模型，输出圆形 / 方形红色印章 bbox。
 *
 * <h2>应用场景</h2>
 * <ul>
 *   <li>公文 / 合同 / 发票 / 证件扫描件印章区域定位</li>
 *   <li>印章真伪鉴定（裁剪后做 OCR 比对或图像比对）</li>
 *   <li>数据集：CS_RC、HUST_CHN_OFFICIAL_SEAL、SealBench</li>
 * </ul>
 *
 * <h2>注册</h2>
 * <pre>
 *   reg("yolov8n-seal-detection",
 *       "com.chua.deeplearning.support.onnx.detection.single.SealDetectionYolov8Translator",
 *       Image.class, DetectedObjects.class, ImageDetector.class,
 *       "vision/seal/yolov8n/model.onnx");
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SealDetectionYolov8Translator extends AbstractSingleClassYolov8Translator {

    /**
     * 类别资源路径（类路径）。
     */
    public static final String CLASS_NAMES_RESOURCE = "vision/seal/yolov8n/class.names.txt";

    /**
     * 默认构造：640×640、0.25 阈值、0.45 NMS。
     */
    public SealDetectionYolov8Translator() {
        super();
    }

    /**
     * 自定义参数。
     *
     * @param inputSize    输入尺寸
     * @param threshold    置信度阈值
     * @param nmsThreshold NMS iou 阈值
     */
    public SealDetectionYolov8Translator(int inputSize, float threshold, float nmsThreshold) {
        super(inputSize, threshold, nmsThreshold);
    }

    @Override
    /**
     * 类名称resource路径
    */
    protected String classNamesResourcePath() {
        return CLASS_NAMES_RESOURCE;
    }

    @Override
    /**
     * 默认类名称
    */
    protected String defaultClassName() {
        return "seal";
    }
}
