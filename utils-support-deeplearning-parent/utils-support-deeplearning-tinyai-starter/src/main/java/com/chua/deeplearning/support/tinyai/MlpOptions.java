package com.chua.deeplearning.support.tinyai;

import java.io.Serializable;
import java.util.Objects;

/**
 * TinyAI 神经网络分类训练参数。
 *
 * <p>MLP 结构为「输入层 -> hiddenLayers 层隐藏层（ReLU） -> 输出层（类别数）」，
 * 损失为 Softmax 交叉熵，优化器为 Adam；全部字段不可变，构造即校验。</p>
 *
 * @param epochs       训练轮数（至少 1）
 * @param batchSize    批大小（至少 1，超过样本数时按样本数处理）
 * @param learningRate 学习率（大于 0）
 * @param hiddenLayers 隐藏层宽度（顺序即网络深度，每层至少 1 个神经元）
 * @param seed         随机种子（TinyAI 全局随机，底层由 JDK Random 驱动）
 * @author CH
 * @since 4.0.0.42
 */
public record MlpOptions(int epochs, int batchSize, float learningRate, int[] hiddenLayers, long seed)
        implements Serializable {

    /**
     * 默认隐藏层结构。
     */
    private static final int[] DEFAULT_HIDDEN = {64, 32};

    /**
     * 规范构造器：校验合法性并防御性拷贝隐藏层数组。
     *
     * @param epochs       训练轮数
     * @param batchSize    批大小
     * @param learningRate 学习率
     * @param hiddenLayers 隐藏层宽度
     * @param seed         随机种子
     */
    public MlpOptions {
        if (epochs < 1) {
            throw new TinyAiException("训练轮数至少 1，实际 " + epochs);
        }
        if (batchSize < 1) {
            throw new TinyAiException("批大小至少 1，实际 " + batchSize);
        }
        if (!(learningRate > 0)) {
            throw new TinyAiException("学习率须大于 0，实际 " + learningRate);
        }
        Objects.requireNonNull(hiddenLayers, "hiddenLayers 不能为 null");
        if (hiddenLayers.length == 0) {
            throw new TinyAiException("隐藏层至少 1 层");
        }
        for (var width : hiddenLayers) {
            if (width < 1) {
                throw new TinyAiException("隐藏层宽度至少 1，实际 " + width);
            }
        }
        hiddenLayers = hiddenLayers.clone();
    }

    /**
     * 默认参数：200 轮、批 32、lr 0.01、隐藏层 64/32。
     *
     * @return 默认参数
     */
    public static MlpOptions defaults() {
        return new MlpOptions(200, 32, 0.01F, DEFAULT_HIDDEN.clone(), 42L);
    }

    /**
     * 测试用小参数：20 轮、批 8、lr 0.05、隐藏层 16。
     *
     * @return 测试参数
     */
    public static MlpOptions testDefaults() {
        return new MlpOptions(20, 8, 0.05F, new int[]{16}, 42L);
    }
}
