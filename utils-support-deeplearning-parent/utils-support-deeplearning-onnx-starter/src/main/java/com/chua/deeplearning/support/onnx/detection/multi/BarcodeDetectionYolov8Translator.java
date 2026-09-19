package com.chua.deeplearning.support.onnx.detection.multi;

import java.util.Arrays;
import java.util.List;

/**
 * 条形码 / 二维码多类检测 Translator（yolov8 @ 640, 5 类）。
 *
 * <p>基于 YOLOv8 微调的多类条形码 / 二维码检测模型。可识别 5 种主流码制。
 *
 * <h2>应用场景</h2>
 * <ul>
 *   <li>商品 / 物流二维码识别（QR、PDF417）</li>
 *   <li>ISBN / EAN / UPC 书籍 / 商品条码</li>
 *   <li>工业 Code128 / Code39 / Code93 资产编码</li>
 * </ul>
 *
 * <h2>注册</h2>
 * <pre>
 *   reg("yolov8n-barcode",
 *       "com.chua.deeplearning.support.onnx.detection.multi.BarcodeDetectionYolov8Translator",
 *       Image.class, DetectedObjects.class, ImageDetector.class,
 *       "vision/barcode/yolov8n/model.onnx");
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BarcodeDetectionYolov8Translator extends AbstractMultiClassYolov8Translator {

    /**
     * 类别资源路径（类路径）。
     */
    public static final String CLASS_NAMES_RESOURCE = "vision/barcode/yolov8n/class.names.txt";

    /**
     * 默认 5 类（与 类.名称.txt 保持一致）。
     */
    public static final List<String> DEFAULT_CLASSES = Arrays.asList(
            "qr_code",
            "code_39",
            "code_128",
            "ean_13",
            "pdf_417"
    );

    /**
     * 创建 barcodedetectionyolov8Translator 实例
    */
    public BarcodeDetectionYolov8Translator() {
        super();
    }

    /**
     * 创建 barcodedetectionyolov8Translator 实例
     * @param inputSize 输入大小
     * @param threshold float
     * @param threshold float
     * @param threshold 阈值
     * @param nmsThreshold nms阈值
     */
    public BarcodeDetectionYolov8Translator(int inputSize, float threshold, float nmsThreshold) {
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
    protected List<String> defaultClassNames() {
        return DEFAULT_CLASSES;
    }
}
