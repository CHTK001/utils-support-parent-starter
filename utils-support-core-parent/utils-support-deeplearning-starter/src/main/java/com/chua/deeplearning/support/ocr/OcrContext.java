package com.chua.deeplearning.support.ocr;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;

import java.util.ArrayList;
import java.util.List;

/**
* OCR 识别上下文。
*
* <p>承载一次整图 OCR 的状态：原始图像、检测框序列（已按阅读顺序排序）、
* 当前处理的文本块（裁剪图 + 检测框），以及已收集的识别结果列表。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class OcrContext {

    /**
    * 原始图像数据。
     */
    private final byte[] imageData;

    /**
    * 检测框序列（已按阅读顺序排序）。
     */
    private final List<DetectionInfo> boxes;

    /**
    * 当前处理下标，由外层循环驱动。
     */
    private int index;

    /**
    * 当前检测框。
     */
    private DetectionInfo currentBox;

    /**
    * 当前裁剪文本块图像。
     */
    private byte[] currentCrop;

    /**
    * 已收集的识别结果。
     */
    private final List<OcrResult> results = new ArrayList<>();

    /**
    * 图像是否模糊（质量门控判定），模糊时强制启用文字高清修复。
     */
    private boolean blurry;

    /**
    * 构造。
    *
    * @param imageData 原始图像
    * @param boxes     检测框序列
     */
    public OcrContext(byte[] imageData, List<DetectionInfo> boxes) {
        this.imageData = imageData;
        this.boxes = boxes == null ? List.of() : boxes;
    }

    /**
    * 是否模糊。
    *
    * @return true 表示模糊
     */
    public boolean blurry() {
        return blurry;
    }

    /**
    * 设置模糊标记。
    *
    * @param blurry 模糊标记
     */
    public void blurry(boolean blurry) {
        this.blurry = blurry;
    }

    /**
    * 是否有下一个文本块。
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
    public DetectionInfo currentBox() {
        return currentBox;
    }

    /**
    * 当前裁剪文本块。
    *
    * @return 文本块图像
     */
    public byte[] currentCrop() {
        return currentCrop;
    }

    /**
    * 设置当前裁剪文本块。
    *
    * @param crop 文本块图像
     */
    public void currentCrop(byte[] crop) {
        this.currentCrop = crop;
    }

    /**
    * 追加识别结果。
    *
    * @param result 结果
     */
    public void addResult(OcrResult result) {
        if (result != null) {
            results.add(result);
        }
    }

    /**
    * 已收集的识别结果。
    *
    * @return 结果列表
     */
    public List<OcrResult> results() {
        return List.copyOf(results);
    }

    /**
    * 检测框数量。
    *
    * @return 数量
     */
    public int size() {
        return boxes.size();
    }

    /**
    * 当前框转换为矩形。
    *
    * @return 矩形
     */
    public PredictRectangle currentRectangle() {
        DetectionInfo box = currentBox;
        if (box == null) {
            return null;
        }
        return new PredictRectangle(box.x(), box.y(), box.width(), box.height(),
                box.confidence(), 0, box.label());
    }
}
