package com.chua.deeplearning.support.weka.data;

import com.chua.deeplearning.support.weka.WekaException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 建模域快照：训练一个模型所需的全部元信息。
 *
 * <p>包含特征列定义、目标列名、是否回归标记与名义取值快照，
 * 由 {@link #of(WekaInstanceData)} 从数据对象派生，供模型创建 / 预测复用。</p>
 *
 * @param features     特征列定义（与训练数据顺序一致）
 * @param targetName   目标列名（标签列或回归目标列）
 * @param regression   是否回归域（目标为数值）
 * @param nominalValues 名义列（含目标列）取值快照
 * @author CH
 * @since 4.0.0.42
 */
public record ModelDomain(List<FeatureColumn> features, String targetName, boolean regression,
        Map<String, List<String>> nominalValues) implements Serializable {

    /** 序列化版本号（P3C 规约：Serializable 必须声明） */
    private static final long serialVersionUID = 1L;

    /**
    * 紧凑构造器：校验 特征 / Target名称 / nominal值 非空，
    * 并将 特征 固化为不可变列表、nominal值 固化为不可变 映射 拷贝。
    */
    public ModelDomain {
        Objects.requireNonNull(features, "features must not be null");
        Objects.requireNonNull(targetName, "targetName must not be null");
        Objects.requireNonNull(nominalValues, "nominalValues must not be null");
        features = List.copyOf(features);
        nominalValues = Map.copyOf(nominalValues);
    }

    /**
     * 从数据对象派生建模域。
     *
     * @param data 数据对象（须已指定标签列或目标列）
     * @return 建模域快照
     * @throws WekaException 数据未指定标签 / 目标列
     */
    public static ModelDomain of(WekaInstanceData data) {
        Objects.requireNonNull(data, "data must not be null");
        if (!data.hasTargetOrLabel()) {
            throw new WekaException("数据未指定标签列或目标列（withLabelColumn / withTargetColumn）");
        }
        return new ModelDomain(data.getFeatures(), data.targetName(), data.hasTarget(), data.nominalValues());
    }
}
