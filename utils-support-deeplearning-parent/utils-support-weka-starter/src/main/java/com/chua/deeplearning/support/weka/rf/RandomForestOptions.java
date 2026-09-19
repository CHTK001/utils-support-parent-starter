package com.chua.deeplearning.support.weka.rf;

import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * 随机森林超参数。
 *
 * <p>对应 Weka {@code weka.classifiers.trees.RandomForest} 的参数配置：</p>
 * <ul>
 *   <li>{@code numTrees} -&gt; 树的数量（Bagging 迭代次数）</li>
 *   <li>{@code bagSizePercent} -&gt; Bagging 采样比例（100 = 全量重采样，Weka 默认）</li>
 *   <li>{@code numFeatures} -&gt; 每棵树的候选特征数（0 = 默认 sqrt(总特征数)）</li>
 *   <li>{@code maxDepth} -&gt; 最大树深（0 = 不限制）</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * RandomForestOptions options = RandomForestOptions.defaults();
 * options.setNumTrees(100);        // 更多树通常更稳
 * options.setNumFeatures(5);       // 每棵树只看 5 个候选特征
 * options.setMaxDepth(20);
 * options.setSeed(42);             // 固定种子保证可复现
 * RandomForestModel model = classifier.train(data, options);
 * }</pre>现
 * RandomForestModel model = classifier.train(data, options);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
public class RandomForestOptions implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /** 树的数量（默认 10） */
    private int numTrees = 10;

    /** 随机种子 */
    private int seed = 1;

    /**
     * Bagging 采样比例（百分数，100 = 全量重采样（Weka 默认），取值 1~100）
     */
    private int bagSizePercent = 100;

    /** 每棵树分裂时的候选特征数（0 = 默认 sqrt(总特征数)） */
    private int numFeatures = 0;

    /** 最大树深（0 = 不限制） */
    private int maxDepth = 0;

    /** 候选特征得分并列时随机选择 */
    private boolean breakTiesRandomly = false;

    /**
     * 默认参数。
     *
     * @return 默认参数实例
     */
    public static RandomForestOptions defaults() {
        return new RandomForestOptions();
    }
}
