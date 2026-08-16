package com.chua.deeplearning.support.onnx;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalFeatureClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 ONNX Runtime 的本地特征提取客户端。
 * <p>
 * 调度 onnx 引擎下已注册的特征模型（图像特征：mobileclip、dinov2 等；
 * 文本特征：clip-text、cn-clip 等），统一以 {@link FeatureClient} 对外提供特征提取能力。
 * 图像特征模型走 DJL onnxruntime-engine 推理（OpenCV 预处理，create() 喂入）。
 * </p>
 *
 * <p>用法：
 * <pre>{@code
 *   // 文本特征
 *   float[] textVec = FeatureClient.create("onnx", "")
 *       .model("clip-text-feature")
 *       .extract("文本");
 *
 *   // 图像特征
 *   float[] imgVec = FeatureClient.create("onnx", "")
 *       .model("mobileclip-s0-vision")
 *       .extractImage(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("onnx")
public class OnnxFeatureClient extends AbstractLocalFeatureClient {

    /**
     * 构造 ONNX 特征提取客户端。
     *
     * @param setting 客户端配置
     */
    public OnnxFeatureClient(FeatureClientSetting setting) {
        super("onnx", setting);
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, null, float[].class);
    }
}
