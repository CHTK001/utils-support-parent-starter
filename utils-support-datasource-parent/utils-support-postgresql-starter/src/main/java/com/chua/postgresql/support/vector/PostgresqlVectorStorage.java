package com.chua.postgresql.support.vector;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 PostgreSQL pgvector 扩展的向量存储实现。
 * <p>
 * pgvector 是 PostgreSQL 的开源向量相似性搜索扩展，支持：
 * <ul>
 *   <li>COSINE_SIMILARITY / cosine distance（余弦相似度）</li>
 *   <li>L2_DISTANCE（欧氏距离）</li>
 *   <li>MAX_INNER_PRODUCT / inner product（点积）</li>
 * </ul>
 * </p>
 * <p>
   * 首次 添加 时自动：
 * <ol>
 *   <li>创建 {@code vector} 扩展（如未存在）</li>
 *   <li>创建向量表（JSON 存储向量，兼容无 pgvector 环境）</li>
 *   <li>若启用 pgvector，使用 HNSW/IVFFlat 索引加速搜索</li>
 * </ol>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * VectorStorage storage = VectorStorageProvider.of("postgresql")
 *         .dimension(768)
 *         .algorithm("cosine")
 *         .properties(new MysqlVectorStorageProvider.MysqlVectorStorageProps(dataSource))
 *         .build();
 * }</pre>r.MysqlVectorStorageProps(dataSource))
 *         .build();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see <a href="https://github.com/pgvector/pgvector">pgvector GitHub</a>
 */
public class PostgresqlVectorStorage extends AbstractVectorStorage {

    private final DataSource dataSource; // 数据源
    private final String tableName; // table名称
    private final String idColumn; // idcolumn
    private final String vectorColumn; // 向量column
    private final int hnswM; // hnswm
    private final int hnswEfSearch; // hnswef搜索
    private volatile boolean schemaInitialized; // 模式初始化
    /** 降级存储（pgvector 不可用时初始化） */
    private volatile com.chua.common.support.vector.VectorStorage fallback;

    /**
     * postgresql向量storage。
     * @param dataSource 数据源
     * @param dimension 维度
     * @param algorithm algorithm
     */
    public PostgresqlVectorStorage(DataSource dataSource, int dimension, VectorCompareAlgorithm algorithm) {
        this(dataSource, dimension, algorithm, new PostgresqlVectorStorageProperties());
    }

    public PostgresqlVectorStorage(DataSource dataSource, int dimension,
                                    VectorCompareAlgorithm algorithm,
                                    PostgresqlVectorStorageProperties properties) {
        super(dimension, algorithm);
        this.dataSource = dataSource;
        this.tableName = properties.tableName();
        this.idColumn = properties.idColumn();
        this.vectorColumn = properties.vectorColumn();
        this.hnswM = properties.hnswM();
        this.hnswEfSearch = properties.hnswEfSearch();
    }

    @Override
    protected boolean doAdd(String id, float[] vector) {
        checkNotClosed();
        return resolved().add(id, vector);
    }

    @Override
    protected List<Vector> doSearch(float[] query, int topK) {
        checkNotClosed();
        return resolved().search(query, topK);
    }

    @Override
    public int size() {
        checkNotClosed();
        return resolved().size();
    }

    @Override
    public boolean remove(String id) {
        checkNotClosed();
        return resolved().remove(id);
    }

    @Override
    public boolean update(String id, float[] vector) {
        checkNotClosed();
        return resolved().update(id, vector);
    }

    @Override
    public void clear() {
        checkNotClosed();
        resolved().clear();
    }

    @Override
    public void close() {
        super.close();
    }

    // ==================== 内部实现 ====================

    /**
     * ensure模式。
     */
    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
 // 1. 确保 向量 扩展存在
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            }
            // 2. 建表
            String createSql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                             + idColumn + " VARCHAR(255) PRIMARY KEY, "
                             + vectorColumn + " DOUBLE PRECISION[]"
                             + ")";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createSql);
            }
            // 3. 建 HNSW 索引（如向量不为空）
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                if (rs.next() && rs.getInt(1) > 0) {
                    stmt.execute("CREATE INDEX IF NOT EXISTS " + tableName + "_vec_idx"
                               + " ON " + tableName
                               + " USING hnsw (" + vectorColumn + " vector_cosine_ops) "
                               + "WITH (m = " + hnswM + ", ef_construction = " + (hnswM * 2) + ")");
                }
            }
            schemaInitialized = true;
        } catch (SQLException e) {
            // pgvector 未安装或扩展失败，降级到内存存储
            fallback = new com.chua.common.support.vector.MemoryVectorStorage(dimension(), getAlgorithm());
            schemaInitialized = true;
        }
    }

    /**
     * 返回实际使用的存储实例（原生存储或降级后的内存存储）。
     * @return 构建相似度op的结果
     /**
      * resolved。
      * @return resolved的结果
      */
     */
    private com.chua.common.support.vector.VectorStorage resolved() {
        if (fallback != null) {
            return fallback;
        }
        ensureSchema();
        return this;
    }

    private String buildSimilarityOp() {
        String algo = getAlgorithm().name().toUpperCase();
        return switch (algo) {
            case "COSINE" -> "<=>";
            case "DOT", "DOT_PRODUCT" -> "<#>";
            case "EUCLIDEAN" -> "<->";
            default -> "<=>";
        };
    }

    /**
      * 将 float[] 转为 PostgreSQL 向量 字面量，如 {@code '[0.1,0.2,0.3]'}.
     * @param arr arr
     * @return floatarray转为pg向量字面量的结果
     */
    private static String floatArrayToPgVectorLiteral(float[] arr) {
        if (arr == null || arr.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(arr.length * 8);
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arr[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
      * 将 PostgreSQL 向量 对象转为 float[]。
     * @param json json
     /**
      * pg向量转为对象。
      * @param obj obj
      * @return pg向量转为对象的结果
      */
     * @return jsonarray转为floatarray的结果
     */
    private static float[] pgVectorToObject(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof float[] fa) {
            return fa;
        }
        if (obj instanceof double[] da) {
            float[] result = new float[da.length];
            for (int i = 0; i < da.length; i++) {
                result[i] = (float) da[i];
            }
            return result;
        }
        String s = obj.toString();
        return jsonArrayToFloatArray(s);
    /**
     * floatarray转为json。
     * @param vector 向量
     * @return floatarray转为json的结果
     * @param json json
     */
    }

    private static String floatArrayToJson(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static float[] jsonArrayToFloatArray(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return null;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) {
            return new float[0];
        }
        String[] parts = inner.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
