package com.chua.deeplearning.support.ai.result;

import lombok.Getter;
import lombok.Setter;

/**
 * 人像分析预测结果。
 * <p>
 * 适用于年龄、性别、种族等多属性分析场景。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
public class HumanPredictResult {

    /**
     * 处理耗时（毫秒）
     */
    private long processingTimeMs;

    /**
     * 置信度
     */
    private float confidence;

    /**
     * 预测值
     */
    private String value;

    /**
     * 性别
     */
    private String gender;

    /**
     * 性别置信度
     */
    private float genderConfidence;

    /**
     * 年龄
     */
    private int age;

    /**
     * 年龄置信度
     */
    private float ageConfidence;

    /**
     * 种族
     */
    private String race;

    /**
     * 种族置信度
     */
    private float raceConfidence;

    public HumanPredictResult() {
    }

    /**
     * 设置性别检测结果。
     *
     * @param gender     性别
     * @param confidence 置信度
     */
    public void setGenderDetected(String gender, float confidence) {
        this.gender = gender;
        this.genderConfidence = confidence;
    }

    /**
     * 设置年龄检测结果。
     *
     * @param age        年龄
     * @param confidence 置信度
     */
    public void setAgeDetected(int age, float confidence) {
        this.age = age;
        this.ageConfidence = confidence;
    }

    /**
     * 设置种族检测结果。
     *
     * @param race       种族
     * @param confidence 置信度
     */
    public void setRaceDetected(String race, float confidence) {
        this.race = race;
        this.raceConfidence = confidence;
    }

    public static HumanPredictResultBuilder builder() {
        return new HumanPredictResultBuilder();
    }

    public static class HumanPredictResultBuilder {

        private final HumanPredictResult r = new HumanPredictResult();

        public HumanPredictResultBuilder processingTimeMs(long t) {
            r.processingTimeMs = t;
            return this;
        }

        public HumanPredictResultBuilder confidence(float c) {
            r.confidence = c;
            return this;
        }

        public HumanPredictResultBuilder value(String v) {
            r.value = v;
            return this;
        }

        public HumanPredictResult build() {
            return r;
        }
    }
}