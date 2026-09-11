package com.chua.deeplearning.support.weka.rf;

import com.chua.deeplearning.support.weka.WekaException;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import com.chua.deeplearning.support.weka.result.FeatureImportance;
import java.util.List;
import weka.core.Instances;

/**
 * 随机森林特征重要性场景。
 *
 * <p>输入：带标签或目标的数据，输出：各特征重要性（平均不纯度下降）排名，
 * 可用于特征筛选与模型可解释性分析。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WekaRandomForestFeatureImportance {

    /**
     * 分析特征重要性。
     *
     * @param data    数据（需具备标签列或目标列）
     * @param options 随机森林参数，传 {@code null} 使用默认值
     * @return 按重要性降序排列的特征重要性列表
     * @throws WekaException 数据缺少标签 / 目标列或训练失败
     */
    public List<FeatureImportance> analyze(WekaInstanceData data, RandomForestOptions options) {
        if (!data.hasTargetOrLabel()) {
            throw new WekaException("特征重要性分析需要标签列或目标列");
        }
        boolean regression = data.hasTarget();
        RandomForestModel model = RandomForestModel.create(
                options == null ? RandomForestOptions.defaults() : options,
                regression, data.getFeatures(), data.targetName(), data.nominalValues());
        Instances ins = data.toWekaInstances();
        model.train(ins);
        return model.featureImportances(ins);
    }
}
