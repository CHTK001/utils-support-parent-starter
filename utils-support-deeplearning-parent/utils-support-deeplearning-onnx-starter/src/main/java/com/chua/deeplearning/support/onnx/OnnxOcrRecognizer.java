package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxOcrRecognizer implements OcrRecognizer {

    /** 模型名称 */
    private String modelName;
    /** 语言 */
    /** Lang */
    private String lang = "zh";
    /** 模型路径 */
    private String modelPath;
    /** 是否使用 GPU */
    /** USEGPU */
    private boolean useGpu = false;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
     * 创建 OnnxOcrRecognizer 实例
     * @param apiKey apiKey
     */
    public OnnxOcrRecognizer(String apiKey) {
    }

    @Override
    /** Model */
    public OcrRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "paddleocrv6";
    }

    /** DetectorModel */
    private String detectorModel() {
        String m = resolveModel();
        return m.contains("-det") ? m : m + "-det";
    }

    /** RecognizerModel */
    private String recognizerModel() {
        String m = resolveModel();
        if (m.contains("-rec")) return m;
        if (m.contains("-det")) return m.replace("-det", "-rec");
        return m + "-rec";
    }

    @Override
    /** Lang */
    public OcrRecognizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    /** ModelPath */
    public OcrRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** UseGpu */
    public OcrRecognizer useGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    @Override
    /** Device */
    public OcrRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Recognize */
    public String recognize(byte[] imageData) {
        return OcrPipeline.builder()
                .detector(detectorModel())
                .recognizer(recognizerModel())
                .build()
                .recognize(imageData);
    }

    @Override
    /** RecognizeDetail */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return OcrPipeline.builder()
                .detector(detectorModel())
                .recognizer(recognizerModel())
                .build()
                .recognizeDetail(imageData);
    }

}


