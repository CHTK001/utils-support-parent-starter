package com.chua.deeplearning.support.onnx;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalImageClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 ONNX Runtime 的本地文生图客户端。
 * <p>
 * 调度 onnx 引擎下已注册的文生图模型（如 small-sd、lcm-lora 系列等），
 * 统一以 {@link ImageClient} 对外提供图像生成能力。
 * </p>
 *
 * <p>用法：
 * <pre>{@code
 *   BufferedImage image = ImageClient.create("onnx", "")
 *       .model("small-stable-diffusion-combined")
 *       .generate("一只柴犬在樱花树下");
 * }</pre>     .generate("一只柴犬在樱花树下");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("onnx")
public class OnnxImageClient extends AbstractLocalImageClient {

    /**
     * 构造 ONNX 文生图客户端。
     *
     * @param setting 客户端配置
     */
    public OnnxImageClient(ImageClientSetting setting) {
        super("onnx", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, ai.djl.modality.cv.Image.class);
    }
}
