package com.chua.deeplearning.support.ocr;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageUtils;
import java.util.ArrayList;
import java.util.List;

/**
* OCR 标注管线 — 自动匹配检测框与识别结果，支持进度回调。
*
* <p>通过 {@link OcrPipeline#withInitDrawer()} 创建，完成标注图的绘制。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DrawerPipeline {

    /**
    * 处理进度回调接口。
    *
    * @param box   当前检测框
    * @param text  识别文字
    * @param conf  识别置信度
    * @param index 当前进度（从 0 开始）
    * @param total 总检测框数
    * @author CH
    * @since 4.0.0
    */
    @FunctionalInterface
    public interface ProcessCallback {
        /**
        * 每处理一个检测框时调用。
        *
        * @param box   检测框
        * @param text  识别文字（空串表示未匹配）
        * @param conf  置信度
        * @param index 当前索引
        * @param total 总数
        */
        void onProcess(DetectionInfo box, String text, float conf, int index, int total);
    }

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
    * 进度回调
    */
    private ProcessCallback callback;

    /**
    * 构造标注管线。
    *
    * @param minConfidence 最小置信度
    * @return DrawerPipeline的结果
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
    * 设置检测框与识别结果，自动匹配并标注。
    *
    * @param allBoxes 检测框列表
    * @param results  识别结果列表
    * @return this
    */
    public DrawerPipeline boxes(List<DetectionInfo> allBoxes, List<OcrResult> results) {
        this.boxes.clear();
        this.labels.clear();
        int total = allBoxes.size();
        for (int i = 0; i < total; i++) {
            DetectionInfo b = allBoxes.get(i);
            String bestText = "";
            float bestConf = 0;
            double bestDist = Double.MAX_VALUE;
            double bx = b.x() + b.width() / 2.0, by = b.y() + b.height() / 2.0;
            for (OcrResult r : results) {
                PredictRectangle rb = r.boundingBox();
                double rx = rb.x() + rb.width() / 2.0, ry = rb.y() + rb.height() / 2.0;
                double dist = Math.abs(bx - rx) + Math.abs(by - ry);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestText = r.text();
                    bestConf = r.confidence();
                }
            }
            double maxDist = (b.width() + b.height()) * 0.5;
            boolean textMatched = bestDist <= maxDist && !bestText.isEmpty();
            boolean confOk = bestConf >= minConfidence || minConfidence <= 0f;
            if (textMatched && confOk) {
                this.boxes.add(b);
                this.labels.add(bestText + " " + String.format("%.2f", bestConf));
            } else if (!textMatched && confOk) {
                this.boxes.add(b);
                this.labels.add(String.format("%.2f", b.confidence()));
            }
            if (callback != null) {
                callback.onProcess(b, textMatched ? bestText : "", bestConf, i, total);
            }
        }
        return this;
    }

    /**
    * 设置进度回调。
    *
    * @param callback 回调函数
    * @return this
    */
    public DrawerPipeline onProcess(ProcessCallback callback) {
        this.callback = callback;
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
