package com.chua.deeplearning.support.pytorch;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalFeatureClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 pytorch (DJL) 的本地特征提取客户端。
 * <p>
 * 调度 pytorch 引擎下已注册的特征模型（图像特征：镜像-特征、clip-镜像；
 * 文本特征：sentence、文本-特征 等），统一以 {@link FeatureClient} 对外提供特征提取能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("pytorch")
public class PytorchFeatureClient extends AbstractLocalFeatureClient {

    /**
     * 构造 pytorch 特征提取客户端。
     *
     * @param setting 客户端配置
     */
    public PytorchFeatureClient(FeatureClientSetting setting) {
        super("pytorch", setting);
    }

    @Override
    /**
     * 模型
    */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, null, float[].class);
    }
}
