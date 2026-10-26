package com.chua.deeplearning.support.reid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨帧轨迹管理器测试。
 *
 * <p>覆盖重识别最核心的三条语义：同一个人跨帧跨镜要并入同一条轨迹；
 * 同一帧里的多张脸不能合并；轨迹要记住全部出现过的摄像头。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
class DefaultPersonTrackTest {

    /**
     * 被测轨迹管理器。
     */
    private DefaultPersonTrack track;

    /**
     * 构造。
     */
    @BeforeEach
    void setUp() {
        track = new DefaultPersonTrack(0.8f, 1000, 0L, 30_000L);
    }

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
     * 同一个人在不同摄像头出现，应并入同一条轨迹。
     */
    @Test
    @DisplayName("跨摄像头同人并入同一条轨迹")
    void shouldMergeSamePersonAcrossCameras() {
        float[] feature = vec(1, 0, 0, 0);

        PersonTrack.Observation first = track.observe("cam-A", "frame-1", feature, 1000L);
        assertFalse(first.matched(), "首次出现应为新轨迹");
        assertEquals(1, track.trackCount());

        PersonTrack.Observation second = track.observe("cam-B", "frame-2", feature, 2000L);
        assertTrue(second.matched(), "同人跨镜应命中已有轨迹");
        assertEquals(first.trackId(), second.trackId(), "应并入同一条轨迹");
        assertEquals(1, track.trackCount(), "轨迹数不应增加");
    }

    /**
     * 不同的人应产生不同轨迹。
     */
    @Test
    @DisplayName("不同人产生不同轨迹")
    void shouldSeparateDifferentPeople() {
        track.observe("cam-A", "frame-1", vec(1, 0, 0, 0), 1000L);
        track.observe("cam-A", "frame-2", vec(0, 1, 0, 0), 2000L);

        assertEquals(2, track.trackCount());
    }

    /**
     * 同一帧里的两张脸不能合并成一条轨迹（合影场景）。
     */
    @Test
    @DisplayName("同帧多脸不合并")
    void shouldNotMergeFacesInSameFrame() {
        // 两个特征完全相同（最坏情况：相似度 1.0），但同帧必须分开
        PersonTrack.Observation first = track.observe("cam-A", "frame-1", vec(1, 0, 0, 0), 1000L);
        PersonTrack.Observation second = track.observe("cam-A", "frame-1", vec(1, 0, 0, 0), 1000L);

        assertNotEquals(first.trackId(), second.trackId(), "同一帧内不得合并");
        assertEquals(2, track.trackCount());
    }

    /**
     * 轨迹要记住出现过的全部摄像头，跨镜判定基于集合。
     */
    @Test
    @DisplayName("轨迹记录全部摄像头并标记跨镜")
    void shouldTrackAllCameras() {
        float[] feature = vec(1, 0, 0, 0);
        PersonTrack.Observation first = track.observe("cam-A", "frame-1", feature, 1000L);
        track.observe("cam-B", "frame-2", feature, 2000L);
        track.observe("cam-C", "frame-3", feature, 3000L);

        PersonTrack.TrackSummary summary = track.track(first.trackId());
        assertNotNull(summary);
        assertEquals(3, summary.cameraIds().size(), "应记录三个摄像头");
        assertTrue(summary.cameraIds().containsAll(List.of("cam-A", "cam-B", "cam-C")));
        assertTrue(summary.crossCamera(), "多摄像头应标记为跨镜");
        assertEquals(3, summary.observations(), "应累计三次观测");
        assertEquals(1000L, summary.firstSeen());
        assertEquals(3000L, summary.lastSeen());
    }

    /**
     * 单摄像头的轨迹不算跨镜。
     */
    @Test
    @DisplayName("单摄像头不算跨镜")
    void shouldNotMarkSingleCameraAsCross() {
        PersonTrack.Observation only = track.observe("cam-A", "frame-1", vec(1, 0, 0, 0), 1000L);
        track.observe("cam-A", "frame-2", vec(1, 0, 0, 0), 2000L);

        PersonTrack.TrackSummary summary = track.track(only.trackId());
        assertNotNull(summary);
        assertFalse(summary.crossCamera());
        assertEquals(1, summary.cameraIds().size());
    }

    /**
     * 超过归并时间窗后不再并入（视为换了人）。
     */
    @Test
    @DisplayName("超过时间窗不再并入")
    void shouldNotMergeAfterGap() {
        // 时间窗 30s
        track.observe("cam-A", "frame-1", vec(1, 0, 0, 0), 1000L);
        PersonTrack.Observation later = track.observe("cam-A", "frame-2", vec(1, 0, 0, 0), 1000L + 60_000L);

        assertFalse(later.matched(), "超过归并时间窗应新建轨迹");
        assertEquals(2, track.trackCount());
    }

    /**
     * 相似度低于阈值不合并。
     */
    @Test
    @DisplayName("低于阈值不合并")
    void shouldNotMergeBelowThreshold() {
        // vec(1,0) 与 vec(0.6,0.8) 的余弦约 0.6 < 0.8 阈值
        track.observe("cam-A", "frame-1", vec(1, 0), 1000L);
        PersonTrack.Observation other = track.observe("cam-A", "frame-2", vec(0.6, 0.8), 2000L);

        assertFalse(other.matched());
        assertEquals(2, track.trackCount());
    }

