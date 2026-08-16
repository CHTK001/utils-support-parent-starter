package com.chua.deeplearning.support.recognition;

import com.chua.deeplearning.support.ai.result.HumanPredictResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 年龄 / 性别 / 种族识别上下文。
 *
 * <p>承载一次年龄性别识别的状态：当前图像与已收集的识别结果列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AgeGenderContext {

    /**
     * 当前图像数据。
     */
    private final byte[] imageData;

    /**
     * 已收集的识别结果。
     */
    private final List<HumanPredictResult> results = new ArrayList<>();

    /**
     * 构造。
     *
     * @param imageData 图像数据
     */
    public AgeGenderContext(byte[] imageData) {
        this.imageData = imageData;
    }

    /**
     * 当前图像数据。
     *
     * @return 图像字节
     */
    public byte[] currentImage() {
        return imageData;
    }

    /**
     * 追加识别结果。
     *
     * @param result 结果
     */
    public void addResult(HumanPredictResult result) {
        if (result != null) {
            results.add(result);
        }
    }

    /**
     * 已收集的识别结果。
     *
     * @return 结果列表
     */
    public List<HumanPredictResult> results() {
        return List.copyOf(results);
    }
}
