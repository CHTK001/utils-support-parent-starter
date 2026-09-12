package com.chua.deeplearning.support.opencv;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalFeatureClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
   * 基于 打开cv 的本地图像理解客户端。
 * <p>
 * 调度 opencv 引擎下已注册的图像模型（人脸检测、行人检测、图像质量评估等），
 * 统一以 {@link FeatureClient} 对外提供图像理解能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencv")
public class OpencvFeatureClient extends AbstractLocalFeatureClient {

    /**
      * 构造 打开cv 图像理解客户端。
     *
     * @param setting 客户端配置
     */
    public OpencvFeatureClient(FeatureClientSetting setting) {
        super("opencv", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
