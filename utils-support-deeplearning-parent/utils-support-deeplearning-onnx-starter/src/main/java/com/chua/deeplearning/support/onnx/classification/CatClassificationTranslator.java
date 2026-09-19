package com.chua.deeplearning.support.onnx.classification;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 猫分类器（零样本）：判断图像中是否有猫，区分猫与其他对象；适用宠物识别、安防监控
 *
 * @author CH
 * @since 4.0.0.47
 * @param candidates candidates
 * @return 构建参数的结果
 */
public class CatClassificationTranslator extends SiglipZeroShotClassificationTranslator {

    private static final String CATS = "cat,dog,bird,fish,horse,rabbit,hamster," +
            /**
             * catclassificationtranslator。
             * @param candidates candidates
             * @return 构建参数的结果
             */
            "squirrel,deer,raccoon,fox,wolf,lion,tiger,bear";

    /**
     * 构造方法，创建 CatClassificationTranslator 实例。
     */
    public CatClassificationTranslator() {
        super(buildArgs(CATS));
    }

    /**
     * 构建参数。
     *
     * @param candidates 方法入参 candidates
     * @return 结果映射，无数据时为空映射
     */
    private static Map<String, Object> buildArgs(String candidates) {
        Map<String, Object> args = new HashMap<>();
        args.put("candidates", candidates);
        return Collections.unmodifiableMap(args);
    }
}
