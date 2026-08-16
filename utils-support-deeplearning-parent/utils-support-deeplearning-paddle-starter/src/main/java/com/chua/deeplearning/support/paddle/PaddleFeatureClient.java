package com.chua.deeplearning.support.paddle;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalFeatureClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 PaddlePaddle 的本地特征提取客户端。
 * <p>
 * 调度 paddle 引擎下已注册的特征模型（如人脸特征、人脸关键点等 Image→float[] 模型），
 * 统一以 {@link FeatureClient} 对外提供特征提取能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("paddle")
public class PaddleFeatureClient extends AbstractLocalFeatureClient {

    /**
     * 构造 Paddle 特征提取客户端。
     *
     * @param setting 客户端配置
     */
    public PaddleFeatureClient(FeatureClientSetting setting) {
        super("paddle", setting);
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, null, float[].class);
    }
}
