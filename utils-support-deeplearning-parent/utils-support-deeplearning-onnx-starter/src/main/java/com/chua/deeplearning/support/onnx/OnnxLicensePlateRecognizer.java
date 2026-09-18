package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.plate.LicensePlateRecognizer;
import com.chua.deeplearning.support.plate.PlateResult;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

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

    /**
    * 创建 onnx执照铭牌recognizer 实例
    * @param apiKey API密钥
    */
    public OnnxLicensePlateRecognizer(String apiKey) {
    }

    @Override
    /** 模型 */
    public LicensePlateRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    /**
    * 解析模型
    *
    * @return resolve模型的结果
    */
    private String resolveModel() {
        return modelName != null ? modelName : "yolov5-plate-detect";
    }

    /**
    * 铭牌detect模型
    *
    * @return 铭牌detect模型的结果
    */
    private String plateDetectModel() {
        String m = resolveModel();
        if (m.contains("-detect")) {
            return m;
        }
        if (m.contains("-recognize")) {
            return m.replace("-recognize", "-detect");
        }
        return m + "-detect";
    }

    /**
    * 铭牌rec模型
    *
    * @return 铭牌rec模型的结果
    */
    private String plateRecModel() {
        String m = resolveModel();
        if (m.contains("-recognize")) {
            return m;
        }
        if (m.contains("-detect")) {
            return m.replace("-detect", "-recognize");
        }
        return "crnn-plate-rec";
    }

    @Override
    /** 阈值 */
    public LicensePlateRecognizer threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** 模型路径 */
    public LicensePlateRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Device */
    public LicensePlateRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Recognize */
    public String recognize(byte[] imageData) {
        PlateResult pr = recognizePlate(imageData);
        return pr == null ? "" : pr.plateNo();
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * recognizedetail
    *
    * @param imageData 镜像数据
    * @return recognizeDetail的结果
    */
    public List<DetectionInfo> recognizeDetail(byte[] imageData) {
        return ImageDetector.create(plateDetectModel())
                .threshold(threshold).modelPath(modelPath).device(device).detect(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * recognize铭牌
    *
    * @param imageData 镜像数据
    * @return recognize铭牌的结果
    */
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


