package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.plate.LicensePlateRecognizer;
import com.chua.deeplearning.support.plate.PlateResult;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxLicensePlateRecognizer implements LicensePlateRecognizer {

    /** 模型名称 */
    private String modelName;
    /** 阈值 */
    private float threshold = 0.5f;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    public OnnxLicensePlateRecognizer(String apiKey) {
    }

    @Override
    public LicensePlateRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "yolov5-plate-detect";
    }

    private String plateDetectModel() {
        String m = resolveModel();
        if (m.contains("-detect")) return m;
        if (m.contains("-recognize")) return m.replace("-recognize", "-detect");
        return m + "-detect";
    }

    private String plateRecModel() {
        String m = resolveModel();
        if (m.contains("-recognize")) return m;
        if (m.contains("-detect")) return m.replace("-detect", "-recognize");
        return "crnn-plate-rec";
    }

    @Override
    public LicensePlateRecognizer threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public LicensePlateRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public LicensePlateRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public String recognize(byte[] imageData) {
        PlateResult pr = recognizePlate(imageData);
        return pr == null ? "" : pr.plateNo();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DetectionInfo> recognizeDetail(byte[] imageData) {
        return ImageDetector.create(plateDetectModel())
                .threshold(threshold).modelPath(modelPath).device(device).detect(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    public PlateResult recognizePlate(byte[] imageData) {
        ITranslator<byte[], PlateResult> t =
                (ITranslator<byte[], PlateResult>) AbstractIdentificationEngine.getInstance()
                        .get(plateRecModel(), ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + plateRecModel());
        }
        return t.translate(imageData);
    }

}


