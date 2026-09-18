package com.chua.deeplearning.support.plate;

import com.chua.deeplearning.support.model.PredictRectangle;

import java.util.ArrayList;
import java.util.List;

/**
* 车牌识别上下文。
*
* <p>承载一次整图车牌识别的状态：原始图像、检测框序列、
* 当前处理的车牌（裁剪图 + 检测框），以及已收集的识别结果列表。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class PlateContext {

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
    private byte[] currentPlate;

    /**
    * 已收集的识别结果。
    */
    private final List<PlateDetectHit> hits = new ArrayList<>();

    /**
    * 构造。
    *
    * @param imageData 原始图像
    * @param boxes     检测框序列
    */
    public PlateContext(byte[] imageData, List<PredictRectangle> boxes) {
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
    public byte[] currentPlate() {
        return currentPlate;
    }

    /**
    * 设置当前裁剪车牌图像。
    *
    * @param plate 车牌图像
    */
    public void currentPlate(byte[] plate) {
        this.currentPlate = plate;
    }

    /**
    * 追加识别结果。
    *
    * @param hit 结果
    */
    public void addHit(PlateDetectHit hit) {
        if (hit != null) {
            hits.add(hit);
        }
    }

    /**
    * 已收集的识别结果。
    *
    * @return 结果列表
    */
    public List<PlateDetectHit> hits() {
        return List.copyOf(hits);
    }
}
