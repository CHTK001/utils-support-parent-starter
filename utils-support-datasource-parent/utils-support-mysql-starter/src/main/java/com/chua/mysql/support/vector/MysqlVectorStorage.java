package com.chua.mysql.support.vector;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.lang.datasource.dialect.SqlName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 基于 MySQL 原生 向量 类型的向量存储实现。
 * <p>
 * 能力探测以实际执行 {@code STRING_TO_VECTOR} / {@code VECTOR_TO_STRING} 为准：
 * <ul>
 *   <li>支持原生类型：向量列使用 {@code VECTOR(dim)}，写入用 {@code STRING_TO_VECTOR(?)}，
 *       余弦/欧氏距离通过 {@code DISTANCE(vec, STRING_TO_VECTOR(?), 'cosine'|'euclidean')}
 *       交给数据库排序（距离升序即相似度降序）。</li>
 *   <li>不支持（含 MySQL 8.0 与 HeatWave 之外的老版本）：向量列退化为 {@code JSON}，
 *       全量读取后在 JVM 内用 {@link VectorCompareAlgorithm} 精算排序。</li>
 * </ul>
 * 两条路径都把数据落在数据库表内，不会因能力缺失而降级为内存存储导致数据丢失。
 * </p>
 * <p>
 * 点积与自定义算法无法翻译为 MySQL 内置距离函数，一律走 JVM 内精算路径，
 * 因此第三方实现的 {@link VectorCompareAlgorithm} 可直接使用。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * DataSource ds = dataSource; // 从 Spring 容器获取
 * VectorStorage storage = new MysqlVectorStorage(ds, 128, VectorCompareAlgorithm.cosine());
 * storage.add("id1", new float[]{...});
 * List<Vector> results = storage.search(query, 10);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see <a href="https://dev.mysql.com/doc/refman/9.0/en/vector-type.html">MySQL VECTOR Type</a>
 */
public class MysqlVectorStorage extends AbstractVectorStorage {

    private static final Logger log = LoggerFactory.getLogger(MysqlVectorStorage.class);

    /**
     * 相似度分值在元数据中的键名
     */
    private static final String SCORE_KEY = "score";

    /**
     * 数据源
     */
    private final DataSource dataSource;
    /**
     * 表名
     */
    private final String tableName;
    /**
     * 标识 列名
     */
    private final String idColumn;
    /**
     * 向量列名
     */
    private final String vectorColumn;
    /**
     * 表结构是否已就绪
     */
    private volatile boolean schemaInitialized;
    /**
     * MySQL 原生 VECTOR 类型是否可用
     */
    private volatile boolean nativeVector;

    /**
     * 构造 MySQL 向量存储。
     *
     * @param dataSource JDBC 数据源，不能为 null
     * @param dimension  向量维度
     * @param algorithm  比较算法，可为 null（按欧氏距离处理）
     */
    public MysqlVectorStorage(DataSource dataSource, int dimension, VectorCompareAlgorithm algorithm) {
        this(dataSource, dimension, algorithm, new MysqlVectorStorageProperties());
    }

