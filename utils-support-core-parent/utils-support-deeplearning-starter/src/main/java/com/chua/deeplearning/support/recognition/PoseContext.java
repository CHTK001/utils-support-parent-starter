package com.chua.deeplearning.support.recognition;

import com.chua.deeplearning.support.pose.PoseKeypoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 姿态识别上下文。
 *
 * <p>承载一次姿态估计的状态：当前图像与已收集的关键点结果列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PoseContext {

    /**
     * 当前图像数据。
     */
    private final byte[] imageData;

    /**
     * 已收集的关键点结果。
     */
    private final List<List<PoseKeypoint>> results = new ArrayList<>();

    /**
     * 构造。
     *
     * @param imageData 图像数据
     */
    public PoseContext(byte[] imageData) {
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
     * 追加关键点结果。
     *
     * @param result 结果
     */
    public void addResult(List<PoseKeypoint> result) {
        if (result != null) {
            results.add(result);
        }
    }

    /**
     * 已收集的关键点结果。
     *
     * @return 结果列表
     */
    public List<List<PoseKeypoint>> results() {
        return List.copyOf(results);
    }
}
