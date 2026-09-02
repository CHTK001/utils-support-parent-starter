package com.chua.deeplearning.support.onnx.detection.yolo_uniow;

import com.chua.deeplearning.support.onnx.detection.multi.AbstractMultiClassYolov8Translator;

import java.util.List;

/**
 * YOLO-UniOW 通用开世界目标检测（Open-World Object Detection）Translator。
 *
 * <p>YOLO-UniOW（清华 THU-MIG，arxiv 2412.20645）基于 YOLO-World + YOLOv10，
 * 通过 Adaptive Decision Learning 在 CLIP 潜在空间完成轻量对齐，
 * 支持开世界（动态类别）与未知目标（wildcard）检测。模型分 S/M/L 三档
 * （7.5M / 16.2M / 29.4M 参数）。</p>
 *
 * <p>本 Translator 复用 {@link AbstractMultiClassYolov8Translator} 的 YOLO 系
 * 解码链路：640 输入缩放、xcycwh 输出解码、sigmoid 置信度与 NMS 过滤，
 * 输出 {@code DetectedObjects}（经 DeepLearning 能力层转换为检测框列表）。</p>
 *
 * <h2>模型资产（ONNX）</h2>
 * <p>官方权重为 PyTorch .pth（PyTorch 2.1.2 + mmcv/mmdet），无法由 DJL 直接加载。
 * 推理前需先导出 ONNX：社区导出参考（AXERA-TECH/YOLO-UniOW export_onnx.py）
 * 采用 reparameterize（文本词表固化进权重）后导出。导出后将 .onnx 放置到
 * 模型根目录对应相对路径（见 {@code OnnxModelRegistrar} 注册条目），
 * 或通过模型目录下载（HF 预留 URL）。</p>
 *
 * <h2>类别词表</h2>
 * <p>开世界推理的类别由导出模型时固化的词表决定：
 * <ul>
 *   <li>使用官方预计算 wildcard 特征（{@code object_tuned_*.npy}）导出时，
 *       模型按泛化 "object" 检测，本类默认类别为单类 {@code ["object"]}；</li>
 *   <li>自定义词表导出（如 COCO 80 类）时，放置
 *       {@code class.names.txt} 到 classpath（每行一类）即可自动覆盖默认类别。</li>
 * </ul></p>
 *
 * <h2>注册</h2>
 * <pre>
 *   reg("yolo-uniow",
 *       "com.chua.deeplearning.support.onnx.detection.yolo_uniow.YoloUniowTranslator",
 *       Image.class, DetectedObjects.class, ImageDetector.class,
 *       "vision/detection/yolo_uniow/model.onnx");
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class YoloUniowTranslator extends AbstractMultiClassYolov8Translator {

    /**
     * 类别资源路径（classpath，可选，缺省回退到默认单类 object）。
     */
    public static final String CLASS_NAMES_RESOURCE = "vision/detection/yolo_uniow/class.names.txt";

    /**
     * 默认类别：object_tuned wildcard 模式的泛化 "object" 单类检测。
     */
    public static final List<String> DEFAULT_CLASSES = List.of("object");

    /** 创建 YoloUniowTranslator 实例（默认 640 / 阈值 0.25 / NMS 0.45） */
    public YoloUniowTranslator() {
        super();
    }

    /**
     * 创建 YoloUniowTranslator 实例。
     *
     * @param inputSize    输入尺寸（YOLO-UniOW 官方为 640）
     * @param threshold    置信度阈值
     * @param nmsThreshold NMS IoU 阈值
     */
    public YoloUniowTranslator(int inputSize, float threshold, float nmsThreshold) {
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
