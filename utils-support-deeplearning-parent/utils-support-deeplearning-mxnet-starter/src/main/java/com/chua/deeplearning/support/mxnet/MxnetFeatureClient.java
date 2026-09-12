package com.chua.deeplearning.support.mxnet;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalFeatureClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
* 基于 mxnet 的本地图像理解客户端。
* <p>
* 调度 mxnet 引擎下已注册的图像模型（分类等），
* 统一以 {@link FeatureClient} 对外提供图像特征 / 理解能力。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("mxnet")
public class MxnetFeatureClient extends AbstractLocalFeatureClient {

    /**
    * 构造 mxnet 图像理解客户端。
    *
    * @param setting 客户端配置
     */
    public MxnetFeatureClient(FeatureClientSetting setting) {
        super("mxnet", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
