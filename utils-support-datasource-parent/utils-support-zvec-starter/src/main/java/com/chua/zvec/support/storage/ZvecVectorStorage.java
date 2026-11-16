package com.chua.zvec.support.storage;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.zvec.support.configuration.ZvecStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.zvec.binding.Collection;
import org.zvec.binding.CollectionSchema;
import org.zvec.binding.DataType;
import org.zvec.binding.Doc;
import org.zvec.binding.DocIterator;
import org.zvec.binding.FieldSchema;
import org.zvec.binding.HnswQueryParams;
import org.zvec.binding.IndexParams;
import org.zvec.binding.IteratorOptions;
import org.zvec.binding.MetricType;
import org.zvec.binding.VectorQuery;
import org.zvec.binding.Zvec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Zvec 向量存储实现：一个实例对应磁盘上的一个 zvec 集合目录。
 *
 * <p>映射关系：</p>
 * <ul>
 *   <li>{@code id} → zvec 文档主键；</li>
 *   <li>{@code float[]} → 固定名 {@value #VECTOR_FIELD} 的 FP32 向量字段；</li>
 *   <li>比较算法 → zvec 距离度量：COSINE→COSINE、DOT→IP、其余→L2。</li>
 * </ul>
 *
 * <p>持久化口径（已在本机 Windows 实测）：未 flush 的写入对检索、统计、重新打开均可见，
 * 因此默认不在每次写入后 flush；数据在 {@link #close()} 与 {@link #rebuild()} 时落盘。
 * 进程被强杀时最多丢失最后一次 flush 之后的增量。</p>
 *
 * <p>维度一致性：重新打开已有集合时若向量维度与当前 embedding 不一致，直接抛
 * {@link IllegalStateException}，不静默删除历史数据——删除与否交由业务决定。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class ZvecVectorStorage extends AbstractVectorStorage {

    /**
     * zvec 集合中的向量字段名。
     */
    public static final String VECTOR_FIELD = "vector";

    /**
     * 未显式配置查询 ef 时使用的下限。
     */
    // [P3C A20 豁免] 与 zvec HNSW 查询默认 ef 对齐
    private static final int MIN_QUERY_EF = 64;

    /**
     * 关闭半径过滤：zvec 中 0 表示不按距离半径裁剪候选。
     */
    private static final float RADIUS_DISABLED = 0.0f;

    /**
     * 存储配置。
     */
    private final ZvecStorageProperties properties;

    /**
     * 集合目录绝对路径。
     */
    private final Path path;

    /**
     * 与比较算法对应的 zvec 距离度量。
     */
    private final MetricType metric;

    /**
     * 原生集合句柄。
     */
    private Collection collection;

    /**
     * 是否已关闭。
     */
    private boolean released;

    /**
     * 默认配置构造。
     *
     * @param dimension 向量维度
     * @param algorithm 比较算法
     */
    public ZvecVectorStorage(int dimension, VectorCompareAlgorithm algorithm) {
        this(dimension, algorithm, null);
    }

    /**
     * 全参数构造：打开或创建集合。
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param properties 存储配置，null 时取默认配置
     */
    public ZvecVectorStorage(int dimension,
                             VectorCompareAlgorithm algorithm,
                             ZvecStorageProperties properties) {
        super(dimension, algorithm);
        this.properties = properties != null ? properties : new ZvecStorageProperties();
        if (this.properties.getPath() == null || this.properties.getPath().isBlank()) {
            throw new IllegalArgumentException("zvec 集合目录不能为空");
        }
        this.path = Paths.get(this.properties.getPath()).toAbsolutePath().normalize();
        this.metric = toMetric(algorithm);
        ZvecRuntime.acquire();
        try {
            this.collection = openOrCreate();
        } catch (RuntimeException e) {
            ZvecRuntime.release();
            throw e;
        }
    }

    /**
     * 打开已有集合，或在目录不存在/为空时创建集合。
     *
     * @return 原生集合句柄
     */
    private Collection openOrCreate() {
        if (!Files.exists(path)) {
            return createCollection();
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("zvec 集合路径不是目录: " + path);
        }
        if (isEmptyDirectory()) {
            // zvec 要求 createAndOpen 的路径不存在；空目录不含数据，删除后按新集合创建
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new UncheckedIOException("无法清理空的 zvec 集合目录: " + path, e);
            }
            return createCollection();
        }
        return openExisting();
    }

    /**
     * 判断集合目录是否为空。
     *
     * @return 目录为空时返回 true
     */
    private boolean isEmptyDirectory() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 zvec 集合目录: " + path, e);
        }
    }

    /**
     * 打开已有集合，并校验向量维度与字段约定。
     *
     * @return 原生集合句柄
     */
    private Collection openExisting() {
        Collection existing = Zvec.open(path.toString(), null);
        CollectionSchema schema = null;
        try {
            schema = existing.getSchema();
            FieldSchema field = schema.getVectorField(VECTOR_FIELD);
            if (field == null) {
                throw new IllegalStateException("目录 [" + path + "] 不是本模块创建的 zvec 集合："
                        + "缺少向量字段 " + VECTOR_FIELD + "，实际字段 " + schema.getAllFieldNames());
            }
            int storedDimension = field.getDimension();
            if (storedDimension != dimension()) {
                throw new IllegalStateException("zvec 集合 [" + path + "] 的向量维度为 " + storedDimension
                        + "，与当前 embedding 维度 " + dimension() + " 不一致；"
                        + "请改用匹配维度的模型，或删除该集合目录后重建");
            }
            log.info("[zvec] 已打开集合 path={}, dimension={}", path, storedDimension);
            return existing;
        } catch (RuntimeException e) {
            existing.close();
            throw e;
        } finally {
            if (schema != null) {
                schema.close();
            }
        }
    }

    /**
     * 按当前配置创建集合并打开。
     *
     * @return 原生集合句柄
     */
    private Collection createCollection() {
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("无法创建 zvec 集合父目录: " + parent, e);
            }
        }
        CollectionSchema schema = new CollectionSchema(collectionName());
        try {
            FieldSchema field = new FieldSchema(VECTOR_FIELD, DataType.VECTOR_FP32, false, dimension());
            try {
                IndexParams indexParams = IndexParams.createHNSW(metric,
                        properties.getHnswM(), properties.getEfConstruction());
                try {
                    field.setIndexParams(indexParams);
                } finally {
                    indexParams.close();
                }
                schema.addField(field);
            } finally {
                field.close();
            }
            schema.validate();
            Collection created = Zvec.createAndOpen(path.toString(), schema, null);
            log.info("[zvec] 已创建集合 path={}, dimension={}, metric={}", path, dimension(), metric);
            return created;
        } finally {
            schema.close();
        }
    }

    /**
     * 取集合名：复用目录名，便于运维定位。
     *
     * @return 集合名
     */
    private String collectionName() {
        Path fileName = path.getFileName();
        return fileName == null ? "zvec" : fileName.toString();
    }

    /**
     * 添加向量。
     *
     * @param id     向量标识
     * @param vector 向量数据
     * @return 成功返回 true；标识已存在时返回 false
     */
    @Override
    protected synchronized boolean doAdd(String id, float[] vector) {
        Doc doc = new Doc();
        try {
            doc.setPK(id);
            doc.addVectorFP32Field(VECTOR_FIELD, vector);
            Collection.WriteResult result = collection.insert(List.of(doc));
            flushIfNeeded();
            return result.successCount == 1 && result.errorCount == 0;
        } finally {
            doc.close();
        }
    }

    /**
     * 检索最近邻。
     *
     * <p>zvec 原始 score 的语义随度量变化（COSINE 为相似度、L2 为距离），
     * 这里统一用当前比较算法重算并按相似度降序排列，与内存 / JVector 实现口径一致。</p>
     *
     * @param query 查询向量
     * @param topK  返回数量
     * @return 按相似度降序的向量列表
     */
    @Override
    protected synchronized List<Vector> doSearch(float[] query, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        try (VectorQuery vectorQuery = new VectorQuery()) {
            vectorQuery.setFieldName(VECTOR_FIELD);
            vectorQuery.setTopK(topK);
            vectorQuery.setQueryVector(query);
            vectorQuery.setIncludeVector(true);
            HnswQueryParams params = new HnswQueryParams(
                    searchEf(topK), RADIUS_DISABLED, false, false);
            try {
                // 所有权转移给 query，params 之后不能再 close
                vectorQuery.setHNSWParams(params);
            } catch (RuntimeException e) {
                params.close();
                throw e;
            }
            List<Doc> docs = collection.query(vectorQuery);
            try {
                return toVectors(docs, query, topK);
            } finally {
                Doc.freeDocs(docs);
            }
        }
    }

    /**
     * 计算 HNSW 查询搜索深度：不小于 topK，避免大 topK 时召回不足。
     *
     * @param topK 返回数量
     * @return 查询 ef
     */
    private int searchEf(int topK) {
        int configured = properties.getQueryEf();
        int ef = configured > 0 ? configured : MIN_QUERY_EF;
        return Math.max(ef, topK);
    }

    /**
     * 把 zvec 文档转换为向量结果。
     *
     * @param docs  原生文档列表
     * @param query 查询向量
     * @param topK  返回数量
     * @return 向量列表
     */
    private List<Vector> toVectors(List<Doc> docs, float[] query, int topK) {
        VectorCompareAlgorithm algorithm = getAlgorithm();
        List<Vector> results = new ArrayList<>(docs.size());
        for (Doc doc : docs) {
            String id = doc.getPK();
            float[] data = doc.getVectorFP32Field(VECTOR_FIELD);
            if (id == null || data == null) {
                continue;
            }
            double score = algorithm != null ? algorithm.compare(query, data) : doc.getScore();
            results.add(new Vector(id, data, Map.of("score", score)));
        }
        results.sort(Comparator.comparingDouble(
                (Vector v) -> ((Number) v.metadata().get("score")).doubleValue()).reversed());
        return results.size() > topK ? new ArrayList<>(results.subList(0, topK)) : results;
    }

    /**
     * 获取集合中的向量数量。
     *
     * @return 向量数量
     */
    @Override
    public synchronized int size() {
        checkNotClosed();
        return (int) collection.getStats().getDocCount();
    }

    /**
     * 删除指定 标识 的向量。
     *
     * @param id 向量标识
     * @return 删除成功返回 true
     */
    @Override
    public synchronized boolean remove(String id) {
        checkNotClosed();
        Collection.WriteResult result = collection.delete(List.of(id));
        flushIfNeeded();
        return result.successCount > 0;
    }

    /**
     * 按标识前缀批量删除向量。
     *
     * <p>zvec 的过滤表达式语法未在本模块内验证，故走快照迭代器取主键再批量删除，
     * 行为只依赖「迭代器返回全部主键」这一已实测能力。</p>
     *
     * @param idPrefix 标识前缀
     * @return 实际删除条数
     */
    @Override
    public synchronized int removeByIdPrefix(String idPrefix) {
        checkNotClosed();
        if (idPrefix == null || idPrefix.isEmpty()) {
            return 0;
        }
        List<String> matched = new ArrayList<>();
        try (IteratorOptions options = new IteratorOptions()) {
            options.setOutputFields(new String[0]);
            options.setIncludeVector(false);
            try (DocIterator iterator = collection.createIterator(options)) {
                Doc doc;
                while ((doc = iterator.next()) != null) {
                    try {
                        String id = doc.getPK();
                        if (id != null && id.startsWith(idPrefix)) {
                            matched.add(id);
                        }
                    } finally {
                        doc.close();
                    }
                }
            }
        }
        if (matched.isEmpty()) {
            return 0;
        }
        Collection.WriteResult result = collection.delete(matched);
        flushIfNeeded();
        return (int) result.successCount;
    }

    /**
     * 更新指定 标识 的向量。
     *
     * @param id     向量标识
     * @param vector 新的向量数据
     * @return 更新成功返回 true；标识不存在时返回 false
     */
    @Override
    public synchronized boolean update(String id, float[] vector) {
        checkNotClosed();
        if (vector.length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.length);
        }
        Doc doc = new Doc();
        try {
            doc.setPK(id);
            doc.addVectorFP32Field(VECTOR_FIELD, vector);
            Collection.WriteResult result = collection.update(List.of(doc));
            flushIfNeeded();
            return result.successCount == 1 && result.errorCount == 0;
        } finally {
            doc.close();
        }
    }

    /**
     * 清空集合：销毁磁盘数据后按当前 schema 重建。
     */
    @Override
    public synchronized void clear() {
        checkNotClosed();
        collection.destroy();
        // destroy 已释放并关闭原生句柄，先置空：重建失败时 close() 不会再次操作已销毁的句柄
        collection = null;
        collection = createCollection();
    }

    /**
     * 重建索引：flush 后触发 zvec 后台优化（合并分段、重建索引）。
     */
    @Override
    public synchronized void rebuild() {
        checkNotClosed();
        collection.flush();
        collection.optimize();
    }

    /**
     * 关闭集合，释放原生资源；由本模块初始化的 zvec 原生库在最后一个实例关闭时退出。
     */
    @Override
    public synchronized void close() {
        if (released) {
            return;
        }
        released = true;
        try {
            if (collection != null) {
                try {
                    collection.flush();
                } catch (RuntimeException e) {
                    log.warn("[zvec] 关闭前 flush 失败，可能有未落盘增量: {}", e.getMessage());
                }
                collection.close();
                collection = null;
            }
        } finally {
            ZvecRuntime.release();
            super.close();
        }
    }

    /**
     * 按配置决定是否立即落盘。
     */
    private void flushIfNeeded() {
        if (properties.isFlushOnWrite()) {
            collection.flush();
        }
    }

    /**
     * 比较算法转 zvec 距离度量。
     *
     * @param algorithm 比较算法
     * @return zvec 度量
     */
    private static MetricType toMetric(VectorCompareAlgorithm algorithm) {
        if (algorithm == null) {
            return MetricType.L2;
        }
        return switch (algorithm.name().toUpperCase()) {
            case "COSINE" -> MetricType.COSINE;
            case "DOT", "DOT_PRODUCT" -> MetricType.IP;
            default -> MetricType.L2;
        };
    }
}
