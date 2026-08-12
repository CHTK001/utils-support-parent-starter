package com.chua.deeplearning.support.onnx.detection.single;

/**
 * 通用表格检测 Translator（YOLOv8n @ 640, 1 类 table）。
 *
 * <p>基于 YOLOv8n 微调的通用表格检测模型。输出表格区域 bbox（归一化坐标）。
 *
 * <h2>应用场景</h2>
 * <ul>
 *   <li>PDF / 扫描件表格区域定位（与 OCR pipeline 配合做 cell 识别）</li>
 *   <li>财报、合同、发票、票据的表格预检测</li>
 *   <li>PubLayNet / TableBank 数据集</li>
 * </ul>
 *
 * <h2>注册</h2>
 * <pre>
 *   reg("yolov8n-table-detection",
 *       "com.chua.deeplearning.support.onnx.detection.single.TableDetectionYolov8Translator",
 *       Image.class, DetectedObjects.class, ImageDetector.class,
 *       "vision/table/yolov8n/model.onnx");
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TableDetectionYolov8Translator extends AbstractSingleClassYolov8Translator {

    /**
     * 类别资源路径（classpath）。
     */
    public static final String CLASS_NAMES_RESOURCE = "vision/table/yolov8n/class.names.txt";

    /**
     * 默认构造：640×640、0.25 阈值、0.45 NMS。
     */
    public TableDetectionYolov8Translator() {
        super();
    }

    /**
     * 自定义参数。
     *
     * @param inputSize    输入尺寸
     * @param threshold    置信度阈值
     * @param nmsThreshold NMS IoU 阈值
     */
    public TableDetectionYolov8Translator(int inputSize, float threshold, float nmsThreshold) {
        super(inputSize, threshold, nmsThreshold);
    }

    @Override
    protected String classNamesResourcePath() {
        return CLASS_NAMES_RESOURCE;
    }

    @Override
    protected String defaultClassName() {
        return "table";
    }
}
