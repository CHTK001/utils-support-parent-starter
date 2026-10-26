package com.chua.deeplearning.support.reid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 默认跨帧轨迹管理器：基于余弦相似度的阈值归并。
 *
 * <p><b>归并规则</b>：一次观测到来时，与所有「近期活跃」的轨迹比对，
 * 取相似度最高且达到阈值的那条并入；没有达到阈值的则新建轨迹。</p>
 *
 * <p><b>三条关键约束</b>（都是实际踩过的坑，缺一就会出现难以排查的错判）：</p>
 * <ol>
 *   <li><b>同一帧内不合并。</b>合影里的两个人长得再像也是两个人。若不排除同帧，
 *       一张两人合影会凭空生成一条「把两个人连起来」的假轨迹。</li>
 *   <li><b>每次观测独立成条。</b>观测记录携带<b>本次</b>的摄像头、来源帧与时间，
 *       而不是复制首条记录的字段。否则轨迹永远只记得第一次出现的摄像头，
 *       跨镜追踪（最核心的用途）直接失效。</li>
 *   <li><b>轨迹保留全部摄像头集合。</b>跨镜判定基于集合大小，而不是「最后一条的摄像头」。</li>
 * </ol>
 *
 * <p>线程安全：读写锁保护；观测来自多个摄像头的并发回调。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class DefaultPersonTrack implements PersonTrack {

    /**
     * 默认同人判定阈值（余弦相似度）。
     */
    public static final float DEFAULT_THRESHOLD = 0.5f;

    /**
     * 默认轨迹容量上限。
     */
    public static final int DEFAULT_MAX_TRACKS = 10000;

    /**
     * 默认轨迹保留时长（毫秒）；0 表示永久保留。
     */
    public static final long DEFAULT_TTL_MILLIS = 0L;

    /**
     * 默认归并时间窗（毫秒）：超过此时长的轨迹不再参与归并。
     */
    public static final long DEFAULT_GAP_MILLIS = 30_000L;

    /**
     * 轨迹 ID 自增序号。
     */
    private long sequence;

    /**
     * 读锁。
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 轨迹 ID → 轨迹。
     */
    private final Map<String, TrackState> tracks = new LinkedHashMap<>();

    /**
     * 同人判定阈值。
     */
    private volatile float threshold;

    /**
     * 轨迹容量上限。
     */
    private volatile int maxTracks;

    /**
     * 轨迹保留时长（毫秒）。
     */
    private volatile long ttlMillis;

    /**
     * 归并时间窗（毫秒）。
     */
    private volatile long gapMillis;

    /**
     * 构造（使用默认参数）。
     */
    public DefaultPersonTrack() {
        this(DEFAULT_THRESHOLD, DEFAULT_MAX_TRACKS, DEFAULT_TTL_MILLIS, DEFAULT_GAP_MILLIS);
    }

    /**
     * 构造。
     *
     * @param threshold 同人判定阈值
     * @param maxTracks 轨迹容量上限
     * @param ttlMillis 轨迹保留时长（毫秒）；0 表示永久
     * @param gapMillis 归并时间窗（毫秒）
     */
    public DefaultPersonTrack(float threshold, int maxTracks, long ttlMillis, long gapMillis) {
        this.threshold = threshold;
        this.maxTracks = maxTracks <= 0 ? DEFAULT_MAX_TRACKS : maxTracks;
        this.ttlMillis = Math.max(0L, ttlMillis);
        this.gapMillis = Math.max(0L, gapMillis);
    }

    /**
     * 设置同人判定阈值。
     *
     * @param threshold 阈值
     */
    public void setThreshold(float threshold) {
        this.threshold = threshold;
    }

    /**
     * 设置轨迹容量上限。
     *
     * @param maxTracks 容量；≤0 表示恢复默认
     */
    public void setMaxTracks(int maxTracks) {
        this.maxTracks = maxTracks <= 0 ? DEFAULT_MAX_TRACKS : maxTracks;
    }

    /**
     * 设置轨迹保留时长。
     *
     * @param ttlMillis 时长（毫秒）；0 表示永久
     */
    public void setTtlMillis(long ttlMillis) {
        this.ttlMillis = Math.max(0L, ttlMillis);
    }

    /**
     * 设置归并时间窗。
     *
     * @param gapMillis 时间窗（毫秒）
     */
    public void setGapMillis(long gapMillis) {
        this.gapMillis = Math.max(0L, gapMillis);
    }

    @Override
    public Observation observe(String cameraId, String sourceId, float[] feature, long timestamp) {
        if (feature == null || feature.length == 0) {
            throw new IllegalArgumentException("观测特征不能为空");
        }
        lock.writeLock().lock();
        try {
            // 本帧已经出现过的轨迹：不再参与本次归并（合影不合并）
            Set<String> blocked = tracksOfFrame(sourceId);

            String bestId = null;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (TrackState state : tracks.values()) {
                if (blocked.contains(state.id)) {
                    continue;
                }
                // 超过归并时间窗的轨迹不再视为「同一个人还在场」
                if (gapMillis > 0 && timestamp - state.lastSeen > gapMillis) {
                    continue;
                }
                float score = cosine(feature, state.feature);
                if (score > bestScore) {
                    bestScore = score;
                    bestId = state.id;
                }
            }

            if (bestId != null && bestScore >= threshold) {
                TrackState state = tracks.get(bestId);
                state.observe(cameraId, sourceId, timestamp);
                return new Observation(bestId, cameraId, sourceId, feature, bestScore, true, timestamp);
            }

            String id = "T" + (++sequence);
            TrackState state = new TrackState(id, feature);
            state.observe(cameraId, sourceId, timestamp);
            tracks.put(id, state);
            evictIfNeeded(timestamp);
            return new Observation(id, cameraId, sourceId, feature, 1.0d, false, timestamp);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public TrackSummary track(String trackId) {
        if (trackId == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            TrackState state = tracks.get(trackId);
            return state == null ? null : state.summary();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<TrackSummary> tracks(String cameraId, boolean crossOnly, int limit) {
        lock.readLock().lock();
        try {
            List<TrackSummary> out = new ArrayList<>();
            for (TrackState state : tracks.values()) {
                TrackSummary summary = state.summary();
                if (cameraId != null && !cameraId.isBlank() && !summary.cameraIds().contains(cameraId)) {
                    continue;
                }
                if (crossOnly && !summary.crossCamera()) {
                    continue;
                }
                out.add(summary);
            }
            out.sort(Comparator.comparingLong(TrackSummary::lastSeen).reversed());
            return limit > 0 && out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int trackCount() {
        lock.readLock().lock();
        try {
            return tracks.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<TrackMatch> search(float[] feature, int topK) {
        if (feature == null || feature.length == 0) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            List<TrackMatch> matches = new ArrayList<>(tracks.size());
            for (TrackState state : tracks.values()) {
                float score = cosine(feature, state.feature);
                if (score == Float.NEGATIVE_INFINITY) {
                    // 维度不一致：换了特征模型，旧轨迹已不可比，直接跳过
                    continue;
                }
                matches.add(new TrackMatch(state.summary(), score));
            }
            matches.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
            int limit = topK <= 0 ? matches.size() : Math.min(topK, matches.size());
            return limit == matches.size() ? matches : new ArrayList<>(matches.subList(0, limit));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            tracks.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int evict(long now) {
        lock.writeLock().lock();
        try {
            int before = tracks.size();
            if (ttlMillis > 0) {
                tracks.values().removeIf(state -> now - state.lastSeen > ttlMillis);
            }
            evictIfNeeded(now);
            // 差值必须在**全部**淘汰动作完成后再算：
            // evictIfNeeded 里还会因容量上限再淘汰一批，若分两次取差值，
            // 返回的淘汰数会与实际不符（多算或少算）。
            // 返回值语义是「被淘汰了多少条」，不是「还剩多少条」。
            return before - tracks.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 找出本帧已经出现过的轨迹 ID 集合。
     *
     * @param sourceId 来源帧标识
     * @return 轨迹 ID 集合；无来源帧时返回空集
     */
    private Set<String> tracksOfFrame(String sourceId) {
        Set<String> blocked = new LinkedHashSet<>();
        if (sourceId == null || sourceId.isBlank()) {
            return blocked;
        }
        for (TrackState state : tracks.values()) {
            if (sourceId.equals(state.sourceId)) {
                blocked.add(state.id);
            }
        }
        return blocked;
    }

    /**
     * 容量超限时淘汰最久未出现的轨迹。
     *
     * @param now 当前时间戳
     */
    private void evictIfNeeded(long now) {
        if (ttlMillis > 0) {
            tracks.values().removeIf(state -> now - state.lastSeen > ttlMillis);
        }
        while (tracks.size() > maxTracks) {
            String oldest = null;
            long oldestSeen = Long.MAX_VALUE;
            for (TrackState state : tracks.values()) {
                if (state.lastSeen < oldestSeen) {
                    oldestSeen = state.lastSeen;
                    oldest = state.id;
                }
            }
            if (oldest == null) {
                return;
            }
            tracks.remove(oldest);
        }
    }

    /**
     * 余弦相似度。
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 相似度（-1~1）；维度不一致返回 {@link Float#NEGATIVE_INFINITY}
     */
    private static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            // 维度不一致说明换了特征模型，此时任何比较都无意义
            return Float.NEGATIVE_INFINITY;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 1e-12 || nb <= 1e-12) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    /**
     * 一条轨迹的状态。
     */
    private static final class TrackState {

        /**
         * 轨迹 ID。
         */
        private final String id;

        /**
         * 代表特征（首次观测的特征）。
         */
        private final float[] feature;

        /**
         * 出现过的摄像头。
         */
        private final Set<String> cameraIds = new LinkedHashSet<>();

        /**
         * 出现过的来源帧。
         */
        private final Set<String> sourceIds = new LinkedHashSet<>();

        /**
         * 最近一次观测的来源帧。
         */
        private String sourceId;

        /**
         * 首次出现时间。
         */
        private long firstSeen;

        /**
         * 最近出现时间。
         */
        private long lastSeen;

        /**
         * 观测次数。
         */
        private int observations;

        TrackState(String id, float[] feature) {
            this.id = id;
            this.feature = feature.clone();
        }

        /**
         * 记录一次观测。
         *
         * @param cameraId  摄像头标识
         * @param sourceId  来源帧标识
         * @param timestamp 观测时间
         */
        void observe(String cameraId, String sourceId, long timestamp) {
            if (cameraId != null && !cameraId.isBlank()) {
                cameraIds.add(cameraId);
            }
            if (sourceId != null && !sourceId.isBlank()) {
                sourceIds.add(sourceId);
            }
            this.sourceId = sourceId;
            if (observations == 0) {
                firstSeen = timestamp;
            }
            lastSeen = timestamp;
            observations++;
        }

        /**
         * 汇总为对外的轨迹视图。
         *
         * @return 轨迹摘要
         */
        TrackSummary summary() {
            return new TrackSummary(id, new ArrayList<>(cameraIds), new ArrayList<>(sourceIds),
                    firstSeen, lastSeen, observations, cameraIds.size() > 1);
        }
    }
}
