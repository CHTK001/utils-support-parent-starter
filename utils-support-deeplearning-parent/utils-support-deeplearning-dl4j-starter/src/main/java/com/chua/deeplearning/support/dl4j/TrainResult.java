package com.chua.deeplearning.support.dl4j;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 模型训练结果。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Accessors(chain = true)
public class TrainResult implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
     * 是否训练成功。
     */
    private boolean success;

    /**
     * 模型保存路径。
     */
    private String modelPath;

    /**
     * 类别标签列表。
     */
    private List<String> labels;

    /**
     * 最优 F1 值。
     */
    private double bestScore;

    /**
     * 训练耗时（毫秒）。
     */
    private long elapsedMs;

    /**
     * 错误信息（失败时填充）。
     */
    private String errorMessage;
}