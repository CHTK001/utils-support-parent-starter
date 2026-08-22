package com.chua.deeplearning.support.draw;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 通用图像标注管线 — 一键产生标注结果。
 *
 * <p>作为 OCR / 人脸 / 布局 / 图片等能力的统一绘制门面：
 * 设置目标图 → 注入检测框与标签 → {@link #done()} 一键输出标注图。
 * 处理过程中通过 {@link #onProcess(BiConsumer)} 回调进度。</p>
 *
 * <pre>{@code
 * // 一键模式
 * byte[] annotated = pipeline.withInitDrawer()
 *         .target(imageBytes)
 *         .boxes(boxes, labels)
 *         .done();
 *
 * // 带进度回调
 * byte[] annotated = pipeline.withInitDrawer()
 *         .target(imageBytes)
 *         .onProcess((idx, total) -> log.info("{}/{}", idx, total))
 *         .box(box1, "table")
 *         .box(box2, "text")
 *         .done();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DrawerPipeline {

    /**
     * 目标图（待标注原图）。
     */
    private byte[] targetImage;

    /**
     * 待标注的检测框列表。
     */
    private final List<DetectionInfo> boxes = new ArrayList<>();

    /**
     * 对应的标签列表。
     */
    private final List<String> labels = new ArrayList<>();

    /**
     * 最小置信度阈值。
     */
    private float minConfidence;

    /**
     * 进度回调：参数为（当前序号, 总数）。
     */
    private BiConsumer<Integer, Integer> progress;

    /**
     * 构造标注管线。
     *
     * @param minConfidence 最小置信度
     */
    public DrawerPipeline(float minConfidence) {
        this.minConfidence = minConfidence;
    }

    /**
     * 设置目标图（待标注原图）。
     *
     * @param corrected 目标图字节
     * @return this
     */
    public DrawerPipeline target(byte[] corrected) {
        this.targetImage = corrected;
        return this;
    }

    /**
     * 设置进度回调。
     *
     * <p>每次执行 {@link #done()} 绘制过程中按检测框逐框回调，
     * 参数为（当前序号, 总数）。</p>
     *
     * @param progress 回调
     * @return this
     */
    public DrawerPipeline onProcess(BiConsumer<Integer, Integer> progress) {
        this.progress = progress;
        return this;
    }

    /**
     * 清空当前标注结果与进度回调。
     *
     * @return this
     */
    public DrawerPipeline clean() {
        boxes.clear();
        labels.clear();
        progress = null;
        return this;
    }

    /**
     * 注入单个检测框与标签。
     *
     * @param box   检测框
     * @param label 标签（可为 null）
     * @return this
     */
    public DrawerPipeline box(DetectionInfo box, String label) {
        if (box == null) {
            return this;
        }
        if (box.confidence() < minConfidence) {
            return this;
        }
        boxes.add(box);
        labels.add(label == null ? "" : label);
        return this;
    }

    /**
     * 一键注入检测框与标签列表。
     *
     * <p>两列表长度需一致；标签元素为 null 时忽略。</p>
     *
     * @param boxList 检测框列表
     * @param labelList 标签列表（与检测框一一对应）
     * @return this
     */
    public DrawerPipeline boxes(List<DetectionInfo> boxList, List<? extends String> labelList) {
        if (boxList == null) {
            return this;
        }
        for (int i = 0; i < boxList.size(); i++) {
            DetectionInfo box = boxList.get(i);
            String label = labelList != null && i < labelList.size() ? labelList.get(i) : null;
            box(box, label);
        }
        return this;
    }

    /**
     * 一键注入检测框与标签列表（PredictRectangle 版本）。
     *
     * @param boxList 检测框列表
     * @param labelList 标签列表（与检测框一一对应）
     * @return this
     */
    public DrawerPipeline predictBoxes(List<PredictRectangle> boxList, List<? extends String> labelList) {
        if (boxList == null) {
            return this;
        }
        for (int i = 0; i < boxList.size(); i++) {
            PredictRectangle b = boxList.get(i);
            String label = labelList != null && i < labelList.size() ? labelList.get(i) : null;
            box(new DetectionInfo(
                label == null ? "" : label,
                b.confidence(), b.x(), b.y(), b.width(), b.height(), 0, 0, 0, 0, 0), null);
        }
        return this;
    }

    /**
     * 一键绘制完成，返回标注结果。
     *
     * <p>未设置目标图时返回空数组。绘制过程触发进度回调。</p>
     *
     * @return 标注后 PNG 字节
     */
    public byte[] done() {
        if (targetImage == null || targetImage.length == 0) {
            return new byte[0];
        }
        List<DetectionInfo> boxList = List.copyOf(boxes);
        List<String> labelList = List.copyOf(labels);
        if (progress != null) {
            progress.accept(0, boxList.size());
        }
        byte[] result = ImageUtils.drawDetectionsWithLabels(targetImage, boxList, labelList);
        if (progress != null) {
            progress.accept(boxList.size(), boxList.size());
        }
        return result;
    }

    /**
     * 一键绘制（语义别名，与 {@link #done()} 一致）。
     *
     * @return 标注后 PNG 字节
     */
    public byte[] draw() {
        return done();
    }

    /**
     * 当前检测框列表。
     *
     * @return 检测框列表
     */
    public List<DetectionInfo> boxes() {
        return List.copyOf(boxes);
    }

    /**
     * 当前标签列表。
     *
     * @return 标签列表
     */
    public List<String> labels() {
        return List.copyOf(labels);
    }

    /**
     * 目标图是否已设置。
     *
     * @return true 已设置
     */
    public boolean hasTarget() {
        return targetImage != null && targetImage.length > 0;
    }
}