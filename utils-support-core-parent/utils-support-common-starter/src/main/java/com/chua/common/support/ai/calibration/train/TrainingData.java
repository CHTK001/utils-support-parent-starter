package com.chua.common.support.ai.calibration.train;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 训练数据（三个目录的分数）。
 *
 * @author CH
 * @since 2026/07/31
 */
@Data
@Builder
public class TrainingData {

    /** 不相似目录的原始分数列表 */
    private List<Double> notSimilarScores;

    /** 看似相似目录的原始分数列表 */
    private List<Double> lookSimilarScores;

    /** 本人目录的原始分数列表 */
    private List<Double> samePersonScores;

    public boolean isEmpty() {
        return (notSimilarScores == null || notSimilarScores.isEmpty())
                && (lookSimilarScores == null || lookSimilarScores.isEmpty())
                && (samePersonScores == null || samePersonScores.isEmpty());
    }

    public List<Double> getPositiveScores() {
        if (samePersonScores == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(samePersonScores);
    }

    public List<Double> getNegativeScores() {
        List<Double> negatives = new ArrayList<>();
        if (notSimilarScores != null) {
            negatives.addAll(notSimilarScores);
        }
        if (lookSimilarScores != null) {
            negatives.addAll(lookSimilarScores);
        }
        return negatives;
    }
}
