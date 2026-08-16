package com.chua.deeplearning.support.pytorch;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalFeatureClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 PyTorch (DJL) 的本地特征提取客户端。
 * <p>
 * 调度 pytorch 引擎下已注册的特征模型（图像特征：image-feature、clip-image；
 * 文本特征：sentence、text-feature 等），统一以 {@link FeatureClient} 对外提供特征提取能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("pytorch")
public class PytorchFeatureClient extends AbstractLocalFeatureClient {

    /**
     * 构造 PyTorch 特征提取客户端。
     *
     * @param setting 客户端配置
     */
    public PytorchFeatureClient(FeatureClientSetting setting) {
        super("pytorch", setting);
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, null, float[].class);
    }
}
