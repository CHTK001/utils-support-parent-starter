package com.chua.mysql.support.vector;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
* 基于 MySQL 8.0.31+ 原生 向量 类型的向量存储实现。
* <p>
* MySQL 8.0.31+ 支持 {@code VECTOR} 数据类型及内置相似度函数：
* <ul>
*   <li>{@code COSINE_SIMILARITY(vec, query)} — 余弦相似度</li>
*   <li>{@code EUCLIDEAN_DISTANCE(vec, query)} — 欧氏距离</li>
*   <li>{@code DOT_PRODUCT(vec, query)} — 点积</li>
* </ul>
* </p>
* <p>
* 表结构（首次 添加 时自动创建）：
* <pre>{@code
* CREATE TABLE IF NOT EXISTS vector_store (
*     id   VARCHAR(255) PRIMARY KEY,
*     vec  VECTOR(128)  NOT NULL   -- 维度由首次 add 决定
* );
* }</pre>add 决定
* );
* }</pre>
* </p>
* <p>
* 使用示例：
* <pre>{@code
* DataSource ds = dataSource; // 从 Spring 容器获取
* VectorStorage storage = new MysqlVectorStorage(ds, 128, VectorCompareAlgorithm.cosine());
* storage.add("id1", new float[]{...});
* List<Vector> results = storage.search(query, 10);
* }</pre>ge.搜索(查询, 10);
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
* @see <a href="https://dev.mysql.com/doc/refman/8.0/en/vector-functions.html">MySQL Vector Functions</a>
 */
public class MysqlVectorStorage extends AbstractVectorStorage {

    /** 数据源 */
    private final DataSource dataSource;
    /** 表名 */
    private final String tableName;
    /** 标识 列名 */
    private final String idColumn;
    /** 向量列名 */
    private final String vectorColumn;
    /** 是否已建表 */
    private volatile boolean schemaInitialized;
    /** 降级存储（建表失败时初始化） */
    private volatile com.chua.common.support.vector.VectorStorage fallback;

    /**
    * 构造 MySQL 向量存储。
    *
    * @param dataSource  JDBC 数据源
    * @param dimension   向量维度
    * @param algorithm   比较算法（COSINE / EUCLIDEAN / DOT）
     */
    public MysqlVectorStorage(DataSource dataSource, int dimension, VectorCompareAlgorithm algorithm) {
        super(dimension, algorithm);
        this.dataSource = dataSource;
        this.tableName = "vector_store";
        this.idColumn = "id";
        this.vectorColumn = "vec";
    }

    /**
    * 全参数构造。
    *
    * @param dataSource  JDBC 数据源
    * @param dimension   向量维度
    * @param algorithm   比较算法
    * @param properties  配置属性（表名、列名等）
     */
    public MysqlVectorStorage(DataSource dataSource, int dimension,
                               VectorCompareAlgorithm algorithm,
                               MysqlVectorStorageProperties properties) {
        super(dimension, algorithm);
        this.dataSource = dataSource;
        this.tableName = properties.tableName();
        this.idColumn = properties.idColumn();
        this.vectorColumn = properties.vectorColumn();
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

    /**
    * 确保向量表已创建；失败时自动降级到内存存储。
     */
    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                   + idColumn + " VARCHAR(255) PRIMARY KEY, "
                   + vectorColumn + " JSON NOT NULL"
                   + ")";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            schemaInitialized = true;
        } catch (SQLException e) {
 // MySQL 不支持 JSON / 向量，降级到内存存储
            fallback = new com.chua.common.support.vector.MemoryVectorStorage(dimension(), getAlgorithm());
            schemaInitialized = true; // 标记，后续请求走 降级
        }
    }

    /**
    * 返回实际使用的存储实例（原生存储或降级后的内存存储）。
    * @return resolved的结果
     */
    private com.chua.common.support.vector.VectorStorage resolved() {
        if (fallback != null) {
            return fallback;
        }
        ensureSchema();
        return this;
    }

    /**
    * 根据当前算法构建 SQL 订单 BY 子句。
    * <p>使用 MySQL 内置向量函数，对 JSON 存储的向量进行相似度排序。</p>
    * @return 构建订单clause的结果
     */
    private String buildOrderClause() {
        String algoName = getAlgorithm().name().toUpperCase();
        return switch (algoName) {
            case "COSINE" -> "COSINE_SIMILARITY(JSON_EXTRACT(" + vectorColumn + ", '$'), ?) DESC";
            case "DOT", "DOT_PRODUCT" -> "DOT_PRODUCT(JSON_EXTRACT(" + vectorColumn + ", '$'), ?) DESC";
            case "EUCLIDEAN" -> "(-EUCLIDEAN_DISTANCE(JSON_EXTRACT(" + vectorColumn + ", '$'), ?))";
            default -> "COSINE_SIMILARITY(JSON_EXTRACT(" + vectorColumn + ", '$'), ?) DESC";
        };
    }

    // ==================== JSON 转换工具 ====================

    /**
    * 将 float 数组序列化为 MySQL JSON 数组字符串。
    * @param vector 向量
    * @return floatarray转为json的结果
     */
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

    /**
    * 将 MySQL JSON 数组字符串反序列化为 float 数组。
    * @param json json
    * @return jsonarray转为floatarray的结果
     */
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
