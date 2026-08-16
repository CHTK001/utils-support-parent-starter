package com.chua.deeplearning.support.recognition;

import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.plate.PlateResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 车牌号识别上下文。
 *
 * <p>承载一次整图车牌号识别的状态：原始图像、检测框序列、当前处理的车牌
 * （裁剪图 + 检测框），以及已收集的识别结果列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PlateNumberContext {

    /**
     * 原始图像数据。
     */
    private final byte[] imageData;

    /**
     * 检测框序列。
     */
    private final List<PredictRectangle> boxes;

    /**
     * 当前处理下标，由外层循环驱动。
     */
    private int index;

    /**
     * 当前检测框。
     */
    private PredictRectangle currentBox;

    /**
     * 当前裁剪车牌图像。
     */
    private byte[] currentCrop;

    /**
     * 已收集的识别结果。
     */
    private final List<PlateResult> results = new ArrayList<>();

    /**
     * 构造。
     *
     * @param imageData 原始图像
     * @param boxes     检测框序列
     */
    public PlateNumberContext(byte[] imageData, List<PredictRectangle> boxes) {
        this.imageData = imageData;
        this.boxes = boxes == null ? List.of() : boxes;
    }

    /**
     * 是否有下一个车牌。
     *
     * @return true 表示进入下一个
     */
    public boolean advance() {
        if (index >= boxes.size()) {
            return false;
        }
        currentBox = boxes.get(index++);
        return true;
    }

    /**
     * 原始图像。
     *
     * @return 图像字节
     */
    public byte[] imageData() {
        return imageData;
    }

    /**
     * 当前检测框。
     *
     * @return 检测框
     */
    public PredictRectangle currentBox() {
        return currentBox;
    }

    /**
     * 当前裁剪车牌图像。
     *
     * @return 车牌图像
     */
    public byte[] currentCrop() {
        return currentCrop;
    }

    /**
     * 设置当前裁剪车牌图像。
     *
     * @param crop 车牌图像
     */
    public void currentCrop(byte[] crop) {
        this.currentCrop = crop;
    }

    /**
     * 追加识别结果。
     *
     * @param result 结果
     */
    public void addResult(PlateResult result) {
        if (result != null) {
            results.add(result);
        }
    }

    /**
     * 已收集的识别结果。
     *
     * @return 结果列表
     */
    public List<PlateResult> results() {
        return List.copyOf(results);
    }
}