    /**
     * 按特征检索轨迹，返回相似度。
     */
    @Test
    @DisplayName("按特征检索轨迹")
    void shouldSearchByFeature() {
        track.observe("cam-A", "frame-1", vec(1, 0, 0, 0), 1000L);
        track.observe("cam-A", "frame-2", vec(0, 1, 0, 0), 2000L);

        List<PersonTrack.TrackMatch> hits = track.search(vec(1, 0, 0, 0), 5);
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).similarity() > hits.get(1).similarity(), "应按相似度降序");
        assertEquals(1.0d, hits.get(0).similarity(), 1e-6);
    }

    /**
     * 检索只返回指定条数。
     */
    @Test
    @DisplayName("检索遵守 TopK")
    void shouldRespectTopK() {
        track.observe("cam-A", "frame-1", vec(1, 0, 0, 0), 1000L);
        track.observe("cam-A", "frame-2", vec(0, 1, 0, 0), 2000L);
        track.observe("cam-A", "frame-3", vec(0, 0, 1, 0), 3000L);

        assertEquals(2, track.search(vec(1, 0, 0, 0), 2).size());
    }

    /**
     * 维度不同的特征不可比，应跳过而不是报错。
     */
    @Test
    @DisplayName("维度不一致时跳过")
    void shouldSkipMismatchedDimension() {
        track.observe("cam-A", "frame-1", vec(1, 0, 0, 0), 1000L);

        assertTrue(track.search(vec(1, 0), 5).isEmpty(), "维度不一致应无命中");
    }

    /**
     * 容量超限时淘汰最久未出现的轨迹。
     */
    @Test
    @DisplayName("容量超限淘汰旧轨迹")
    void shouldEvictWhenExceedingCapacity() {
        DefaultPersonTrack small = new DefaultPersonTrack(0.8f, 2, 0L, 30_000L);
        small.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);
        small.observe("cam-A", "f2", vec(0, 1, 0, 0), 2000L);
        small.observe("cam-A", "f3", vec(0, 0, 1, 0), 3000L);

        assertEquals(2, small.trackCount(), "应淘汰到容量上限");
    }

    /**
     * 按 TTL 淘汰过期轨迹。
     */
    @Test
    @DisplayName("TTL 到期淘汰轨迹")
    void shouldEvictExpiredByTtl() {
        DefaultPersonTrack ttlTrack = new DefaultPersonTrack(0.8f, 1000, 5_000L, 30_000L);
        ttlTrack.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);

        // evict 返回的是「被淘汰的条数」，不是「剩余条数」
        assertEquals(1, ttlTrack.evict(1000L + 10_000L), "应淘汰 1 条过期轨迹");
        assertEquals(0, ttlTrack.trackCount(), "淘汰后不应剩轨迹");

        // 未到期时不应淘汰
        ttlTrack.observe("cam-A", "f2", vec(1, 0, 0, 0), 1000L);
        assertEquals(0, ttlTrack.evict(2000L), "未到期不应淘汰");
        assertEquals(1, ttlTrack.trackCount());
    }

    /**
     * 按摄像头筛选轨迹。
     */
    @Test
    @DisplayName("按摄像头筛选轨迹")
    void shouldFilterByCamera() {
        float[] feature = vec(1, 0, 0, 0);
        PersonTrack.Observation a = track.observe("cam-A", "f1", feature, 1000L);
        track.observe("cam-B", "f2", feature, 2000L);

        List<PersonTrack.TrackSummary> onlyA = track.tracks("cam-A", false, 0);
        assertEquals(1, onlyA.size());
        assertEquals(a.trackId(), onlyA.get(0).trackId());
    }

    /**
     * 只看跨镜轨迹。
     */
    @Test
    @DisplayName("筛选跨镜轨迹")
    void shouldFilterCrossCameraOnly() {
        float[] feature = vec(1, 0, 0, 0);
        track.observe("cam-A", "f1", feature, 1000L);
        PersonTrack.Observation cross = track.observe("cam-B", "f2", feature, 2000L);

        List<PersonTrack.TrackSummary> crossOnly = track.tracks(null, true, 0);
        assertEquals(1, crossOnly.size());
        assertEquals(cross.trackId(), crossOnly.get(0).trackId());
    }

    /**
     * 清空轨迹。
     */
    @Test
    @DisplayName("清空所有轨迹")
    void shouldClearTracks() {
        track.observe("cam-A", "f1", vec(1, 0, 0, 0), 1000L);
        track.observe("cam-A", "f2", vec(0, 1, 0, 0), 2000L);

        track.clear();
        assertEquals(0, track.trackCount());
        assertNull(track.track("T1"));
    }

    /**
     * 空特征应被拒绝，而不是静默产生坏轨迹。
     */
    @Test
    @DisplayName("空特征被拒绝")
    void shouldRejectEmptyFeature() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> track.observe("cam-A", "f1", new float[0], 1000L));
    }
}
