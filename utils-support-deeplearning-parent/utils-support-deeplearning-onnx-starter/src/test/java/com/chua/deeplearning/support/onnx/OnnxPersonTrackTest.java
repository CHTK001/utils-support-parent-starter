package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.reid.PersonTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONNX 跨帧轨迹管理器测试。
 *
 * <p>验证 ONNX 侧实现与底层归并算法的一致性：配置项正确透传、
 * 归并语义（同人合并 / 同帧不合并 / 跨镜判定）与底层实现一致。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
class OnnxPersonTrackTest {

    /**
     * 构造特征向量。
     *
     * @param values 分量
     * @return 向量
     */
    private static float[] vec(double... values) {
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) values[i];
        }
        return out;
    }

    /**
     * 同人跨摄像头应并入同一条轨迹。
     */
    @Test
    @DisplayName("同人跨摄像头并入同一条轨迹")
    void shouldMergeSamePersonAcrossCameras() {
        OnnxPersonTrack track = new OnnxPersonTrack("")
                .threshold(0.8f)
                .model("arc-face");

        float[] feature = vec(1, 0, 0, 0);
        PersonTrack.Observation first = track.observe("cam-A", "f1", feature, 1000L);
        PersonTrack.Observation second = track.observe("cam-B", "f2", feature, 2000L);

        assertFalse(first.matched());
        assertTrue(second.matched());
        assertEquals(first.trackId(), second.trackId());
        assertEquals(1, track.trackCount());
    }

    /**
     * 同一帧的多张脸不能合并。
     */
    @Test
    @DisplayName("同帧多脸不合并")
    void shouldNotMergeFacesInSameFrame() {
        OnnxPersonTrack track = new OnnxPersonTrack("").threshold(0.8f);

        PersonTrack.Observation first = track.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);
        PersonTrack.Observation second = track.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);

        assertNotEquals(first.trackId(), second.trackId());
        assertEquals(2, track.trackCount());
    }

    /**
     * 阈值配置正确透传到底层归并算法。
     */
    @Test
    @DisplayName("阈值配置生效")
    void shouldApplyThreshold() {
        OnnxPersonTrack track = new OnnxPersonTrack("").threshold(0.95f);

        // 余弦约 0.8 < 0.95 阈值 → 不应合并
        track.observe("cam-A", "f1", vec(1, 0), 1000L);
        PersonTrack.Observation other = track.observe("cam-A", "f2", vec(0.8, 0.6), 2000L);

        assertFalse(other.matched());
        assertEquals(2, track.trackCount());
    }

    /**
     * 轨迹记录全部摄像头并标记跨镜。
     */
    @Test
    @DisplayName("轨迹标记跨镜")
    void shouldMarkCrossCamera() {
        OnnxPersonTrack track = new OnnxPersonTrack("").threshold(0.8f);
        float[] feature = vec(1, 0, 0, 0);

        PersonTrack.Observation first = track.observe("cam-A", "f1", feature, 1000L);
        track.observe("cam-B", "f2", feature, 2000L);
        track.observe("cam-C", "f3", feature, 3000L);

        PersonTrack.TrackSummary summary = track.track(first.trackId());
        assertNotNull(summary);
        assertEquals(3, summary.cameraIds().size());
        assertTrue(summary.crossCamera());
        assertEquals(3, summary.observations());
    }

    /**
     * 归并时间窗配置生效：超窗不合并。
     */
    @Test
    @DisplayName("归并时间窗生效")
    void shouldApplyGapWindow() {
        OnnxPersonTrack track = new OnnxPersonTrack("")
                .threshold(0.8f)
                .gapMillis(5_000L);

        track.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);
        PersonTrack.Observation later = track.observe("cam-A", "f2", vec(1, 0, 0, 0), 20_000L);

        assertFalse(later.matched(), "超过归并窗口应新建轨迹");
        assertEquals(2, track.trackCount());
    }

    /**
     * 轨迹容量配置生效。
     */
    @Test
    @DisplayName("轨迹容量上限生效")
    void shouldApplyMaxTracks() {
        OnnxPersonTrack track = new OnnxPersonTrack("")
                .threshold(0.8f)
                .maxTracks(2);

        track.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);
        track.observe("cam-A", "f2", vec(0, 1, 0, 0), 2000L);
        track.observe("cam-A", "f3", vec(0, 0, 1, 0), 3000L);

        assertEquals(2, track.trackCount());
    }

    /**
     * 检索轨迹返回相似度。
     */
    @Test
    @DisplayName("按特征检索轨迹")
    void shouldSearchTracks() {
        OnnxPersonTrack track = new OnnxPersonTrack("").threshold(0.8f);
        track.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);
        track.observe("cam-A", "f2", vec(0, 1, 0, 0), 2000L);

        List<PersonTrack.TrackMatch> hits = track.search(vec(1, 0, 0, 0), 5);
        assertEquals(2, hits.size());
        assertEquals(1.0d, hits.get(0).similarity(), 1e-6);
    }

    /**
     * 未配置特征模型时给出明确报错，而不是静默失败。
     */
    @Test
    @DisplayName("未配置模型时明确报错")
    void shouldFailFastWithoutModel() {
        OnnxPersonTrack track = new OnnxPersonTrack("");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> track.featureExtractor());
        assertTrue(error.getMessage().contains("arc-face") || error.getMessage().contains("模型"),
                "报错应说明缺模型，实际: " + error.getMessage());
    }

    /**
     * 配置模型后可创建特征提取器。
     */
    @Test
    @DisplayName("配置模型后可取特征提取器")
    void shouldCreateFeatureExtractorWithModel() {
        OnnxPersonTrack track = new OnnxPersonTrack("").model("arc-face");
        // 只验证不抛异常（真正的推理需要模型文件，不在单元测试范围内）
        assertNotNull(track.featureExtractor());
    }

    /**
     * 清空轨迹。
     */
    @Test
    @DisplayName("清空轨迹")
    void shouldClearTracks() {
        OnnxPersonTrack track = new OnnxPersonTrack("");
        track.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);

        track.clear();
        assertEquals(0, track.trackCount());
    }
}
