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
 * {@link com.chua.deeplearning.support.image.ImageSearcher} 均委托本管线完成检索。</p>
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
        if (!vectorStorage.add(id, feature)) {
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
