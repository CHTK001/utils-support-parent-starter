package com.chua.deeplearning.support.onnx;

import com.chua.common.support.utils.StringUtils;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
 */

@Slf4j
public class OnnxOcrRecognizer implements OcrRecognizer {

    /**
     * 模型名称
     */
    private String modelName;
    /**
     * 语言
     */
    private String lang = "zh";
    /**
     * 模型路径
     */
    private String modelPath;
    /**
     * 是否使用 GPU
     */
    private boolean useGpu = false;
    /**
     * 设备类型
     */
    private String device = "cpu";
    /**
     * 缓存的 OCR 管线（避免每次识别重建节点树）
     */
    private OcrPipeline pipeline;

    /**
     * 创建 onnxocrrecognizer 实例
     * @param apiKey API密钥
     */
    public OnnxOcrRecognizer(String apiKey) {
    }

    @Override
    /**
     * 模型
    */
    public OcrRecognizer model(String model) {
        this.modelName = model;
        this.pipeline = null;
        return this;
    }

    /**
     * 解析模型
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定 OCR 模型，请通过 .model(\"模型ID\") 指定，"
                    + "或在系统配置 ocr 分组的 detector_model / recognizer_model 中选择，可用模型: "
                    + OcrRecognizer.listModels());
        }
        return modelName;
    }

    /**
     * detector模型
     *
     * @return detector模型的结果
     */
    private String detectorModel() {
        String m = resolveModel();
        return m.contains("-det") ? m : m + "-det";
    }

    /**
     * recognizer模型
     *
     * @return recognizer模型的结果
     */
    private String recognizerModel() {
        String m = resolveModel();
        if (m.contains("-rec")) {
            return m;
        }
        if (m.contains("-det")) {
            return m.replace("-det", "-rec");
        }
        return m + "-rec";
    }

    @Override
    /**
     * Lang
    */
    public OcrRecognizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    /**
     * 模型路径
    */
    public OcrRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /**
     * usegpu
    */
    public OcrRecognizer useGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    @Override
    /**
     * Device
    */
    public OcrRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /**
     * Recognize
     */
    public String recognize(byte[] imageData) {
        return pipeline().recognize(imageData);
    }

    @Override
    /**
     * recognizedetail
     */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return pipeline().recognizeDetail(imageData);
    }

    /**
     * 构建 OCR 管线。
     * <p>管线不可变且内部复用已加载的 ONNX 会话，此处缓存一份，
     * 避免每次识别都重建 {@code PipelineBuilder} 节点树。</p>
     * <p>{@code useGpu}/{@code device} 会写入系统属性 {@code deeplearning.device}，
     * 供 {@link com.chua.deeplearning.support.engine.DeviceSelector} 与
     * {@link GpuHelper} 统一裁决实际执行设备；显式设置过则不再覆盖。</p>
     *
     * @return OCR 管线
     */
    private synchronized OcrPipeline pipeline() {
        applyDevice();
        if (pipeline == null) {
            pipeline = OcrPipeline.builder()
                    .detector(detectorModel())
                    .recognizer(recognizerModel())
                    .build();
        }
        return pipeline;
    }

    /**
     * 将显式设置的推理设备写入系统属性。
     */
    private void applyDevice() {
        if (StringUtils.isNotBlank(device)) {
            System.setProperty(com.chua.deeplearning.support.engine.DeviceSelector.PROP, device.trim());
        } else if (useGpu) {
            System.setProperty(com.chua.deeplearning.support.engine.DeviceSelector.PROP, "gpu");
        }
    }

}


