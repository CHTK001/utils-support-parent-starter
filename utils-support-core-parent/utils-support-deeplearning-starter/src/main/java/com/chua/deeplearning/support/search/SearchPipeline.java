package com.chua.deeplearning.support.search;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.deeplearning.support.draw.DrawerPipeline;
import com.chua.deeplearning.support.feature.FeatureExtractor;

import java.util.List;
import java.util.Objects;

/**
 * 通用特征检索管线（提取特征 → 向量检索）。
 *
 * <p>基于 {@link Pipeline} 通用管线框架编排，取代手写顺序调用。
 * {@link com.chua.deeplearning.support.face.FaceSearcher}、
 * {@link com.chua.deeplearning.support.image.ImagePipeline} 均委托本管线完成检索。</p>
 *
 * <p><b>入库 / 检索 / 删除三件事都在本类</b>，是「特征向量检索」的统一入口。
 * 各业务（人脸、以图搜图等）只负责自己的元数据与持久化，不再各自手写
 * 「提特征 → 写向量」「写向量失败怎么办」这类编排 —— 手写编排的典型后果是
 * 有的地方漏判返回值、有的地方漏删向量，症状隐蔽且分散在多个模块里。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SearchPipeline {

    /**
     * 节点：提取特征
     */
    private static final String NODE_EXTRACT = "extract";

    /**
     * 节点：检索
     */
    private static final String NODE_SEARCH = "search";

    /**
     * 节点：收集
     */
    private static final String NODE_COLLECT = "collect";

    /**
     * 节点：终止
     */
    private static final String NODE_END = "end";

    /**
     * 特征提取器。
     */
    private final FeatureExtractor featureExtractor;

    /**
     * 向量库。
     */
    private final VectorStorage vectorStorage;

    /**
     * 检索管线实例。
     */
    private final Pipeline pipeline;

    /**
     * 构造。
     *
     * @param featureExtractor 特征提取器
     * @param vectorStorage    向量库
     */
    public SearchPipeline(FeatureExtractor featureExtractor, VectorStorage vectorStorage) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
        this.vectorStorage = Objects.requireNonNull(vectorStorage, "vectorStorage");
        this.pipeline = buildPipeline();
    }

    /**
     * 编排检索管线（提取 → 检索 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("feature-search")
                .task(NODE_EXTRACT, ctx -> {
                    SearchContext sc = current(ctx);
                    sc.feature(featureExtractor.extract(sc.imageData()));
                    return null;
                }).taskEnd()
                .decision("hasFeature", ctx -> current(ctx).feature() != null ? NODE_SEARCH : NODE_END)
                .task(NODE_SEARCH, ctx -> {
                    SearchContext sc = current(ctx);
                    sc.vectors(vectorStorage.search(sc.feature(), sc.topK()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 入库：提取图像特征并写入向量库。
     *
     * @param id 业务标识
     * @param imageData 图像字节
     * @throws IllegalStateException 特征为空或入库失败
     */
    public void enroll(String id, byte[] imageData) {
        float[] feature = featureExtractor.extract(imageData);
        if (feature == null) {
            throw new IllegalStateException("特征提取失败，无法入库: " + id);
        }
        enroll(id, feature);
    }

    /**
     * 入库：直接写入已提取好的特征。
     *
     * <p>调用方往往已经为了确定向量维度而提取过特征，再走
     * {@link #enroll(String, byte[])} 会重复跑一次推理。此重载让「提特征」与
     * 「写向量」解耦，同时把入库校验（空特征、写入失败）收在管线里 ——
     * 否则每个调用方都要自己写一遍这两条判断，早晚写漏。</p>
     *
     * @param id      业务标识
     * @param feature 已提取的特征向量
     * @throws IllegalStateException 特征为空或入库失败
     */
    public void enroll(String id, float[] feature) {
        if (feature == null || feature.length == 0) {
            throw new IllegalStateException("特征为空，无法入库: " + id);
        }
        if (!vectorStorage.add(id, feature)) {
            // 写入返回 false 而不是抛异常，是被调用方的约定；在管线里统一升级成异常，
            // 调用方无须逐处判返回值，也就不会再出现「忽略了 false 当成功」的情况。
            throw new IllegalStateException("向量入库失败: " + id);
        }
    }

    /**
     * 执行检索。
     *
     * @param imageData 查询图
     * @param topK      返回条数
     * @return 命中向量
     */
    public List<Vector> search(byte[] imageData, int topK) {
        SearchContext sc = new SearchContext(imageData, topK);
        PipelineContext<SearchContext> ctx = new PipelineContext<>(pipeline.getId(), sc);
        ctx.setAttribute("search", sc);
        ctx.setNextNodeId(NODE_EXTRACT);
        pipeline.resume(ctx);
        return sc.vectors();
    }

    /**
     * 按已提取的特征检索。
     *
     * <p>与 {@link #enroll(String, float[])} 对称：调用方手里已有特征时
     * 不该为了走管线再解一次图。</p>
     *
     * @param feature 查询特征
     * @param topK    返回条数
     * @return 命中向量
     */
    public List<Vector> search(float[] feature, int topK) {
        if (feature == null || feature.length == 0) {
            return List.of();
        }
        List<Vector> hits = vectorStorage.search(feature, Math.max(1, topK));
        return hits == null ? List.of() : hits;
    }

    /**
     * 移除向量。
     *
     * <p>删除与入库/检索同属管线职责，放在这里是为了让调用方只面对一个入口，
     * 不会出现「入库走管线、删除绕过管线直接找 vectorStorage」这种半统一状态。</p>
     *
     * @param id 业务标识
     * @return 是否确实移除了（索引中本就没有该条目时返回 false）
     */
    public boolean remove(String id) {
        return vectorStorage.remove(id);
    }

    /**
     * 向量库维度。
     *
     * @return 维度；底层不可用时返回 0
     */
    public int dimension() {
        try {
            return vectorStorage.dimension();
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * 从管线上下文提取检索上下文。
     *
     * @param ctx 管线上下文
     * @return 检索上下文
     */
    @SuppressWarnings("unchecked")
    private static SearchContext current(PipelineContext<?> ctx) {
        return (SearchContext) ctx.getAttribute("search");
    }

    /**
     * 创建标注管线，支持一键绘制检测结果。
     *
     * @return DrawerPipeline 实例
     */
    public DrawerPipeline withInitDrawer() {
        return new DrawerPipeline(0.5f);
    }
}
