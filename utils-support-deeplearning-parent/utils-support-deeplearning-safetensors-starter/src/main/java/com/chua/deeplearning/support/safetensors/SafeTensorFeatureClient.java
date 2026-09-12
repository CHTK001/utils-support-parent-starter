package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalFeatureClient;

import java.util.List;

/**
   * safetensor 本地特征提取客户端（HTTP 网关）。
 * <p>
   * 通过本地 safetensor服务（localhost:8765）调度文本嵌入 / 图像识别类模型，
 * 统一以 {@link FeatureClient} 对外提供特征提取能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("safetensors")
public class SafeTensorFeatureClient extends AbstractLocalFeatureClient {

    /**
      * 构造 safetensor 特征提取客户端。
     *
     * @param setting 客户端配置
     */
    public SafeTensorFeatureClient(FeatureClientSetting setting) {
        super("safetensors", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return SafeTensorModels.ofType("text_embedding", "image_recognition");
    }
}
