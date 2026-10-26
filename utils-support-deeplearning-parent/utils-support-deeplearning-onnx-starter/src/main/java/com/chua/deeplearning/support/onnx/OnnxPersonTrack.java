package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.reid.DefaultPersonTrack;
import com.chua.deeplearning.support.reid.PersonTrack;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ONNX 跨帧轨迹管理器（SPI 提供者="onnx"）。
 *
 * <p>与 {@link OnnxFaceDetector} / {@link OnnxFaceRecognizer} 同一范式：
 * 本类只负责<b>把 ONNX 侧的模型配置透传给底层实现</b>，真正的推理与归并
 * 交给 {@link DefaultPersonTrack} 与 ONNX 模型注册表完成。</p>
 *
 * <p><b>为什么轨迹归并本身不放在 ONNX 模块。</b>归并是纯算法（余弦阈值 + 时间窗），
 * 与用哪个推理引擎无关；放在 ONNX 模块里会让「换推理引擎」这件事连带复制一份算法。
 * 因此算法留在 {@code deeplearning-starter}（模型无关），ONNX 侧只提供
 * 「用哪个模型提特征」的绑定。</p>
 *
 * <p>用法：
 * <pre>{@code
 *   PersonTrack track = PersonTrack.create("onnx", "")
 *       .model("arc-face")
 *       .threshold(0.5f)
 *       .observe("cam-A", "frame-1", faceFeature, System.currentTimeMillis());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class OnnxPersonTrack implements PersonTrack {

    /**
     * 默认同人判定阈值。
     */
    private static final float DEFAULT_THRESHOLD = 0.5f;

    /**
     * 默认轨迹容量上限。
     */
    private static final int DEFAULT_MAX_TRACKS = 10000;

    /**
     * 默认轨迹保留时长（毫秒）；0 表示永久。
     */
    private static final long DEFAULT_TTL_MILLIS = 0L;

    /**
     * 默认归并时间窗（毫秒）。
     */
    private static final long DEFAULT_GAP_MILLIS = 30_000L;

    /**
     * API 密钥。
     */
    private final String apiKey;

    /**
     * 特征模型名称。
     */
    private String modelName;

    /**
     * 同人判定阈值。
     */
    private float threshold = DEFAULT_THRESHOLD;

    /**
     * 轨迹容量上限。
     */
    private int maxTracks = DEFAULT_MAX_TRACKS;

    /**
     * 轨迹保留时长（毫秒）。
     */
    private long ttlMillis = DEFAULT_TTL_MILLIS;

    /**
     * 归并时间窗（毫秒）。
     */
    private long gapMillis = DEFAULT_GAP_MILLIS;

    /**
     * 设备类型。
     */
    private String device = "cpu";

    /**
     * 底层归并实现。
     */
    private final DefaultPersonTrack delegate = new DefaultPersonTrack(
            DEFAULT_THRESHOLD, DEFAULT_MAX_TRACKS, DEFAULT_TTL_MILLIS, DEFAULT_GAP_MILLIS);

    /**
     * 构造。
     *
     * @param apiKey API 密钥（本地引擎可空）
     */
    public OnnxPersonTrack(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 设置特征模型。
     *
     * @param model 模型标识
     * @return this
     */
    public OnnxPersonTrack model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 设置同人判定阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    public OnnxPersonTrack threshold(float threshold) {
        this.threshold = threshold;
        this.delegate.setThreshold(threshold);
        return this;
    }

    /**
     * 设置轨迹容量上限。
     *
     * @param maxTracks 容量
     * @return this
     */
    public OnnxPersonTrack maxTracks(int maxTracks) {
        this.maxTracks = maxTracks <= 0 ? DEFAULT_MAX_TRACKS : maxTracks;
        this.delegate.setMaxTracks(this.maxTracks);
        return this;
    }

    /**
     * 设置轨迹保留时长（毫秒）。
     *
     * @param ttlMillis 时长；0 表示永久
     * @return this
     */
    public OnnxPersonTrack ttlMillis(long ttlMillis) {
        this.ttlMillis = Math.max(0L, ttlMillis);
        this.delegate.setTtlMillis(this.ttlMillis);
        return this;
    }

    /**
     * 设置归并时间窗（毫秒）。
     *
     * @param gapMillis 时间窗
     * @return this
     */
    public OnnxPersonTrack gapMillis(long gapMillis) {
        this.gapMillis = Math.max(0L, gapMillis);
        this.delegate.setGapMillis(this.gapMillis);
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备（cpu / cuda）
     * @return this
     */
    public OnnxPersonTrack device(String device) {
        this.device = device;
        return this;
    }

    /**
     * 特征提取器（按当前模型配置创建）。
     *
     * @return 特征提取器
     */
    public FeatureExtractor featureExtractor() {
        return FeatureExtractor.create(resolveModel()).device(device);
    }

    /**
     * 解析特征模型。
     *
     * @return 模型标识
     */
    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定人脸特征模型，请通过 .model(\"模型ID\") 指定，"
                    + "或在系统配置 reid 分组的 feature_model 中选择，可用模型: "
                    + FeatureExtractor.listModels());
        }
        return modelName;
    }

    @Override
    public Observation observe(String cameraId, String sourceId, float[] feature, long timestamp) {
        return delegate.observe(cameraId, sourceId, feature, timestamp);
    }

    @Override
    public TrackSummary track(String trackId) {
        return delegate.track(trackId);
    }

    @Override
    public List<TrackSummary> tracks(String cameraId, boolean crossOnly, int limit) {
        return delegate.tracks(cameraId, crossOnly, limit);
    }

    @Override
    public int trackCount() {
        return delegate.trackCount();
    }

    @Override
    public List<TrackMatch> search(float[] feature, int topK) {
        return delegate.search(feature, topK);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public int evict(long now) {
        return delegate.evict(now);
    }
}
