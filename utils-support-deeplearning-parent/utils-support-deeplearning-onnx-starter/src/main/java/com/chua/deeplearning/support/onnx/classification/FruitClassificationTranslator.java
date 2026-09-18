package com.chua.deeplearning.support.onnx.classification;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
* 水果分类器（零样本）：识别常见水果（苹果、香蕉、橙子、葡萄、草莓等）；适用水果识别、食品分类
*
* @author CH
* @since 4.0.0.47
* @param candidates candidates
* @return 构建参数的结果
 */
public class FruitClassificationTranslator extends SiglipZeroShotClassificationTranslator {

    private static final String FRUITS =
            "apple,banana,orange,grape,strawberry,watermelon,lemon,pineapple,mango," +
            "pear,peach,kiwi,pomegranate,blueberry,raspberry,plum,apricot,fig," +
            /**
            * fruitclassificationtranslator。
            * @param candidates candidates
            * @return 构建参数的结果
            */
            "papaya,passion fruit,coconut,avocado,chanoy";

    public FruitClassificationTranslator() {
        super(buildArgs(FRUITS));
    }

    private static Map<String, Object> buildArgs(String candidates) {
        Map<String, Object> args = new HashMap<>();
        args.put("candidates", candidates);
        return Collections.unmodifiableMap(args);
    }
}
