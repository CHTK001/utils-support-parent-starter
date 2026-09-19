package com.chua.postgresql.support.vector;

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
 * 基于 PostgreSQL pgvector 扩展的向量存储实现。
 * <p>
 * 能力探测按「建表是否成功」判定，而非依赖扩展创建结果：
 * <ul>
 *   <li>pgvector 可用：向量列使用 {@code vector(dim)}，相似度排序交给数据库
 *       （{@code <=>} 余弦距离 / {@code <->} 欧氏距离 / {@code <#>} 负内积），
 *       并尝试建立 HNSW 索引加速。</li>
 *   <li>pgvector 不可用：向量列退化为 {@code double precision[]}，全量读取后在
 *       JVM 内用 {@link VectorCompareAlgorithm} 精确计算并排序。</li>
 * </ul>
 * 两条路径都把数据落在数据库表内，不会因扩展缺失而丢数据。
 * </p>
 * <p>
 * 自定义算法（非内置 COSINE/EUCLIDEAN/DOT）无法翻译为 pgvector 距离运算符，
 * 一律走 JVM 内精算路径，因此第三方实现的 {@link VectorCompareAlgorithm} 可直接使用。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * VectorStorage storage = VectorStorageProvider.of("postgresql")
 *         .dimension(768)
 *         .algorithm("cosine")
 *         .properties(new PostgresqlVectorStorageProvider.PgVectorStorageProps(dataSource))
 *         .build();
 * storage.add("id1", new float[]{...});
 * List<Vector> results = storage.search(query, 10);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see <a href="https://github.com/pgvector/pgvector">pgvector GitHub</a>
 */
public class PostgresqlVectorStorage extends AbstractVectorStorage {

    private static final Logger log = LoggerFactory.getLogger(PostgresqlVectorStorage.class);

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
     * HNSW 索引的 M 参数
     */
    private final int hnswM;
    /**
     * HNSW 搜索时的 ef_search
     */
    private final int hnswEfSearch;
    /**
     * 表结构是否已就绪
     */
    private volatile boolean schemaInitialized;
    /**
     * pgvector 原生 vector 类型是否可用
     */
    private volatile boolean nativeVector;

    /**
     * postgresql 向量 storage。
     *
     * @param dataSource 数据源，不能为 null
     * @param dimension  向量维度
     * @param algorithm  比较算法，可为 null（按欧氏距离处理）
     */
    public PostgresqlVectorStorage(DataSource dataSource, int dimension, VectorCompareAlgorithm algorithm) {
        this(dataSource, dimension, algorithm, new PostgresqlVectorStorageProperties());
    }

    /**
     * 全参数构造。
     *
     * @param dataSource  JDBC 数据源，不能为 null
     * @param dimension   向量维度
     * @param algorithm   比较算法
     * @param properties  表名/列名/索引参数配置，为 null 时使用默认值
     */
    public PostgresqlVectorStorage(DataSource dataSource, int dimension,
                                   VectorCompareAlgorithm algorithm,
                                   PostgresqlVectorStorageProperties properties) {
        super(dimension, algorithm);
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        PostgresqlVectorStorageProperties props = properties == null
                ? new PostgresqlVectorStorageProperties() : properties;
        this.tableName = checkIdentifier(props.tableName());
        this.idColumn = checkIdentifier(props.idColumn());
        this.vectorColumn = checkIdentifier(props.vectorColumn());
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须为正: " + dimension);
        }
        this.hnswM = props.hnswM() > 0 ? props.hnswM() : 16;
        this.hnswEfSearch = props.hnswEfSearch() > 0 ? props.hnswEfSearch() : 40;
        this.dataSource = dataSource;
    }

    /**
     * pgvector 原生 vector 类型是否可用；可用于判断是否走了 JVM 精算路径。
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
        String sql = "INSERT INTO " + tableName + " (" + idColumn + ", " + vectorColumn + ") VALUES (?, "
                + vectorPlaceholder() + ") ON CONFLICT (" + idColumn + ") DO NOTHING";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, nativeVector ? toPgVectorLiteral(vector) : toPgArrayLiteral(vector));
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
        String operator = distanceOperator();
        return operator != null ? searchByDatabase(query, topK, operator)
                : searchInJvm(query, topK);
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
        String sql = "DELETE FROM " + tableName + " WHERE " + idColumn + " LIKE ? ESCAPE '\\'";
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
        String sql = "UPDATE " + tableName + " SET " + vectorColumn + " = " + vectorPlaceholder()
                + " WHERE " + idColumn + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nativeVector ? toPgVectorLiteral(vector) : toPgArrayLiteral(vector));
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
     * 建表并探测 pgvector 能力；失败时抛出而不降级为内存存储，避免静默丢数据。
     */
    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        boolean nativeType = false;
        try (Connection conn = dataSource.getConnection()) {
            tryCreateExtension(conn);
            try {
                createTable(conn, "vector(" + dimension() + ")");
                nativeType = true;
            } catch (SQLException e) {
                log.info("pgvector vector 类型不可用，向量列退化为 double precision[]（检索改由 JVM 精算）: {}",
                        e.getMessage());
                createTable(conn, "double precision[]");
            }
            if (nativeType) {
                tryCreateHnswIndex(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 PostgreSQL 向量表失败", e);
        }
        this.nativeVector = nativeType;
        this.schemaInitialized = true;
    }

    /**
     * 尝试创建 vector 扩展；无权限或扩展包未安装时仅记录日志，由建表结果决定能力。
     *
     * @param conn 数据库连接
     */
    private void tryCreateExtension(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (SQLException e) {
            log.debug("创建 vector 扩展失败（可能已安装或权限不足）: {}", e.getMessage());
        }
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
     * 尝试建立 HNSW 索引；pgvector 版本过旧不支持 HNSW 时仅记录日志。
     *
     * @param conn 数据库连接
     */
    private void tryCreateHnswIndex(Connection conn) {
        String opsClass = hnswOpsClass();
        if (opsClass == null) {
            return;
        }
        String indexName = checkIndexName(
                tableName.substring(tableName.lastIndexOf('.') + 1) + "_vec_idx");
        String sql = "CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + tableName
                + " USING hnsw (" + vectorColumn + " " + opsClass + ")"
                + " WITH (m = " + hnswM + ", ef_construction = " + (hnswM * 2) + ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            log.info("创建 HNSW 索引失败，搜索将使用精确扫描: {}", e.getMessage());
        }
    }

    /**
     * HNSW 索引操作符类，与算法对应；自定义算法无法映射时返回 null（不建索引）。
     *
     * @return 操作符类名，无法映射返回 null
     */
    private String hnswOpsClass() {
        VectorCompareAlgorithm algorithm = getAlgorithm();
        if (algorithm == null) {
            return "vector_cosine_ops";
        }
        return switch (algorithm.name().toUpperCase()) {
            case "COSINE" -> "vector_cosine_ops";
            case "EUCLIDEAN" -> "vector_l2_ops";
            case "DOT", "DOT_PRODUCT", "INNER_PRODUCT" -> "vector_ip_ops";
            default -> null;
        };
    }

    /**
     * 向量列的入参占位符（含类型转换）。
     *
     * @return SQL 片段
     */
    private String vectorPlaceholder() {
        return nativeVector ? "?::vector" : "?::double precision[]";
    }

    /**
     * 可下推到数据库的距离运算符；自定义算法或无 pgvector 时返回 null。
     *
     * @return 距离运算符，无法映射返回 null
     */
    private String distanceOperator() {
        if (!nativeVector) {
            return null;
        }
        VectorCompareAlgorithm algorithm = getAlgorithm();
        if (algorithm == null) {
            return "<=>";
        }
        return switch (algorithm.name().toUpperCase()) {
            case "COSINE" -> "<=>";
            case "EUCLIDEAN" -> "<->";
            case "DOT", "DOT_PRODUCT", "INNER_PRODUCT" -> "<#>";
            default -> null;
        };
    }

    /**
     * 由数据库完成距离排序的检索路径。
     *
     * @param query    查询向量
     * @param topK     返回数量
     * @param operator 距离运算符
     * @return 相似度从高到低的结果
     */
    private List<Vector> searchByDatabase(float[] query, int topK, String operator) {
        String sql = "SELECT " + idColumn + ", " + vectorColumn + " FROM " + tableName
                + " ORDER BY " + vectorColumn + " " + operator + " ?::vector LIMIT ?";
        List<Vector> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            applyHnswEfSearch(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, toPgVectorLiteral(query));
                ps.setInt(2, topK);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Vector vector = toVector(rs.getString(1), rs.getObject(2), query);
                        if (vector != null) {
                            results.add(vector);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("向量检索失败", e);
        }
        return results;
    }

    /**
     * 全量读取后在 JVM 内精算相似度的检索路径，用于无 pgvector 或自定义算法场景。
     *
     * @param query 查询向量
     * @param topK  返回数量
     * @return 相似度从高到低的结果
     */
    private List<Vector> searchInJvm(float[] query, int topK) {
        String sql = "SELECT " + idColumn + ", " + vectorColumn + " FROM " + tableName;
        List<Vector> all = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Vector vector = toVector(rs.getString(1), rs.getObject(2), query);
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
     * HNSW 检索的候选集大小，设置失败不影响正确性。
     *
     * @param conn 数据库连接
     */
    private void applyHnswEfSearch(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET hnsw.ef_search = " + hnswEfSearch);
        } catch (SQLException e) {
            log.debug("设置 hnsw.ef_search 失败: {}", e.getMessage());
        }
    }

    /**
     * 把数据库返回的一行组装成带相似度分的向量；维度不符（脏数据）时返回 null 跳过。
     *
     * @param id   标识 列值
     * @param raw  向量列值
     * @param query 查询向量
     * @return 向量结果，脏数据返回 null
     */
    private Vector toVector(String id, Object raw, float[] query) {
        float[] data = toFloatArray(raw);
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
     * 校验索引名白名单（不允许 schema 限定），杜绝拼接注入。
     *
     * @param name 索引名
     * @return 校验通过的索引名
     */
    private static String checkIndexName(String name) {
        if (!SqlName.isSimple(name)) {
            throw new IllegalArgumentException("非法索引名: " + name);
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
     * 将 float 数组转为 pgvector 向量字面量，如 {@code [0.1,0.2]}。
     *
     * @param arr 向量数组，不能为 null
     * @return 向量字面量
     */
    private static String toPgVectorLiteral(float[] arr) {
        StringBuilder sb = new StringBuilder(arr.length * 8 + 2);
        sb.append('[');
        appendComponents(sb, arr);
        return sb.append(']').toString();
    }

    /**
     * 将 float 数组转为 PostgreSQL 数组字面量，如 {@code {0.1,0.2}}。
     *
     * @param arr 向量数组，不能为 null
     * @return 数组字面量
     */
    private static String toPgArrayLiteral(float[] arr) {
        StringBuilder sb = new StringBuilder(arr.length * 8 + 2);
        sb.append('{');
        appendComponents(sb, arr);
        return sb.append('}').toString();
    }

    /**
     * 追加逗号分隔的分量，非有限值（NaN/Infinity）会破坏 SQL 字面量，统一写 0。
     *
     * @param sb  目标构造器
     * @param arr 向量数组
     */
    private static void appendComponents(StringBuilder sb, float[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            float value = arr[i];
            sb.append(Float.isFinite(value) ? value : 0f);
        }
    }

    /**
     * 将数据库返回的向量对象解析为 float 数组。
     * 兼容 {@code float[]}、{@code double[]} 以及 {@code [1,2]} / {@code {1,2}} 文本形态。
     *
     * @param obj 数据库返回值，可为 null
     * @return 浮点数组，无法解析返回 null
     */
    private static float[] toFloatArray(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof float[] fa) {
            return fa.clone();
        }
        if (obj instanceof double[] da) {
            float[] result = new float[da.length];
            for (int i = 0; i < da.length; i++) {
                result[i] = (float) da[i];
            }
            return result;
        }
        String text = obj.toString().trim();
        if (text.length() < 2) {
            return null;
        }
        char open = text.charAt(0);
        char close = text.charAt(text.length() - 1);
        if ((open != '[' || close != ']') && (open != '{' || close != '}')) {
            return null;
        }
        String inner = text.substring(1, text.length() - 1).trim();
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
