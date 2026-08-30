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
 * 首次 add 时自动：
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
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see <a href="https://github.com/pgvector/pgvector">pgvector GitHub</a>
 */
public class PostgresqlVectorStorage extends AbstractVectorStorage {

    private final DataSource dataSource;
    private final String tableName;
    private final String idColumn;
    private final String vectorColumn;
    private final int hnswM;
    private final int hnswEfSearch;
    private volatile boolean schemaInitialized;

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
        ensureSchema();
        String json = floatArrayToJson(vector);
        String sql = "INSERT INTO " + tableName + " (" + idColumn + ", " + vectorColumn + ") "
                   + "VALUES (?, ?) ON CONFLICT (" + idColumn + ") DO UPDATE SET "
                   + vectorColumn + " = EXCLUDED." + vectorColumn;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, json);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("PostgreSQL 向量添加失败: id=" + id, e);
        }
    }

    @Override
    protected List<Vector> doSearch(float[] query, int topK) {
        checkNotClosed();
        ensureSchema();
        String vecLiteral = floatArrayToPgVectorLiteral(query);
        String algoOp = buildSimilarityOp();
        String sql = "SELECT " + idColumn + ", " + vectorColumn
                   + " FROM " + tableName
                   + " ORDER BY " + vectorColumn + " " + algoOp + " ?::vector"
                   + " LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, query);
            ps.setInt(2, topK);
            List<Vector> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(idColumn);
                    Object vecObj = rs.getObject(vectorColumn);
                    float[] vec = pgVectorToObject(vecObj);
                    if (vec != null) {
                        double score = getAlgorithm().compare(query, vec);
                        results.add(new Vector(id, vec, Map.of("score", score)));
                    }
                }
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("PostgreSQL 向量搜索失败", e);
        }
    }

    @Override
    public int size() {
        checkNotClosed();
        ensureSchema();
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("PostgreSQL 向量计数失败", e);
        }
    }

    @Override
    public boolean remove(String id) {
        checkNotClosed();
        ensureSchema();
        String sql = "DELETE FROM " + tableName + " WHERE " + idColumn + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("PostgreSQL 向量删除失败: id=" + id, e);
        }
    }

    @Override
    public boolean update(String id, float[] vector) {
        checkNotClosed();
        ensureSchema();
        String json = floatArrayToJson(vector);
        String sql = "UPDATE " + tableName + " SET " + vectorColumn + " = ? WHERE " + idColumn + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, json);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("PostgreSQL 向量更新失败: id=" + id, e);
        }
    }

    @Override
    public void clear() {
        checkNotClosed();
        ensureSchema();
        String sql = "DELETE FROM " + tableName;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("PostgreSQL 向量清空失败", e);
        }
    }

    @Override
    public void close() {
        super.close();
    }

    // ==================== 内部实现 ====================

    private synchronized void ensureSchema() {
        if (schemaInitialized) return;
        try (Connection conn = dataSource.getConnection()) {
            // 1. 确保 vector 扩展存在
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
            throw new RuntimeException("PostgreSQL 向量建表失败: " + tableName, e);
        }
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
     * 将 float[] 转为 PostgreSQL vector 字面量，如 {@code '[0.1,0.2,0.3]'}.
     */
    private static String floatArrayToPgVectorLiteral(float[] arr) {
        if (arr == null || arr.length == 0) return "[]";
        StringBuilder sb = new StringBuilder(arr.length * 8);
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 将 PostgreSQL vector 对象转为 float[]。
     */
    private static float[] pgVectorToObject(Object obj) {
        if (obj == null) return null;
        if (obj instanceof float[] fa) return fa;
        if (obj instanceof double[] da) {
            float[] result = new float[da.length];
            for (int i = 0; i < da.length; i++) result[i] = (float) da[i];
            return result;
        }
        String s = obj.toString();
        return jsonArrayToFloatArray(s);
    }

    private static String floatArrayToJson(float[] vector) {
        if (vector == null || vector.length == 0) return "[]";
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static float[] jsonArrayToFloatArray(String json) {
        if (json == null || json.isBlank()) return null;
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null;
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return new float[0];
        String[] parts = inner.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
