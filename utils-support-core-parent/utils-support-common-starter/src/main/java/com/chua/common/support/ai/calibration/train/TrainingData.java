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
    /** NOTsimilarscores */
    private List<Double> notSimilarScores;

    /** 看似相似目录的原始分数列表 */
    /** Looksimilarscores */
    private List<Double> lookSimilarScores;

    /** 本人目录的原始分数列表 */
    /** Samepersonscores */
    private List<Double> samePersonScores;

    /**
     * 判断训练数据是否为空。
     *
     * @return true 表示三个目录均无数据
     */
    public boolean isEmpty() {
        return (notSimilarScores == null || notSimilarScores.isEmpty())
                && (lookSimilarScores == null || lookSimilarScores.isEmpty())
                && (samePersonScores == null || samePersonScores.isEmpty());
    }

    /**
     * 获取正样本分数列表（本人目录）。
     *
     * @return 正样本分数列表，若为空则返回空列表
     */
    public List<Double> getPositiveScores() {
        if (samePersonScores == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(samePersonScores);
    }

    /**
     * 获取负样本分数列表（不相似 + 看似相似目录）。
     *
     * @return 负样本分数列表
     */
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
