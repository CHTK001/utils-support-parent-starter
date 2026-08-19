package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.MattingService;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxMattingService implements MattingService {

    /** 模型名称 */
    private String modelName;

    /**
     * 创建 OnnxMattingService 实例
     * @param apiKey apiKey
     */
    public OnnxMattingService(String apiKey) {
    }

    @Override
    /** Model */
    public MattingService model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "modnet";
    }

    @Override
    /** Matte */
    public byte[] matte(byte[] imageData) {
        return MattingService.create(resolveModel()).matte(imageData);
    }

}


