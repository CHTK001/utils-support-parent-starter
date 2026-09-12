package com.chua.deeplearning.support.pytorch;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalImageClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
   * 基于 pytorch (DJL) 的本地文生图客户端。
 * <p>
   * 调度 pytorch 引擎下已注册的图像生成模型（如 biggan 系列等），
 * 统一以 {@link ImageClient} 对外提供图像生成能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("pytorch")
public class PytorchImageClient extends AbstractLocalImageClient {

    /**
      * 构造 pytorch 文生图客户端。
     *
     * @param setting 客户端配置
     */
    public PytorchImageClient(ImageClientSetting setting) {
        super("pytorch", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, ai.djl.modality.cv.Image.class);
    }
}
