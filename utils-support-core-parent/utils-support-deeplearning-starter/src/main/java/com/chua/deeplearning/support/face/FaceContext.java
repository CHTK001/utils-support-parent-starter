package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * 人脸管线上下文数据载体。
 *
 * <p>在基于 {@link com.chua.common.support.task.pipeline.core.Pipeline} 的人脸识别管线中，
 * 通过 {@code PipelineContext.attributes} 传递的中间数据，承载单张场景图内
 * 多人脸的检测框、裁剪图、活体结果、特征向量与识别命中结果。</p>
 *
 * <p>一个 {@code FaceContext} 对应一次 {@code FacePipeline.detect/detectLargest} 调用，
 * 内部按检测框索引逐脸推进：每张人脸依次经过 裁剪 →（可选）活体 →（可选）特征 →（可选）检索。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FaceContext {

    /**
     * 原始场景图。
     */
    private final byte[] imageData;

    /**
     * 全部检测框。
     */
    private final List<PredictRectangle> boxes;

    /**
     * 当前处理的人脸框索引。
     */
    private int index;

    /**
     * 已产出的人脸检测命中（检测管线）。
     */
    private final List<FaceDetectionHit> detectionHits = new ArrayList<>();

    /**
     * 已产出的人脸识别命中（识别管线）。
     */
    private final List<FaceIdentifyHit> identifyHits = new ArrayList<>();

    /**
     * 当前人脸框。
     */
    private PredictRectangle currentBox;

    /**
     * 当前人脸裁剪图。
     */
    private byte[] currentFace;

    /**
     * 当前人脸活体结果（live / score）。
     */
    private boolean currentLive = true;

    /**
     * 当前人脸活体分数。
     */
    private float currentLiveScore = 1.0f;

    /**
     * 当前人脸特征向量。
     */
    private float[] currentFeature;

    /**
     * 当前人脸检索命中（识别管线）。
     */
    private List<FaceSearchHit> currentHits = List.of();

    /**
     * 当前对齐后人脸图。
     */
    private byte[] currentAlignedFace;

    /**
     * 当前修复后人脸图。
     */
    private byte[] currentRestoredFace;

    /**
     * 当前高清化后人脸图。
     */
    private byte[] currentEnhancedFace;

    /**
     * 构造上下文。
     *
     * @param imageData 原始场景图
     * @param boxes     全部检测框
     */
    public FaceContext(byte[] imageData, List<PredictRectangle> boxes) {
        this.imageData = imageData;
        this.boxes = boxes == null ? List.of() : new ArrayList<>(boxes);
    }

    /**
     * 原始场景图。
     *
     * @return 图片字节
     */
    public byte[] imageData() {
        return imageData;
    }

    /**
     * 全部检测框。
     *
     * @return 框列表
     */
    public List<PredictRectangle> boxes() {
        return boxes;
    }

    /**
     * 是否有待处理的人脸。
     *
     * @return true 表示还有下一张人脸
     */
    public boolean hasNext() {
        return index < boxes.size();
    }

    /**
     * 推进到下一张人脸，重置单人中间状态。
     *
     * @return true 表示成功推进
     */
    public boolean advance() {
        if (!hasNext()) {
            return false;
        }
        this.currentBox = boxes.get(index++);
        this.currentFace = null;
        this.currentLive = true;
        this.currentLiveScore = 1.0f;
        this.currentFeature = null;
        this.currentHits = List.of();
        this.currentAlignedFace = null;
        this.currentRestoredFace = null;
        this.currentEnhancedFace = null;
        return true;
    }

    /**
     * 当前人脸框。
     *
     * @return 当前框，未推进时为 空
     */
    public PredictRectangle currentBox() {
        return currentBox;
    }

    /**
     * 设置当前人脸裁剪图。
     *
     * @param face 裁剪图
     */
    public void currentFace(byte[] face) {
        this.currentFace = face;
    }

    /**
     * 当前人脸裁剪图。
     *
     * @return 裁剪图
     */
    public byte[] currentFace() {
        return currentFace;
    }

    /**
     * 设置当前人脸活体结果。
     *
     * @param live  是否活体
     * @param score 活体分数
     */
    public void currentLive(boolean live, float score) {
        this.currentLive = live;
        this.currentLiveScore = score;
    }

    /**
     * 当前人脸是否活体。
     *
     * @return 活体标记
     */
    public boolean currentLive() {
        return currentLive;
    }

    /**
     * 当前人脸活体分数。
     *
     * @return 分数
     */
    public float currentLiveScore() {
        return currentLiveScore;
    }

    /**
     * 设置当前人脸特征向量。
     *
     * @param feature 特征
     */
    public void currentFeature(float[] feature) {
        this.currentFeature = feature;
    }

    /**
     * 当前人脸特征向量。
     *
     * @return 特征
     */
    public float[] currentFeature() {
        return currentFeature;
    }

    /**
     * 设置当前人脸检索命中。
     *
     * @param hits 检索命中
     */
    public void currentHits(List<FaceSearchHit> hits) {
        this.currentHits = hits == null ? List.of() : hits;
    }

    /**
     * 当前人脸检索命中。
     *
     * @return 检索命中列表
     */
    public List<FaceSearchHit> currentHits() {
        return currentHits;
    }

    /**
     * 设置当前对齐后人脸图。
     *
     * @param face 对齐人脸图
     */
    public void currentAlignedFace(byte[] face) {
        this.currentAlignedFace = face;
    }

    /**
     * 当前对齐后人脸图。
     *
     * @return 对齐人脸图
     */
    public byte[] currentAlignedFace() {
        return currentAlignedFace;
    }

    /**
     * 设置当前修复后人脸图。
     *
     * @param face 修复人脸图
     */
    public void currentRestoredFace(byte[] face) {
        this.currentRestoredFace = face;
    }

    /**
     * 当前修复后人脸图。
     *
     * @return 修复人脸图
     */
    public byte[] currentRestoredFace() {
        return currentRestoredFace;
    }

    /**
     * 设置当前高清化后人脸图。
     *
     * @param face 高清人脸图
     */
    public void currentEnhancedFace(byte[] face) {
        this.currentEnhancedFace = face;
    }

    /**
     * 当前高清化后人脸图。
     *
     * @return 高清人脸图
     */
    public byte[] currentEnhancedFace() {
        return currentEnhancedFace;
    }

    /**
     * 记录一次检测命中。
     *
     * @param hit 命中
     */
    public void addDetectionHit(FaceDetectionHit hit) {
        this.detectionHits.add(hit);
    }

    /**
     * 检测命中列表。
     *
     * @return 命中列表
     */
    public List<FaceDetectionHit> detectionHits() {
        return detectionHits;
    }

    /**
     * 记录一次识别命中。
     *
     * @param hit 命中
     */
    public void addIdentifyHit(FaceIdentifyHit hit) {
        this.identifyHits.add(hit);
    }

    /**
     * 识别命中列表。
     *
     * @return 命中列表
     */
    public List<FaceIdentifyHit> identifyHits() {
        return identifyHits;
    }
}
