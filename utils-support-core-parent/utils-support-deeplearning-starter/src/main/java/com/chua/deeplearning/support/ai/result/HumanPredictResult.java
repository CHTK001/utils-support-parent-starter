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

    /**
    * 创建空的预测结果。
    */
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

    /**
    * 创建结果构建器。
    *
    * @return 构建器实例
    */
    public static HumanPredictResultBuilder builder() {
        return new HumanPredictResultBuilder();
    }

    /**
    * 人像预测结果构建器。
    * @author CH
    * @since 4.0.0
    */
    public static class HumanPredictResultBuilder {

        /**
        * 待构建的结果实例
        */
        private final HumanPredictResult r = new HumanPredictResult();

        /**
        * 设置处理耗时。
        *
        * @param t 处理耗时（毫秒）
        * @return 当前构建器
        */
        public HumanPredictResultBuilder processingTimeMs(long t) {
            r.processingTimeMs = t;
            return this;
        }

        /**
        * 设置置信度。
        *
        * @param c 置信度
        * @return 当前构建器
        */
        public HumanPredictResultBuilder confidence(float c) {
            r.confidence = c;
            return this;
        }

        /**
        * 设置预测值。
        *
        * @param v 预测值
        * @return 当前构建器
        */
        public HumanPredictResultBuilder value(String v) {
            r.value = v;
            return this;
        }

        /**
        * 构建预测结果。
        *
        * @return 预测结果实例
        */
        public HumanPredictResult build() {
            return r;
        }
    }
}