    /**
     * 全参数构造。
     *
     * @param dataSource JDBC 数据源，不能为 null
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param properties 表名与列名配置，为 null 时使用默认值
     */
    public MysqlVectorStorage(DataSource dataSource, int dimension,
                              VectorCompareAlgorithm algorithm,
                              MysqlVectorStorageProperties properties) {
        super(dimension, algorithm);
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须为正: " + dimension);
        }
        MysqlVectorStorageProperties props = properties == null
                ? new MysqlVectorStorageProperties() : properties;
        this.tableName = checkIdentifier(props.tableName());
        this.idColumn = checkIdentifier(props.idColumn());
        this.vectorColumn = checkIdentifier(props.vectorColumn());
        this.dataSource = dataSource;
    }

    /**
     * MySQL 原生 VECTOR 类型是否可用；可用于判断是否走了 JVM 精算路径。
     *
     * @return 原生能力可用返回 true
     */
    public boolean isNativeVector() {
        ensureSchema();
        return nativeVector;
    }

    @Override
    protected boolean doAdd(String id, float[] vector) {
        checkNotClosed();
        ensureSchema();
        String sql = "INSERT IGNORE INTO " + tableName + " (" + idColumn + ", " + vectorColumn
                + ") VALUES (?, " + vectorWriteExpression() + ")";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, toTextLiteral(vector));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("写入向量失败: " + id, e);
        }
    }

    @Override
    protected List<Vector> doSearch(float[] query, int topK) {
        checkNotClosed();
        if (topK <= 0) {
            return List.of();
        }
        ensureSchema();
        String metric = distanceMetric();
        return metric != null ? searchByDatabase(query, topK, metric) : searchInJvm(query, topK);
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
            throw new IllegalStateException("统计向量数量失败", e);
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
            throw new IllegalStateException("删除向量失败: " + id, e);
        }
    }

    @Override
    public int removeByIdPrefix(String idPrefix) {
        checkNotClosed();
        if (idPrefix == null) {
            return 0;
        }
        ensureSchema();
        String sql = "DELETE FROM " + tableName + " WHERE " + idColumn + " LIKE ? ESCAPE '\\\\'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, escapeLike(idPrefix) + "%");
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("按前缀删除向量失败: " + idPrefix, e);
        }
    }

    @Override
    public boolean update(String id, float[] vector) {
        checkNotClosed();
        if (vector.length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.length);
        }
        ensureSchema();
        String sql = "UPDATE " + tableName + " SET " + vectorColumn + " = " + vectorWriteExpression()
                + " WHERE " + idColumn + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toTextLiteral(vector));
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新向量失败: " + id, e);
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
            throw new IllegalStateException("清空向量表失败", e);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 探测原生 向量 能力并建表；建表失败时抛出而不降级为内存存储，避免静默丢数据。
     */
    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        boolean nativeType = probeNativeVectorSupport();
        try (Connection conn = dataSource.getConnection()) {
            if (nativeType) {
                try {
                    createTable(conn, "VECTOR(" + dimension() + ")");
                } catch (SQLException e) {
                    log.info("MySQL VECTOR 类型建表失败，向量列退化为 JSON（检索改由 JVM 精算）: {}",
                            e.getMessage());
                    nativeType = false;
                    createTable(conn, "JSON");
                }
            } else {
                createTable(conn, "JSON");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 MySQL 向量表失败", e);
        }
        this.nativeVector = nativeType;
        this.schemaInitialized = true;
    }

    /**
     * 建立向量表。
     *
     * @param conn       数据库连接
     * @param vectorType 向量列类型
     * @throws SQLException 建表失败
     */
    private void createTable(Connection conn, String vectorType) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + idColumn + " VARCHAR(255) PRIMARY KEY, "
                + vectorColumn + " " + vectorType + " NOT NULL"
                + ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * 探测服务端是否支持原生 向量 函数。
     *
     * @return 支持返回 true
     */
    private boolean probeNativeVectorSupport() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT VECTOR_TO_STRING(STRING_TO_VECTOR('[1,0]'))")) {
            return rs.next() && rs.getString(1) != null;
        } catch (SQLException e) {
            log.info("MySQL 服务端不支持原生 VECTOR 函数，向量列使用 JSON 存储: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 写入时向量列表达式：原生类型走 {@code STRING_TO_VECTOR}，否则把文本转 JSON。
     *
     * @return SQL 表达式
     */
    private String vectorWriteExpression() {
        return nativeVector ? "STRING_TO_VECTOR(?)" : "CAST(? AS JSON)";
    }

    /**
     * 读取时向量列表达式：原生 VECTOR 直接取出是二进制，需转文本才能解析。
     *
     * @return SQL 表达式
     */
    private String vectorReadExpression() {
        return nativeVector ? "VECTOR_TO_STRING(" + vectorColumn + ")" : vectorColumn;
    }

    /**
     * 可下推到数据库的距离度量名；点积、自定义算法或无原生能力时返回 null。
     *
     * @return {@code DISTANCE} 函数的度量参数，无法映射返回 null
     */
    private String distanceMetric() {
        if (!nativeVector) {
            return null;
        }
        VectorCompareAlgorithm algorithm = getAlgorithm();
        String name = algorithm == null ? "EUCLIDEAN" : algorithm.name().toUpperCase();
        return switch (name) {
            case "COSINE" -> "cosine";
            case "EUCLIDEAN" -> "euclidean";
            default -> null;
        };
    }

    /**
     * 由数据库完成距离排序的检索路径。
     *
     * @param query  查询向量
     * @param topK   返回数量
     * @param metric 距离度量名
     * @return 相似度从高到低的结果
     */
    private List<Vector> searchByDatabase(float[] query, int topK, String metric) {
        String sql = "SELECT " + idColumn + ", " + vectorReadExpression() + " FROM " + tableName
                + " ORDER BY DISTANCE(" + vectorColumn + ", STRING_TO_VECTOR(?), '" + metric + "') ASC"
                + " LIMIT ?";
        List<Vector> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toTextLiteral(query));
            ps.setInt(2, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Vector vector = toVector(rs.getString(1), rs.getString(2), query);
                    if (vector != null) {
                        results.add(vector);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("向量检索失败", e);
        }
        return results;
    }

    /**
     * 全量读取后在 JVM 内精算相似度的检索路径，用于 JSON 存储、点积或自定义算法场景。
     *
     * @param query 查询向量
     * @param topK  返回数量
     * @return 相似度从高到低的结果
     */
    private List<Vector> searchInJvm(float[] query, int topK) {
        String sql = "SELECT " + idColumn + ", " + vectorReadExpression() + " FROM " + tableName;
        List<Vector> all = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Vector vector = toVector(rs.getString(1), rs.getString(2), query);
                if (vector != null) {
                    all.add(vector);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("向量检索失败", e);
        }
        all.sort(Comparator.comparingDouble(
                (Vector v) -> ((Number) v.metadata().get(SCORE_KEY)).doubleValue()).reversed());
        return new ArrayList<>(all.subList(0, Math.min(topK, all.size())));
    }

    /**
     * 把数据库返回的一行组装成带相似度分的向量；维度不符（脏数据）时返回 null 跳过。
     *
     * @param id    标识 列值
     * @param raw   向量列文本
     * @param query 查询向量
     * @return 向量结果，脏数据返回 null
     */
    private Vector toVector(String id, String raw, float[] query) {
        float[] data = parseFloatArray(raw);
        if (data == null || data.length != dimension()) {
            log.warn("跳过维度不符的向量: id={}, 期望 {}, 实际 {}",
                    id, dimension(), data == null ? -1 : data.length);
            return null;
        }
        return new Vector(id, data, Map.of(SCORE_KEY, (double) algorithm().compare(query, data)));
    }

    /**
     * 实际用于打分的算法，未指定时按欧氏距离处理。
     *
     * @return 比较算法，不为 null
     */
    private VectorCompareAlgorithm algorithm() {
        VectorCompareAlgorithm algorithm = getAlgorithm();
        return algorithm != null ? algorithm : VectorCompareAlgorithm.euclidean();
    }

    /**
     * 校验 SQL 标识符白名单，杜绝拼接注入。
     *
     * @param name 标识符
     * @return 校验通过的标识符
     */
    private static String checkIdentifier(String name) {
        if (!SqlName.isSafe(name)) {
            throw new IllegalArgumentException("非法 SQL 标识符: " + name);
        }
        return name;
    }

    /**
     * 转义 LIKE 通配符，使前缀按字面量匹配。
     *
     * @param prefix 原始前缀
     * @return 转义后的前缀
     */
    private static String escapeLike(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 将 float 数组序列化为 {@code [0.1,0.2]} 形式的文本，原生类型与 JSON 存储通用。
     * 非有限值（NaN/Infinity）会破坏 JSON 文本，统一写 0。
     *
     * @param vector 向量数组，不能为 null
     * @return 文本字面量
     */
    private static String toTextLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            float value = vector[i];
            sb.append(Float.isFinite(value) ? value : 0f);
        }
        return sb.append(']').toString();
    }

    /**
     * 将 {@code [0.1,0.2]} 形式的文本反序列化为 float 数组。
     *
     * @param text 数据库返回的向量文本，可为 null
     * @return 浮点数组，无法解析返回 null
     */
    private static float[] parseFloatArray(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            return null;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) {
            return new float[0];
        }
        String[] parts = inner.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result;
    }
}
