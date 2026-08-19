package com.chua.deeplearning.support.ocr;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.utils.ImageUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR 标注管线 — 独立管理检测结果的绘制生命周期。
 *
 * <p>通过 {@link OcrPipeline#withInitDrawer()} 创建，支持自定义绘制流程：</p>
 * <pre>{@code
 * OcrPipeline ocr = OcrPipeline.builder()...build();
 * OcrPipeline.DrawerPipeline drawer = ocr.withInitDrawer();
 * drawer.target(imageData)       // 设置目标图
 *        .onProcess(box, result) // 逐框处理
 *        .done();                // 完成绘制，返回标注图
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DrawerPipeline {

    /**
     * 矫正后的目标图
     */
    private byte[] corrected;

    /**
     * 待标注的检测框列表
     */
    private final List<DetectionInfo> boxes = new ArrayList<>();

    /**
     * 对应的标签列表
     */
    private final List<String> labels = new ArrayList<>();

    /**
     * 最小置信度阈值
     */
    private float minConfidence;

    /**
     * 构造标注管线。
     *
     * @param minConfidence 最小置信度
     */
    public DrawerPipeline(float minConfidence) {
        this.minConfidence = minConfidence;
    }

    /**
     * 设置目标图（矫正后的图片）。
     *
     * @param corrected 矫正后图片
     * @return this
     */
    public DrawerPipeline target(byte[] corrected) {
        this.corrected = corrected;
        return this;
    }

    /**
     * 清空当前标注结果。
     *
     * @return this
     */
    public DrawerPipeline clean() {
        boxes.clear();
        labels.clear();
        return this;
    }

    /**
     * 处理单个检测框，添加到标注列表。
     *
     * @param box   检测框
     * @param text  识别文字
     * @param conf  识别置信度
     * @return this
     */
    public DrawerPipeline onProcess(DetectionInfo box, String text, float conf) {
        if (conf >= minConfidence && text != null && !text.isEmpty()) {
            boxes.add(box);
            labels.add(text + " " + String.format("%.2f", conf));
        }
        return this;
    }

    /**
     * 完成绘制，返回标注图。
     *
     * @return 标注后 JPEG 字节；无目标图时返回空数组
     */
    public byte[] done() {
        if (corrected == null) {
            return new byte[0];
        }
        return ImageUtils.drawDetectionsWithLabels(corrected, boxes, labels);
    }

    /**
     * 获取当前检测框列表。
     *
     * @return 检测框列表
     */
    public List<DetectionInfo> boxes() {
        return boxes;
    }

    /**
     * 获取当前标签列表。
     *
     * @return 标签列表
     */
    public List<String> labels() {
        return labels;
    }
}