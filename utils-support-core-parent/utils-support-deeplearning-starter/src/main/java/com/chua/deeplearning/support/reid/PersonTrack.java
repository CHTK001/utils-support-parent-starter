package com.chua.deeplearning.support.reid;

import java.util.List;
import java.util.Map;

/**
 * 跨帧轨迹管理器（Person Track）。
 *
 * <p><b>它解决什么问题。</b>向量检索只能回答「这张脸像库里的哪一条」，
 * 但监控场景真正要回答的是「这个人刚才从哪个摄像头走进了哪个摄像头」——
 * 也就是把<b>同一个人的多次出现</b>串成一条<b>轨迹</b>。轨迹管理器就是这层归并逻辑：
 * 每来一次观测（一个摄像头的一帧里的一张脸），它判断「这个人是不是已经见过了」，
 * 是则并入已有轨迹，否则开一条新轨迹。</p>
 *
 * <p><b>为什么是 SPI 而不是写死在门面里。</b>归并策略（阈值、时间窗、容量淘汰、
 * 是否允许跨摄像头合并）依赖具体场景与算力预算：安防场景要跨摄像头合并且留痕久，
 * 而门禁场景可能只关心最近几秒。因此策略可插拔，模型/引擎无关。</p>
 *
 * <p><b>与向量库的关系。</b>向量库（{@code VectorStorage}）负责「特征 → 相似度检索」，
 * 轨迹管理器负责「相似度 → 归并决策」。两者互补：前者是检索原语，后者是时序状态机。</p>
 *
 * <p>实现需保证线程安全：观测通常来自多个摄像头的并发回调。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface PersonTrack {

    /**
     * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static PersonTrack create(String provider, String apiKey) {
        return com.chua.common.support.spi.ServiceProvider.of(PersonTrack.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 一次观测：某个摄像头某帧里看到的一张脸。
     *
     * @param trackId   归并到的轨迹 ID；新轨迹为 null
     * @param cameraId  摄像头标识（跨镜判定的依据，可为空）
     * @param sourceId  来源帧标识（同一帧去重的依据，可为空）
     * @param feature   人脸特征向量
     * @param score     与命中轨迹的相似度；新建轨迹时为 1.0
     * @param matched   是否命中了已有轨迹
     * @param timestamp 观测时间戳（毫秒）
     */
    record Observation(String trackId,
                       String cameraId,
                       String sourceId,
                       float[] feature,
                       double score,
                       boolean matched,
                       long timestamp) {

        /**
         * 规范构造器：特征向量做防御性拷贝。
         *
         * <p>value class 前置条件——数组组件必须深不可变。
         * 特征为 {@code null} 时保留其 {@code null} 语义。</p>
         *
         * @param feature 特征向量
         */
        public Observation {
            feature = feature == null ? null : feature.clone();
        }

        /**
         * 访问器覆写：返回内部特征向量的副本。
         *
         * @return 特征向量副本；无则返回 {@code null}
         */
        @Override
        public float[] feature() {
            return feature == null ? null : feature.clone();
        }
    }

    /**
     * 一条轨迹的状态。
     *
     * @param trackId      轨迹 ID
     * @param cameraIds    出现过的摄像头集合
     * @param sourceIds    出现过的来源帧集合
     * @param firstSeen    首次出现时间
     * @param lastSeen     最近出现时间
     * @param observations 观测次数
     * @param crossCamera  是否跨摄像头出现
     */
    record TrackSummary(String trackId,
                        List<String> cameraIds,
                        List<String> sourceIds,
                        long firstSeen,
                        long lastSeen,
                        int observations,
                        boolean crossCamera) {

        /**
         * 规范构造器：集合做不可变化处理。
         *
         * @param cameraIds 摄像头集合
         * @param sourceIds 来源集合
         */
        public TrackSummary {
            cameraIds = cameraIds == null ? List.of() : List.copyOf(cameraIds);
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    /**
     * 轨迹命中：轨迹摘要 + 相似度。
     *
     * @param summary   轨迹摘要
     * @param similarity 相似度（越大越像）
     */
    record TrackMatch(TrackSummary summary, double similarity) {
    }

    /**
     * 记录一次观测：命中已有轨迹则并入，否则新建轨迹。
     *
     * @param cameraId 摄像头标识，可为空
     * @param sourceId 来源帧标识，可为空
     * @param feature  人脸特征向量
     * @param timestamp 观测时间戳（毫秒）
     * @return 归并结果（轨迹 ID、是否命中、相似度）
     */
    Observation observe(String cameraId, String sourceId, float[] feature, long timestamp);

    /**
     * 按 ID 取轨迹。
     *
     * @param trackId 轨迹 ID
     * @return 轨迹；不存在返回 {@code null}
     */
    TrackSummary track(String trackId);

    /**
     * 列出轨迹。
     *
     * @param cameraId 只看某个摄像头的轨迹；为空表示全部
     * @param crossOnly 只看跨摄像头轨迹
     * @param limit    返回上限；≤0 表示不限
     * @return 轨迹列表（按最近出现时间倒序）
     */
    List<TrackSummary> tracks(String cameraId, boolean crossOnly, int limit);

    /**
     * 轨迹总数。
     *
     * @return 数量
     */
    int trackCount();

    /**
     * 按特征检索最相似的轨迹。
     *
     * <p>轨迹摘要不含特征向量（对外只暴露摄像头、时间等元信息），
     * 因此「以图搜人」这类能力必须由轨迹管理器自己完成比对，
     * 否则调用方拿不到可用的相似度。</p>
     *
     * @param feature 查询特征
     * @param topK    返回条数
     * @return 命中的轨迹（按相似度降序）
     */
    List<TrackMatch> search(float[] feature, int topK);

    /**
     * 清空所有轨迹。
     */
    void clear();

    /**
     * 淘汰过期轨迹（按保留时长与容量上限）。
     *
     * <p>供宿主定时调用；不调用也不影响正确性，只是内存会持续增长。</p>
     *
     * @param now 当前时间戳（毫秒）
     * @return 被淘汰的轨迹数
     */
    int evict(long now);
}
