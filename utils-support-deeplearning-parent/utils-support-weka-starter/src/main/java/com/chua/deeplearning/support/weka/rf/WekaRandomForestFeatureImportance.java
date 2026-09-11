package com.chua.deeplearning.support.weka.rf;

import com.chua.deeplearning.support.weka.WekaException;
import com.chua.deeplearning.support.weka.data.ModelDomain;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import com.chua.deeplearning.support.weka.result.FeatureImportance;
import java.util.List;
import java.util.Objects;
import weka.core.Instances;

/**
 * 随机森林特征重要性场景。
 *
 * <p>输入：带标签或目标的数据，输出：各特征重要性（平均不纯度下降）排名，
 * 可用于特征筛选与模型可解释性分析。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 数据需具备标签列（分类）或目标列（回归）之一
 * WekaInstanceData data = WekaInstanceData.classification(features, "label", rows);
 * List<FeatureImportance> importance =
 *         new WekaRandomForestFeatureImportance().analyze(data, RandomForestOptions.defaults());
 * importance.forEach(item -> log.info("特征重要性 {}", item));
 * // 保留前 N 个特征即为特征筛选：
 * List<String> topFeatures = importance.stream().limit(5).map(FeatureImportance::feature).toList();
 * }</pre>
 *
 * @see <a href="https://www.cs.waikato.ac.nz/ml/weka/">Weka 官方文档</a>
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
        Objects.requireNonNull(data, "data must not be null");
        if (!data.hasTargetOrLabel()) {
            throw new WekaException("特征重要性分析需要标签列或目标列");
        }
        var model = RandomForestModel.create(options == null ? RandomForestOptions.defaults() : options,
                ModelDomain.of(data));
        var instances = data.toWekaInstances();
        model.train(instances);
        return model.featureImportances(instances);
    }
}
