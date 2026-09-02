package com.chua.deeplearning.support.dl4j;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 训练进度状态，用于训练过程中的进度上报与查询。
 *
 * <p>训练平台通过 {@link TrainListener} 实时回调更新该对象，前端轮询接口读取它
 * 即可展示训练进度、当前 epoch、最佳评分等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Accessors(chain = true)
public class TrainProgress implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 训练状态。
     */
    private TrainStatus status;

    /**
     * 当前 epoch（从 0 开始）。
     */
    private int currentEpoch;

    /**
     * 总 epoch 数。
     */
    private int totalEpochs;

    /**
     * 当前训练阶段描述（如 "加载数据" / "构建模型" / "训练中" / "评估中"）。
     */
    private String stage;

    /**
     * 进度百分比（0~100）。
     */
    private double progress;

    /**
     * 当前最优 F1 值。
     */
    private double bestScore;

    /**
     * 错误信息（状态为 {@link TrainStatus#FAILED} 时填充）。
     */
    private String error;

    /**
     * 模型保存路径（训练完成时填充）。
     */
    private String modelPath;

    /**
     * 实际使用的基模型路径（显示是预训练还是续训模型，启动时填充）。
     */
    private String baseModelPath;

    /**
     * 类别标签串（训练完成后填充）。
     */
    private String classLabels;
}