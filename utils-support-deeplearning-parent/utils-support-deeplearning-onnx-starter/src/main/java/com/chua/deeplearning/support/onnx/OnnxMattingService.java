package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.MattingService;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

@Slf4j
public class OnnxMattingService implements MattingService {

    /** 模型名称 */
    private String modelName;

    /**
    * 创建 onnxmatting服务 实例
    * @param apiKey API密钥
     */
    public OnnxMattingService(String apiKey) {
    }

    @Override
    /** 模型 */
    public MattingService model(String model) {
        this.modelName = model;
        return this;
    }

    /**
    * 解析模型
    *
    * @return resolve模型的结果
     */
    private String resolveModel() {
        return modelName != null ? modelName : "modnet";
    }

    @Override
    /** Matte */
    public byte[] matte(byte[] imageData) {
        return MattingService.create(resolveModel()).matte(imageData);
    }

}


