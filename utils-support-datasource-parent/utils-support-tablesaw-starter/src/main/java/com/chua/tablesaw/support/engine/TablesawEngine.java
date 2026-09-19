package com.chua.tablesaw.support.engine;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.DateColumn;
import tech.tablesaw.api.DateTimeColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.FloatColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvReadOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Tablesaw 数据引擎实现，基于 Tablesaw 数据处理引擎提供文件数据源的 ORM 查询能力。
 * <p>
 * 通过 {@link #load(String, String)} 加载 CSV 文件，使用 lambda查询包装器 / lambda更新包装器
 * 提供标准的 Engine ORM 接口，支持条件过滤、排序、列投影、分页（limit / offset）与内存表的
 * 更新、删除。
 * </p>
 * <p>
 * 语义约束（生产使用须注意）：
 * <ul>
 *   <li>{@link #store(String, List)}、{@link #load(String, String)}、更新与删除均作用于
 *       内存表，<b>不会自动回写文件</b>；需要落盘请显式调用 {@link #save(String, String)}。</li>
 *   <li>实体类需具备无参构造函数，且属性有 setter 或可写字段，否则查询映射阶段显式抛错，
 *       不再静默返回空对象。</li>
 *   <li>未注册对应表、条件列 / 投影列 / 排序列不存在、写操作取值失败等配置错误一律显式抛
 *       {@link IllegalStateException}，避免静默返回空集造成误判。</li>
 *   <li>JOIN、GROUP BY / HAVING、聚合投影等依赖 SQL 引擎的能力不受支持，调用即抛
 *       {@link UnsupportedOperationException}。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("tablesaw")
public class TablesawEngine implements Engine {

    /**
     * Tablesaw 表格数据存储映射表
     * <p>
     * 键 为数据源名称或表名，值 为对应的 Tablesaw Table 对象。
     * </p>
     */
    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    /**
     * 引擎数据源映射表
     * <p>
     * 存储所有通过 {@link #addDataSource(String, EngineDataSource)} 注册的数据源。
     * </p>
     */
    private final Map<String, EngineDataSource<Object>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名称
     * <p>
     * 当未指定数据源名称时，使用此名称对应的数据源进行操作。
     * </p>
     */
    private String defaultDataSourceName;

    /**
     * 引擎关闭标记
     * <p>
     * {@link #close()} 后置为 true，此后一切读写入口直接抛 {@link IllegalStateException}，
     * 防止向已释放的引擎写入导致数据静默丢失。
     * </p>
     */
    private volatile boolean closed;

    /**
     * 实体类与内存表的直连索引
     * <p>
     * {@link #store(String, List)} 按实体类登记，使 {@code query(实体类)} 能稳定读回同一张表，
     * 不依赖表名拼写；同一实体多次存储时以最后一次为准。仅按名称加载的 CSV 表不入此索引。
     * </p>
     */
    private final Map<Class<?>, Table> entityTables = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 添加数据源
    */
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        checkOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("数据源不能为空: " + name);
        }
        dataSources.put(name, (EngineDataSource<Object>) dataSource);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    @Override
    /**
     * 设置默认数据源名称
    */
    public Engine setDefaultDataSourceName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("默认数据源名称不能为空");
        }
        this.defaultDataSourceName = name;
        return this;
    }

    /**
     * 获取默认数据源名称。
     *
     * @return 默认数据源名称
     */
    @Override
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    /**
     * 将数据列表存储到引擎内存中。
     * <p>通过反射读取实体 getter 属性，按返回类型构建 Tablesaw 列并填充数据，
     * 写入 {@code tables} 后即可被 {@code query(Class)} 链式查询检索；
     * 整型/长整型/单双精度/布尔按对应数值列存储，{@code LocalDate} 与日期时间按
     * 日期列存储，其余类型（含 {@code BigDecimal}、枚举）以字符串列存储，
     * 读取时统一由 {@link Converter} 还原为目标属性类型。</p>
     * <p>除按入参名称登记外，还会登记“实体类 → 内存表”的直连索引，
     * 保证 {@code store("任意名", users)} 之后 {@code query(User.class)} 能读回同一张表。</p>
     * <p>本方法只维护内存表，不落盘；需要写文件请显式调用 {@link #save(String, String)}。</p>
     *
     * @param name 数据存储名称（表名）
     * @param data 数据列表
     * @param <T>  数据类型
     * @return this
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Engine store(String name, List<T> data) {
        checkOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据存储名称（表名）不能为空");
        }
        if (data == null) {
            throw new IllegalArgumentException("Tablesaw 存储失败：数据列表为 null, name=" + name);
        }
        if (CollectionUtils.isEmpty(data)) {
            // 空列表既无实体类型也无法推导列结构，建一张零列表头反而会掩盖调用方的传参错误，
            // 故保持跳过语义，但升级为告警，便于排查“以为存进去了其实没存”的问题
            log.warn("Tablesaw 存储被跳过：数据列表为空, name={}", name);
            return this;
        }
        T first = data.stream().filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tablesaw 存储失败：数据列表不存在非 null 元素, name=" + name));
        Class<T> entityClass = (Class<T>) first.getClass();
        Map<String, Method> getters = resolveGetterMethods(entityClass);
        if (getters.isEmpty()) {
            throw new IllegalStateException("Tablesaw 存储失败：实体 " + entityClass.getName()
                    + " 未提供任何可读取属性（需要 getXxx()/isXxx() 或记录式访问器）");
        }
        Table table = Table.create(name);
        for (Map.Entry<String, Method> entry : getters.entrySet()) {
            Method getter = entry.getValue();
            Column<?> column = newColumn(entry.getKey(), getter.getReturnType());
            for (T entity : data) {
                if (entity == null) {
                    throw new IllegalArgumentException("Tablesaw 存储失败：数据列表存在 null 元素, name=" + name
                            + ", 属性=" + entry.getKey());
                }
                appendValue(column, readGetter(entity, getter, entityClass));
            }
            table.addColumns(column);
        }
        putTable(name, table);
        // 登记实体类到内存表的直连索引，保证 store("任意名", users) 后 query(User.class) 读回同一张表
        entityTables.put(entityClass, table);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        log.info("Tablesaw 数据存储成功: name={}, rows={}, cols={}", name, table.rowCount(), table.columnCount());
        return this;
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor(String dataSourceName) {
        return null;
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 获取数据源
    */
    public <T> EngineDataSource<T> getDataSource(String name) {
        if (name == null) {
            return null;
        }
        return (EngineDataSource<T>) dataSources.get(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 获取数据源
    */
    public <T> EngineDataSource<T> getDataSource() {
        // 未注册数据源且未设置默认名时返回 null，避免以 null 键访问 ConcurrentHashMap 抛 NPE
        if (defaultDataSourceName == null) {
            return null;
        }
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    @Override
    /**
     * 获取Dialect
    */
    public Dialect getDialect(String dataSourceName) {
        return null;
    }

    @Override
    /**
     * 不支持元数据操作
    */
    public boolean supportsMeta() {
        return false;
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 逐个关闭已注册数据源：单个失败不得中断其余数据源的释放，故仅记录不抛出
        for (Map.Entry<String, EngineDataSource<Object>> entry : dataSources.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.error("Tablesaw 关闭数据源失败: name={}", entry.getKey(), e);
            }
        }
        tables.clear();
        entityTables.clear();
        dataSources.clear();
        defaultDataSourceName = null;
    }

    /**
     * 加载 CSV 文件到指定名称的数据源
     *
     * @param name    数据源名称
     * @param csvPath CSV 文件路径
     * @return 当前引擎实例，支持链式调用
     */
    public TablesawEngine load(String name, String csvPath) {
        return load(name, csvPath, null);
    }

    /**
     * 加载指定编码的 CSV 文件到数据源
     *
     * @param name    数据源名称
     * @param csvPath CSV 文件路径
     * @param charset 文件编码（如 UTF-8、GBK），为 空 时使用系统默认编码
     * @return 当前引擎实例，支持链式调用
     */
    public TablesawEngine load(String name, String csvPath, String charset) {
        checkOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
        if (csvPath == null || csvPath.isBlank()) {
            throw new IllegalArgumentException("CSV 文件路径不能为空: name=" + name);
        }
        File file = new File(csvPath);
        if (!file.isFile()) {
            throw new IllegalStateException("Tablesaw 加载失败：CSV 文件不存在或不是普通文件: " + csvPath);
        }
        try {
            Table table;
            if (charset != null && !charset.isBlank()) {
                log.debug("使用指定编码加载 CSV: charset={}, path={}", charset, csvPath);
                // try-with-resources 保证解码读流关闭，避免文件句柄泄漏；
                // Tablesaw 读取即全量入内存表，读取完成后关闭流是安全的
                try (Reader reader = new InputStreamReader(new FileInputStream(file), Charset.forName(charset))) {
                    table = Table.read().csv(CsvReadOptions.builder(reader).build());
                }
            } else {
                table = Table.read().csv(file);
            }
            return register(name, table);
        } catch (Exception e) {
            // 加载失败必须显式抛出：吞掉异常会让上层以为表已就绪，查询静默返回空集
            throw new IllegalStateException("Tablesaw 加载 CSV 失败: " + csvPath, e);
        }
    }

    /**
     * 从输入流加载 CSV 数据到数据源
     * <p>入参流由本方法负责关闭：Tablesaw 读取即全量入内存表，加载完成后流不再需要。</p>
     *
     * @param name        数据源名称
     * @param inputStream CSV 数据输入流
     * @return 当前引擎实例，支持链式调用
     */
    public TablesawEngine load(String name, InputStream inputStream) {
        checkOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
        if (inputStream == null) {
            throw new IllegalArgumentException("CSV 输入流不能为空: name=" + name);
        }
        try (InputStream in = inputStream) {
            Table table = Table.read().csv(in);
            return register(name, table);
        } catch (Exception e) {
            throw new IllegalStateException("Tablesaw 加载流数据失败: name=" + name, e);
        }
    }

    /**
     * 将内存表以 UTF-8 编码写出为 CSV 文件，实现显式持久化。
     * <p>{@link #store(String, List)} 与 {@code load} 只维护内存表，若业务需要落盘必须
     * 调用本方法；表未注册或写出失败均显式抛错，避免“以为保存了其实没保存”。</p>
     *
     * @param name    已注册的表名或数据源名称
     * @param csvPath 目标 CSV 文件路径
     * @return 当前引擎实例，支持链式调用
     */
    public TablesawEngine save(String name, String csvPath) {
        checkOpen();
        if (csvPath == null || csvPath.isBlank()) {
            throw new IllegalArgumentException("CSV 输出路径不能为空: name=" + name);
        }
        Table table = requireRegistered(name, "保存");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(csvPath), StandardCharsets.UTF_8)) {
            table.write().csv(writer);
        } catch (IOException e) {
            throw new IllegalStateException("Tablesaw 写出 CSV 失败: name=" + name + ", path=" + csvPath, e);
        }
        log.info("Tablesaw 写出 CSV 成功: name={}, rows={}, path={}", name, table.rowCount(), csvPath);
        return this;
    }

    /**
     * 注册加载得到的内存表：对齐表名、写入映射表并补设默认数据源名称。
     *
     * @param name  注册名称
     * @param table Tablesaw 表
     * @return 当前引擎实例
     */
    private TablesawEngine register(String name, Table table) {
        // 表名与注册名对齐，便于排查时 table.name() 与映射键一致
        table.setName(name);
        putTable(name, table);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        log.info("Tablesaw 加载成功: name={}, rows={}, cols={}", name, table.rowCount(), table.columnCount());
        return this;
    }

    /**
     * 注册内存表到映射表。
     * <p>覆盖同名表时同步清除指向旧表的实体类索引，避免旧实体查到已作废的数据。</p>
     *
     * @param name  表名或数据源名称
     * @param table Tablesaw 表
     */
    private void putTable(String name, Table table) {
        Table previous = tables.put(name, table);
        if (previous != null && previous != table) {
            entityTables.entrySet().removeIf(entry -> entry.getValue() == previous);
        }
    }

    /**
     * 获取指定名称的 Tablesaw 表格
     *
     * @param name 表名或数据源名称
     * @return Tablesaw Table 对象，不存在则返回 空
     */
    public Table getTable(String name) {
        if (name == null) {
            return null;
        }
        return tables.get(name);
    }

    /**
     * 获取默认 Tablesaw 表格
     *
     * @return 默认的 Tablesaw Table 对象，未设置默认名称或表不存在返回 空
     */
    public Table getTable() {
        if (defaultDataSourceName == null) {
            return null;
        }
        return tables.get(defaultDataSourceName);
    }

    /**
     * 获取所有已加载的表名
     *
     * @return 表名集合（只读快照，不会随后续注册变化）
     */
    public Set<String> tableNames() {
        return Set.copyOf(tables.keySet());
    }

    @Override
    /**
     * 查询
    */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new LambdaQueryWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
            */
            protected String resolveColumn(SFunction<T, ?> column) {
                return resolveLambdaColumn(column);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaQueryWrapper<T> newInstance() {
                return new LambdaQueryWrapper<T>(entityClass) {

                    @Override
                    /**
                     * 解析Column
                    */
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return resolveLambdaColumn(column);
                    }
                };
            }

            @Override
            /**
             * 列表
            */
            public List<T> list() {
                return executeQuery(this);
            }

            @Override
            /**
             * One
            */
            public T one() {
                HitRows<T> hit = prepare(this);
                List<Integer> rows = hit.rows();
                if (rows.isEmpty()) {
                    return null;
                }
                // 命中多行时取首行，与基类 one() 约定一致
                return materialize(hit.table(), entityClass, rows.subList(0, 1),
                        resolveProjection(hit.table(), hit.selectColumns())).getFirst();
            }

            @Override
            /**
             * Page
            */
            public Page<T> page(int pageNum, int pageSize) {
                return executePage(this, pageNum, pageSize);
            }

            @Override
            /**
             * 统计命中行数（忽略 limit / offset 分页参数）
            */
            public long count() {
                return prepare(this).rows().size();
            }
        };
    }

    @Override
    /**
     * 更新
    */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new LambdaUpdateWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
            */
            protected String resolveColumn(SFunction<T, ?> column) {
                return resolveLambdaColumn(column);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaUpdateWrapper<T> newInstance() {
                return new LambdaUpdateWrapper<T>(entityClass) {

                    @Override
                    /**
                     * 解析Column
                    */
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return resolveLambdaColumn(column);
                    }
                };
            }

            @Override
            /**
             * 更新内存表中命中的行
            */
            public int update() {
                return executeUpdate(this);
            }
        };
    }

    @Override
    /**
     * 删除
    */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new LambdaDeleteWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
            */
            protected String resolveColumn(SFunction<T, ?> column) {
                return resolveLambdaColumn(column);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaDeleteWrapper<T> newInstance() {
                return new LambdaDeleteWrapper<T>(entityClass) {

                    @Override
                    /**
                     * 解析Column
                    */
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return resolveLambdaColumn(column);
                    }
                };
            }

            @Override
            /**
             * 删除内存表中命中的行
            */
            public int remove() {
                return executeDelete(this);
            }
        };
    }

    // ==================== 查询 / 更新 / 删除执行 ====================

    /**
     * 执行查询操作
     * <p>
     * 在表上按条件筛选行号、按 ORDER BY 排序，并使 SELECT 投影与 limit / offset 生效。
     * </p>
     *
     * @param wrapper 查询包装器
     * @param <T>     实体类型
     * @return 查询结果列表
     */
    private <T> List<T> executeQuery(LambdaQueryWrapper<T> wrapper) {
        HitRows<T> hit = prepare(wrapper);
        List<Integer> rows = applyLimitOffset(hit.rows(), wrapper.getLimit(), wrapper.getOffset());
        return materialize(hit.table(), wrapper.getEntityClass(), rows,
                resolveProjection(hit.table(), hit.selectColumns()));
    }

    /**
     * 执行分页查询
     * <p>分页窗口由 {@code pageNum / pageSize} 决定，包装器上的 limit / offset 不参与分页，
     * 避免两套分页参数叠加产生难以解释的行号偏移。</p>
     *
     * @param wrapper  查询包装器
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页大小
     * @param <T>      实体类型
     * @return 分页结果
     */
    private <T> Page<T> executePage(LambdaQueryWrapper<T> wrapper, int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new IllegalArgumentException("页码必须从 1 开始: pageNum=" + pageNum);
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("每页大小必须大于 0: pageSize=" + pageSize);
        }
        HitRows<T> hit = prepare(wrapper);
        List<Integer> rows = hit.rows();
        long total = rows.size();
        long from = (long) (pageNum - 1) * pageSize;
        if (from >= total) {
            return new Page<>(pageNum, pageSize, total, List.of());
        }
        int to = (int) Math.min(from + pageSize, total);
        List<Integer> window = rows.subList((int) from, to);
        List<T> records = materialize(hit.table(), wrapper.getEntityClass(), window,
                resolveProjection(hit.table(), hit.selectColumns()));
        return new Page<>(pageNum, pageSize, total, records);
    }

    /**
     * 预解析查询：定位内存表、校验不支持的 SQL 能力、按条件筛选并按 ORDER BY 排序命中行号。
     *
     * @param wrapper 查询包装器
     * @param <T>     实体类型
     * @return 命中的内存表、行号与 SELECT 投影列
     */
    private <T> HitRows<T> prepare(LambdaQueryWrapper<T> wrapper) {
        Table table = requireTable(wrapper.getEntityClass(), "查询");
        QuerySql<T> sql = wrapper.buildSql();
        if (sql.hasJoins()) {
            throw new UnsupportedOperationException("Tablesaw 内存表不支持 JOIN 关联查询: "
                    + wrapper.getEntityClass().getName());
        }
        if (sql.hasGroupBy() || sql.hasHaving()) {
            throw new UnsupportedOperationException("Tablesaw 内存表不支持 GROUP BY / HAVING 聚合查询: "
                    + wrapper.getEntityClass().getName());
        }
        List<Integer> rows = matchedRowIndexes(table, conditionsToMatcher(table, wrapper.getConditions()));
        if (CollectionUtils.isNotEmpty(wrapper.getOrderBys())) {
            rows.sort((a, b) -> compareRows(table, a, b, wrapper.getOrderBys()));
        }
        return new HitRows<>(table, rows, sql.selectColumns());
    }

    /**
     * 执行内存表更新，将命中行的 SET 列改写为新值。
     * <p>写操作失败必须显式抛出，绝不静默返回 0：本方法先复用
     * {@link LambdaUpdateWrapper#buildSql()} 的空 SET / 全表更新护栏，再逐列改写。</p>
     *
     * @param wrapper 更新包装器
     * @param <T>     实体类型
     * @return 受影响行数
     */
    private <T> int executeUpdate(LambdaUpdateWrapper<T> wrapper) {
        wrapper.buildSql();
        Table table = requireTable(wrapper.getEntityClass(), "更新");
        Map<String, Object> setValues = wrapper.getSetValues();
        if (setValues == null || setValues.isEmpty()) {
            throw new IllegalStateException("Tablesaw 更新失败：SET 列为空: "
                    + wrapper.getEntityClass().getSimpleName());
        }
        // 先整体校验列存在性，避免部分列写入后才失败
        Map<Column<?>, Object> writes = new LinkedHashMap<>(setValues.size());
        for (Map.Entry<String, Object> entry : setValues.entrySet()) {
            writes.put(requireColumn(table, entry.getKey(), "更新列"), entry.getValue());
        }
        List<Integer> rows = matchedRowIndexes(table, conditionsToMatcher(table, wrapper.getConditions()));
        if (rows.isEmpty()) {
            log.info("Tablesaw 更新命中 0 行: table={}", table.name());
            return 0;
        }
        for (Map.Entry<Column<?>, Object> entry : writes.entrySet()) {
            Column<Object> column = asWritableColumn(entry.getKey());
            for (Integer row : rows) {
                writeColumnValue(column, row, entry.getValue());
            }
        }
        log.info("Tablesaw 更新成功: table={}, rows={}, columns={}",
                table.name(), rows.size(), setValues.keySet());
        return rows.size();
    }

    /**
     * 执行内存表删除，移除命中行。
     * <p>同样先套用 {@link LambdaDeleteWrapper#buildSql()} 的全表删除护栏。</p>
     *
     * @param wrapper 删除包装器
     * @param <T>     实体类型
     * @return 删除行数
     */
    private <T> int executeDelete(LambdaDeleteWrapper<T> wrapper) {
        wrapper.buildSql();
        Table table = requireTable(wrapper.getEntityClass(), "删除");
        List<Integer> rows = matchedRowIndexes(table, conditionsToMatcher(table, wrapper.getConditions()));
        if (rows.isEmpty()) {
            log.info("Tablesaw 删除命中 0 行: table={}", table.name());
            return 0;
        }
        int[] indexes = new int[rows.size()];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = rows.get(i);
        }
        // Tablesaw 的 dropRows 返回删除后的副本而非原地修改，必须回写索引，否则删除只是“看起来成功”
        Table remaining = table.dropRows(indexes);
        replaceTable(table, remaining);
        log.info("Tablesaw 删除成功: table={}, rows={}", table.name(), indexes.length);
        return indexes.length;
    }

    /**
     * 用新表实例替换索引中的旧表实例。
     * <p>Tablesaw 的行级裁剪 API（如 {@code dropRows}）返回副本，因此需要同时刷新
     * “表名 → 表”与“实体类 → 表”两处索引，避免任何一侧继续持有被删除前的旧数据。</p>
     *
     * @param previous    被替换的旧表实例
     * @param replacement 替换后的新表实例
     */
    private void replaceTable(Table previous, Table replacement) {
        tables.replaceAll((name, table) -> table == previous ? replacement : table);
        entityTables.replaceAll((entity, table) -> table == previous ? replacement : table);
    }

    /**
     * 校验并解析列投影集合。
     * <p>聚合表达式、不存在的列一律显式抛错；{@code *} 或空投影表示全列，返回空集合。</p>
     *
     * @param table         内存表
     * @param selectColumns 投影列
     * @return 归一化后的投影键集合，空集合表示不裁剪
     */
    private static Set<String> resolveProjection(Table table, List<String> selectColumns) {
        Set<String> projection = new LinkedHashSet<>();
        if (CollectionUtils.isEmpty(selectColumns)) {
            return projection;
        }
        for (String column : selectColumns) {
            if (column == null || column.isBlank()) {
                continue;
            }
            if (column.indexOf('(') >= 0) {
                throw new UnsupportedOperationException("Tablesaw 内存表不支持聚合函数投影: " + column);
            }
            if ("*".equals(column.trim())) {
                // 显式全列投影：清空已收集列即不裁剪
                return Set.of();
            }
            requireColumn(table, column, "投影列");
            projection.add(normalize(column));
        }
        return projection;
    }

    /**
     * 按 limit / offset 截取命中行号。
     *
     * @param rows   命中行号（已排序）
     * @param limit  行数上限，0 表示不限制
     * @param offset 偏移行数，0 表示不偏移
     * @return 截取后的行号列表
     */
    private static List<Integer> applyLimitOffset(List<Integer> rows, int limit, int offset) {
        List<Integer> result = rows;
        if (offset > 0) {
            result = offset >= result.size() ? List.of() : result.subList(offset, result.size());
        }
        if (limit > 0 && limit < result.size()) {
            result = result.subList(0, limit);
        }
        return result;
    }

    /**
     * 将命中行的列值映射为实体对象列表。
     *
     * @param table       内存表
     * @param entityClass 实体类型
     * @param rows        行号列表
     * @param projection  投影键集合，空集合表示全列
     * @param <T>         实体类型
     * @return 实体列表
     */
    private static <T> List<T> materialize(Table table, Class<T> entityClass, List<Integer> rows,
                                           Set<String> projection) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> columnNames = table.columnNames();
        Map<String, Method> setters = resolveSetters(entityClass, columnNames);
        Map<String, Field> fields = resolveFields(entityClass, columnNames, setters.keySet());
        List<T> result = new ArrayList<>(rows.size());
        for (Integer row : rows) {
            result.add(rowToEntity(table, row, entityClass, setters, fields, projection));
        }
        return result;
    }

    /**
     * 将内存表指定行转换为实体对象
     *
     * @param table       Tablesaw 表
     * @param rowIndex    行号
     * @param entityClass 实体类类型
     * @param setters     setter 方法映射表（键为归一化列名）
     * @param fields      字段映射表（无 setter 时的兜底写入通道）
     * @param projection  投影键集合，空集合表示全列
     * @param <T>         实体类型
     * @return 实体对象
     */
    private static <T> T rowToEntity(Table table, int rowIndex, Class<T> entityClass,
                                     Map<String, Method> setters, Map<String, Field> fields,
                                     Set<String> projection) {
        T instance = ReflectUtils.instantiate(entityClass);
        if (instance == null) {
            throw new IllegalStateException("Tablesaw 行转实体失败：实体无法实例化（需要可访问的无参构造）: "
                    + entityClass.getName());
        }
        for (Column<?> column : table.columns()) {
            String key = normalize(column.name());
            if (!projection.isEmpty() && !projection.contains(key)) {
                continue;
            }
            Method setter = setters.get(key);
            Field field = setter == null ? fields.get(key) : null;
            if (setter == null && field == null) {
                // 表中多余列（如 CSV 附加列）在实体无对应属性时跳过，属正常投影而非错误
                continue;
            }
            Class<?> targetType = setter != null ? setter.getParameterTypes()[0] : field.getType();
            Object value = readColumnValue(column, rowIndex, targetType);
            if (value == null) {
                continue;
            }
            writeProperty(instance, entityClass, column.name(), setter, field, value);
        }
        return instance;
    }

    /**
     * 读取列值并转换为目标类型
     *
     * @param column     列
     * @param rowIndex   行号
     * @param targetType 目标类型
     * @return 转换后的值，缺失值返回 空
     */
    private static Object readColumnValue(Column<?> column, int rowIndex, Class<?> targetType) {
        if (column.isMissing(rowIndex)) {
            // Tablesaw 以类型相关哨兵值表达缺失，统一还原为 null，避免把 Integer.MIN_VALUE 之类写入实体
            return null;
        }
        Object raw = column.get(rowIndex);
        if (targetType.isEnum()) {
            Object enumValue = Converter.convertIfNecessary(raw, targetType);
            if (enumValue == null) {
                throw new IllegalStateException("Tablesaw 列值无法映射为枚举: 列=" + column.name()
                        + ", 值=" + raw + ", 枚举类型=" + targetType.getName());
            }
            return enumValue;
        }
        Object converted = Converter.convertIfNecessary(raw, targetType);
        // 转换失败时保留原值，交由属性写入阶段显式报错，不静默丢弃字段
        return converted != null ? converted : raw;
    }

    /**
     * 将值写入实体的 setter 或字段，失败显式抛出。
     *
     * @param instance    实体实例
     * @param entityClass 实体类型
     * @param column      列名（用于错误信息）
     * @param setter      setter 方法，可为空
     * @param field       字段，可为空
     * @param value       待写入值
     * @param <T>         实体类型
     */
    private static <T> void writeProperty(T instance, Class<T> entityClass, String column,
                                          Method setter, Field field, Object value) {
        if (setter != null) {
            try {
                // [P3C 1.10 豁免] 此处已持有精确 Method 句柄，ReflectUtils.invoke 会静默吞掉
                // 查找与调用失败，导致实体字段被无声丢弃，故保留原生 Method.invoke 并显式抛出异常
                setter.invoke(instance, value);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Tablesaw 列值写入实体失败: " + entityClass.getName()
                        + "." + setter.getName() + ", 列=" + column + ", 值类型=" + value.getClass().getName(), e);
            }
            return;
        }
        if (field != null && !ReflectUtils.setField(instance, field.getName(), value)) {
            throw new IllegalStateException("Tablesaw 列值写入实体字段失败（字段不可写）: "
                    + entityClass.getName() + "#" + field.getName() + ", 列=" + column);
        }
    }

    // ==================== 条件与比较 ====================

    /**
     * 将条件列表转换为行匹配器
     * <p>顶层组合语义与 {@code AbstractLambdaWrapper#buildWhere} 保持一致：
     * OR 嵌套分组与前文以 OR 连接，其余条件一律 AND 连接。</p>
     *
     * @param table      内存表（用于列存在性校验与取值）
     * @param conditions 条件列表
     * @return 组合后的行匹配器，条件为空时恒真
     */
    private BiPredicate<Table, Integer> conditionsToMatcher(Table table, List<Condition> conditions) {
        if (CollectionUtils.isEmpty(conditions)) {
            return (t, i) -> true;
        }
        BiPredicate<Table, Integer> accumulator = null;
        for (Condition condition : conditions) {
            BiPredicate<Table, Integer> current = conditionToMatcher(table, condition);
            BiPredicate<Table, Integer> previous = accumulator;
            boolean leadingOr = condition.isNested() && "OR".equalsIgnoreCase(condition.getNestedOperator());
            if (previous == null) {
                accumulator = current;
            } else if (leadingOr) {
                accumulator = (t, i) -> previous.test(t, i) || current.test(t, i);
            } else {
                accumulator = (t, i) -> previous.test(t, i) && current.test(t, i);
            }
        }
        return accumulator;
    }

    /**
     * 将单个条件转换为行匹配器
     * <p>支持嵌套条件（组内以 nestedOperator 统一连接）；条件列在表中不存在时显式抛错。</p>
     *
     * @param table     内存表
     * @param condition 条件对象
     * @return 对应的行匹配器
     */
    private BiPredicate<Table, Integer> conditionToMatcher(Table table, Condition condition) {
        if (condition.isNested()) {
            List<Condition> nested = condition.getNested();
            if (CollectionUtils.isEmpty(nested)) {
                // 空分组与 SQL 渲染的 1 = 1 一致，恒真
                return (t, i) -> true;
            }
            List<BiPredicate<Table, Integer>> matchers = new ArrayList<>(nested.size());
            for (Condition sub : nested) {
                matchers.add(conditionToMatcher(table, sub));
            }
            if ("OR".equalsIgnoreCase(condition.getNestedOperator())) {
                return (t, i) -> matchers.stream().anyMatch(m -> m.test(t, i));
            }
            return (t, i) -> matchers.stream().allMatch(m -> m.test(t, i));
        }
        String column = resolveConditionColumn(condition);
        Column<?> target = requireColumn(table, column, "条件列");
        String operator = condition.getOperator();
        Object value = condition.getValue();
        if (operator == null) {
            throw new IllegalStateException("Tablesaw 条件缺少操作符: 列=" + column);
        }
        return (t, i) -> evaluate(rowValue(target, i), operator, value, column);
    }

    /**
     * 取条件的列名：优先使用已解析列名，兜底解析 Lambda 方法引用。
     *
     * @param condition 条件
     * @return 列名
     */
    private String resolveConditionColumn(Condition condition) {
        String column = condition.getColumnName();
        if (column != null && !column.isBlank()) {
            return column;
        }
        column = resolveLambdaColumn(condition.getColumn());
        if (column == null || column.isBlank()) {
            throw new IllegalStateException("Tablesaw 条件列为空，无法定位列");
        }
        return column;
    }

    /**
     * 计算属性值与操作数的匹配结果
     * <p>支持的操作符：=, !=, >, >=, <, <=, LIKE, NOT LIKE, 入, NOT 入, 是否 空, 是否 NOT 空, BETWEEN。
     * 未知操作符说明条件构造与引擎能力不匹配，直接抛错而非返回 false 静默漏数据。</p>
     *
     * @param prop     行值
     * @param op       操作符
     * @param val      操作数值
     * @param column   列名（用于错误信息）
     * @return 匹配结果
     */
    private boolean evaluate(Object prop, String op, Object val, String column) {
        switch (op) {
            case "=":
                return eq(prop, val);
            case "!=":
                // SQL 语义：任一侧为 null 时结果为未知，按不命中处理
                return prop != null && val != null && !eq(prop, val);
            case ">":
                return prop != null && val != null && compareValues(prop, val) > 0;
            case ">=":
                return prop != null && val != null && compareValues(prop, val) >= 0;
            case "<":
                return prop != null && val != null && compareValues(prop, val) < 0;
            case "<=":
                return prop != null && val != null && compareValues(prop, val) <= 0;
            case "LIKE":
                return prop != null && likeMatch(String.valueOf(prop), String.valueOf(val));
            case "NOT LIKE":
                return prop != null && !likeMatch(String.valueOf(prop), String.valueOf(val));
            case "IN": {
                if (val instanceof Collection<?> collection) {
                    // 空集合的 IN 为恒假，与 SQL 渲染的 1 = 0 一致
                    return prop != null && collection.stream().anyMatch(v -> eq(prop, v));
                }
                throw new IllegalStateException("Tablesaw IN 条件值必须为集合: 列=" + column);
            }
            case "NOT IN": {
                if (val instanceof Collection<?> collection) {
                    if (collection.isEmpty()) {
                        return true;
                    }
                    return prop != null && collection.stream().noneMatch(v -> eq(prop, v));
                }
                throw new IllegalStateException("Tablesaw NOT IN 条件值必须为集合: 列=" + column);
            }
            case "IS NULL":
                return prop == null;
            case "IS NOT NULL":
                return prop != null;
            case "BETWEEN": {
                if (!(val instanceof Object[] range) || range.length < 2) {
                    throw new IllegalStateException("Tablesaw BETWEEN 条件值必须为 [起始值, 结束值]: 列=" + column);
                }
                if (prop == null || range[0] == null || range[1] == null) {
                    return false;
                }
                return compareValues(prop, range[0]) >= 0 && compareValues(prop, range[1]) <= 0;
            }
            default:
                throw new IllegalStateException("Tablesaw 不支持的条件操作符: " + op + " (列=" + column + ")");
        }
    }

    /**
     * 判断两个值是否相等
     * <p>数字按 {@link BigDecimal} 精确保值比较（避免 long 转 double 丢精度），
     * 其他类型先按 equals，再按字符串兜底比较。</p>
     *
     * @param prop 行值
     * @param val  目标值
     * @return 是否相等
     */
    private boolean eq(Object prop, Object val) {
        if (prop == null) {
            return val == null;
        }
        if (val == null) {
            return false;
        }
        if (prop instanceof Number || val instanceof Number) {
            BigDecimal left = toDecimal(prop);
            BigDecimal right = toDecimal(val);
            if (left != null && right != null) {
                return left.compareTo(right) == 0;
            }
        }
        if (prop.equals(val)) {
            return true;
        }
        return prop.toString().equals(val.toString());
    }

    /**
     * 比较两个行值大小
     * <p>数字走 {@link BigDecimal} 精确比较；同类型可比较对象走自然序；
     * 其余类型退化为字符串比较（列存引擎的字符串列常见此情形）。</p>
     *
     * @param left  左值，非空
     * @param right 右值，非空
     * @return 负数、0、正数
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object left, Object right) {
        BigDecimal l = toDecimal(left);
        BigDecimal r = toDecimal(right);
        if (l != null && r != null) {
            return l.compareTo(r);
        }
        if (left instanceof Comparable && right.getClass().isInstance(left)) {
            return ((Comparable) left).compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    /**
     * 尝试把值转为 {@link BigDecimal}，非数值返回 空。
     * <p>字符串形式的数字（{@code BigDecimal} 属性按字符串列存储、CSV 数字列被推断为字符串）
     * 同样按数值解析，避免数值比较退化为字典序。</p>
     *
     * @param value 待转换值
     * @return 数值，非数值类型返回 null
     */
    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof CharSequence text) {
            String trimmed = text.toString().trim();
            char first = trimmed.isEmpty() ? ' ' : trimmed.charAt(0);
            if (Character.isDigit(first) || first == '-' || first == '+') {
                try {
                    return new BigDecimal(trimmed);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * LIKE 模糊匹配
     * <p>按 SQL LIKE 语义逐字符翻译模式：{@code %} 匹配任意长度（含零长度）、{@code \} 或 {@code !} 之后的
     * 字符按字面量处理，其余正则元字符全部转义后整体锚定匹配。</p>
     *
     * @param prop    属性值字符串
     * @param pattern 匹配模式
     * @return 是否匹配
     */
    private boolean likeMatch(String prop, String pattern) {
        if (prop == null || pattern == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if ((c == '\\' || c == '!') && i + 1 < pattern.length()) {
                appendLiteral(regex, pattern.charAt(++i));
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                appendLiteral(regex, c);
            }
        }
        return java.util.regex.Pattern.compile(regex.toString(), java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(prop).matches();
    }

    /**
     * 将单个字符作为字面量追加到正则片段。
     *
     * @param regex 正则片段缓冲区
     * @param c     待追加字符
     */
    private static void appendLiteral(StringBuilder regex, char c) {
        if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
            regex.append('\\');
        }
        regex.append(c);
    }

    /**
     * 比较两行数据在指定排序字段上的大小
     * <p>支持多字段排序，按 orderBys 列表顺序逐字段比较；排序列不存在显式抛错。</p>
     *
     * @param table    内存表
     * @param rowA     行号 A
     * @param rowB     行号 B
     * @param orderBys 排序字段列表，格式为 "字段名称 ASC" 或 "字段名称 DESC"
     * @return 负数表示 A &lt; B，正数表示 A &gt; B，相等返回 0
     */
    private static int compareRows(Table table, int rowA, int rowB, List<String> orderBys) {
        for (String orderBy : orderBys) {
            String[] parts = orderBy.trim().split("\\s+");
            Column<?> column = requireColumn(table, parts[0], "排序列");
            Object valueA = column.isMissing(rowA) ? null : column.get(rowA);
            Object valueB = column.isMissing(rowB) ? null : column.get(rowB);
            int cmp;
            if (valueA == null && valueB == null) {
                cmp = 0;
            } else if (valueA == null) {
                // NULL 排最前，与 MySQL 升序行为一致
                cmp = -1;
            } else if (valueB == null) {
                cmp = 1;
            } else {
                cmp = compareValues(valueA, valueB);
            }
            if (cmp != 0) {
                boolean desc = parts.length > 1 && "DESC".equalsIgnoreCase(parts[1]);
                return desc ? -cmp : cmp;
            }
        }
        return 0;
    }

    /**
     * 按条件筛选命中的行号。
     *
     * @param table   内存表
     * @param matcher 行匹配器
     * @return 命中行号（升序）
     */
    private static List<Integer> matchedRowIndexes(Table table, BiPredicate<Table, Integer> matcher) {
        List<Integer> hits = new ArrayList<>(table.rowCount());
        for (int row = 0; row < table.rowCount(); row++) {
            if (matcher.test(table, row)) {
                hits.add(row);
            }
        }
        return hits;
    }

    // ==================== 表 / 列解析 ====================

    /**
     * 根据实体类型解析对应的 Tablesaw 表格，未注册时显式抛错。
     * <p>解析顺序：{@link #entityTables} 直连索引（store 写入的表）→ 实体类推导表名精确匹配 →
     * 归一化（大小写 / 下划线）匹配 → 引擎仅有一张表时直接使用该表
     * （{@code load("任意名", csv)} 后按实体查询的便捷路径）。
     * 此前“回退默认表”的写法在多表场景会把别的表数据当成本实体结果返回，故改为抛错。</p>
     *
     * @param entityClass 实体类类型
     * @param operation   操作名（用于错误信息）
     * @return Tablesaw Table 对象
     */
    private Table requireTable(Class<?> entityClass, String operation) {
        checkOpen();
        Table table = entityTables.get(entityClass);
        String expected = getTableName(entityClass);
        if (table == null) {
            table = tables.get(expected);
        }
        if (table == null) {
            table = findByLooseName(expected);
        }
        if (table == null) {
            throw new IllegalStateException("Tablesaw " + operation + "失败：未注册实体 "
                    + entityClass.getName() + " 对应的表（期望表名: " + expected
                    + "），当前已注册表: " + tables.keySet());
        }
        return table;
    }

    /**
     * 按名称取已注册的表，不存在显式抛错。
     *
     * @param name      表名或数据源名称
     * @param operation 操作名（用于错误信息）
     * @return Tablesaw Table 对象
     */
    private Table requireRegistered(String name, String operation) {
        Table table = name == null ? null : tables.get(name);
        if (table == null) {
            throw new IllegalStateException("Tablesaw " + operation + "失败：表未注册: " + name
                    + "，当前已注册表: " + tables.keySet());
        }
        return table;
    }

    /**
     * 归一化匹配表名（忽略大小写与下划线）。
     *
     * @param name 表名
     * @return 匹配的表，未找到返回 空
     */
    private Table findByLooseName(String name) {
        String target = normalize(name);
        for (Map.Entry<String, Table> entry : tables.entrySet()) {
            if (normalize(entry.getKey()).equals(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 按列名取列，不存在显式抛错。
     *
     * @param table   内存表
     * @param name    列名（驼峰属性名或下划线列名均可）
     * @param usage   用途（用于错误信息）
     * @return Tablesaw 列
     */
    private static Column<?> requireColumn(Table table, String name, String usage) {
        Column<?> column = findColumn(table, name);
        if (column == null) {
            throw new IllegalStateException("Tablesaw " + usage + "不存在: " + name
                    + " (表 " + table.name() + " 可用列: " + table.columnNames() + ")");
        }
        return column;
    }

    /**
     * 按名称查找列（忽略大小写、下划线与表前缀）。
     *
     * @param table 内存表
     * @param name  列名
     * @return 列，未找到返回 空
     */
    private static Column<?> findColumn(Table table, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = normalize(name);
        for (Column<?> column : table.columns()) {
            if (normalize(column.name()).equals(target)) {
                return column;
            }
        }
        return null;
    }

    /**
     * 名称归一化：去掉表前缀与下划线并转小写，用于驼峰属性名与下划线列名互匹配。
     *
     * @param name 原始名称
     * @return 归一化名称
     */
    private static String normalize(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        String value = dot >= 0 ? name.substring(dot + 1) : name;
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '_') {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * 读取列在当前行的值（缺失值返回 空）。
     *
     * @param column   列
     * @param rowIndex 行号
     * @return 列值，缺失返回 null
     */
    private static Object rowValue(Column<?> column, int rowIndex) {
        return column.isMissing(rowIndex) ? null : column.get(rowIndex);
    }

    // ==================== 列构建与写入 ====================

    /**
     * 按 Java 类型创建 Tablesaw 列。
     * <p>整型族（含 short/byte）用 {@link IntColumn}，日期与日期时间用专用列以保留时间语义，
     * 其余类型（含 {@code BigDecimal}、枚举）以字符串列存储，读取时由 {@link Converter} 还原，
     * 避免浮点列造成精度损失。</p>
     *
     * @param name 列名
     * @param type 属性类型
     * @return Tablesaw 列
     */
    private static Column<?> newColumn(String name, Class<?> type) {
        if (type == int.class || type == Integer.class || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            return IntColumn.create(name);
        }
        if (type == long.class || type == Long.class) {
            return LongColumn.create(name);
        }
        if (type == double.class || type == Double.class) {
            return DoubleColumn.create(name);
        }
        if (type == float.class || type == Float.class) {
            return FloatColumn.create(name);
        }
        if (type == boolean.class || type == Boolean.class) {
            return BooleanColumn.create(name);
        }
        if (type == LocalDate.class || type == java.sql.Date.class) {
            return DateColumn.create(name);
        }
        if (type == LocalDateTime.class || type == Instant.class || type == java.util.Date.class) {
            return DateTimeColumn.create(name);
        }
        return StringColumn.create(name);
    }

    /**
     * 向列追加一个值，按列类型做窄化转换；类型不匹配显式抛错。
     *
     * @param column 目标列
     * @param value  属性值，可为空（写入缺失值）
     */
    private static void appendValue(Column<?> column, Object value) {
        if (value == null) {
            column.appendMissing();
            return;
        }
        if (column instanceof IntColumn intColumn) {
            intColumn.append(toNumber(value, column.name()).intValue());
        } else if (column instanceof LongColumn longColumn) {
            longColumn.append(toNumber(value, column.name()).longValue());
        } else if (column instanceof DoubleColumn doubleColumn) {
            doubleColumn.append(toNumber(value, column.name()).doubleValue());
        } else if (column instanceof FloatColumn floatColumn) {
            floatColumn.append(toNumber(value, column.name()).floatValue());
        } else if (column instanceof BooleanColumn booleanColumn) {
            Boolean bool = value instanceof Boolean b ? b : Converter.convertIfNecessary(value, Boolean.class);
            if (bool == null) {
                throw new IllegalStateException("Tablesaw 列值无法转换为布尔: 列=" + column.name() + ", 值=" + value);
            }
            booleanColumn.append(bool);
        } else if (column instanceof DateColumn dateColumn) {
            LocalDate date = requireConverted(value, LocalDate.class, column.name());
            dateColumn.append(date);
        } else if (column instanceof DateTimeColumn dateTimeColumn) {
            LocalDateTime dateTime = requireConverted(value, LocalDateTime.class, column.name());
            dateTimeColumn.append(dateTime);
        } else if (column instanceof StringColumn stringColumn) {
            // 直接写入原值：appendCell 走 CSV 解析器，会剥离成对引号，导致含引号的属性值被改坏
            stringColumn.append(String.valueOf(value));
        } else {
            column.appendCell(String.valueOf(value));
        }
    }

    /**
     * 改写内存表某列在某行的值（更新操作使用）。
     *
     * @param column   目标列
     * @param rowIndex 行号
     * @param value    新值，为空时写入缺失值
     */
    @SuppressWarnings("unchecked")
    private static void writeColumnValue(Column<Object> column, int rowIndex, Object value) {
        if (value == null) {
            column.setMissing(rowIndex);
            return;
        }
        Class<?> elementType = columnJavaType(column);
        Object converted = requireConverted(value, elementType, column.name());
        column.set(rowIndex, converted);
    }

    /**
     * 将列以可写泛型返回，便于统一调用 {@code set(int, Object)}。
     *
     * @param column 原列
     * @return 同实例的可写列视图
     */
    @SuppressWarnings("unchecked")
    private static Column<Object> asWritableColumn(Column<?> column) {
        return (Column<Object>) column;
    }

    /**
     * 推断列对应的 Java 元素类型。
     *
     * @param column 列
     * @return Java 类型
     */
    private static Class<?> columnJavaType(Column<?> column) {
        if (column instanceof IntColumn) {
            return Integer.class;
        }
        if (column instanceof LongColumn) {
            return Long.class;
        }
        if (column instanceof DoubleColumn) {
            return Double.class;
        }
        if (column instanceof FloatColumn) {
            return Float.class;
        }
        if (column instanceof BooleanColumn) {
            return Boolean.class;
        }
        if (column instanceof DateColumn) {
            return LocalDate.class;
        }
        if (column instanceof DateTimeColumn) {
            return LocalDateTime.class;
        }
        return String.class;
    }

    /**
     * 取值为数值，非数值显式抛错。
     *
     * @param value  原始值
     * @param column 列名（用于错误信息）
     * @return 数值
     */
    private static Number toNumber(Object value, String column) {
        if (value instanceof Number number) {
            return number;
        }
        Number converted = Converter.convertIfNecessary(value, Number.class);
        if (converted == null) {
            throw new IllegalStateException("Tablesaw 列值类型不匹配：列 " + column + " 需要数值，实际值=" + value);
        }
        return converted;
    }

    /**
     * 按目标类型转换值，转换失败显式抛错。
     *
     * @param value  原始值
     * @param type   目标类型
     * @param column 列名（用于错误信息）
     * @param <E>    目标类型参数
     * @return 转换结果
     */
    private static <E> E requireConverted(Object value, Class<E> type, String column) {
        E converted = Converter.convertIfNecessary(value, type);
        if (converted == null) {
            throw new IllegalStateException("Tablesaw 列值类型不匹配：列 " + column + " 需要 "
                    + type.getSimpleName() + "，实际值=" + value);
        }
        return converted;
    }

    // ==================== 反射与命名 ====================

    /**
     * 收集实体类的可读属性（属性名 → getter 方法）。
     * <p>识别 {@code getXxx()}、返回布尔的 {@code isXxx()}，以及与字段同名的记录式访问器
     * （如 {@code name()}）；跳过桥接方法、合成方法与静态方法。</p>
     *
     * @param entityClass 实体类类型
     * @return 有序的属性名到 getter 映射
     */
    private static Map<String, Method> resolveGetterMethods(Class<?> entityClass) {
        Set<String> fieldNames = new HashSet<>();
        for (Class<?> clazz = entityClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fieldNames.add(field.getName());
                }
            }
        }
        Map<String, Method> getters = new LinkedHashMap<>();
        for (Method method : entityClass.getMethods()) {
            String methodName = method.getName();
            if (method.getParameterCount() != 0 || method.isBridge() || method.isSynthetic()
                    || Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class
                    || "getClass".equals(methodName)) {
                continue;
            }
            String property = null;
            if (methodName.startsWith("get") && methodName.length() > 3) {
                property = decapitalize(methodName.substring(3));
            } else if (methodName.startsWith("is") && methodName.length() > 2
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                property = decapitalize(methodName.substring(2));
            } else if (fieldNames.contains(methodName)) {
                property = methodName;
            }
            if (property != null) {
                getters.putIfAbsent(property, method);
            }
        }
        return getters;
    }

    /**
     * 反射调用 getter 方法获取属性值。
     * <p>存储属于写入路径，取值失败必须显式抛出，不能按 null 静默存入造成数据缺失。</p>
     *
     * @param entity      实体对象
     * @param getter      getter 方法
     * @param entityClass 实体类型（用于错误信息）
     * @return 属性值
     */
    private static Object readGetter(Object entity, Method getter, Class<?> entityClass) {
        try {
            // [P3C 1.10 豁免] 此处已持有精确 Method 句柄，ReflectUtils.invoke 查找失败会静默返回 null，
            // 使存储环节丢字段且无从排查，故保留原生 Method.invoke 并显式抛出异常
            return getter.invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Tablesaw 读取实体属性失败: " + entityClass.getName()
                    + "#" + getter.getName(), e);
        }
    }

    /**
     * 解析实体类的 setter 方法映射
     * <p>按归一化列名（忽略大小写与下划线）匹配实体 setter，使 CSV 的 {@code user_name}
     * 列与实体的 {@code userName} 属性能对上。</p>
     *
     * @param entityClass 实体类类型
     * @param columnNames 列名列表
     * @return 归一化列名到 setter 方法的映射表
     */
    private static Map<String, Method> resolveSetters(Class<?> entityClass, Collection<String> columnNames) {
        Set<String> wanted = columnNames.stream().map(TablesawEngine::normalize).collect(Collectors.toSet());
        Map<String, Method> setters = new HashMap<>();
        for (Method method : entityClass.getMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 1 || method.isBridge() || method.isSynthetic()
                    || !name.startsWith("set") || name.length() <= 3) {
                continue;
            }
            String key = normalize(name.substring(3));
            if (wanted.contains(key)) {
                setters.putIfAbsent(key, method);
            }
        }
        return setters;
    }

    /**
     * 解析无 setter 时的字段兜底映射。
     *
     * @param entityClass 实体类类型
     * @param columnNames 列名列表
     * @param setterKeys  已由 setter 覆盖的归一化键
     * @return 归一化列名到字段的映射表
     */
    private static Map<String, Field> resolveFields(Class<?> entityClass, Collection<String> columnNames,
                                                    Set<String> setterKeys) {
        Set<String> wanted = columnNames.stream().map(TablesawEngine::normalize).collect(Collectors.toSet());
        Map<String, Field> fields = new HashMap<>();
        for (Class<?> clazz = entityClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String key = normalize(field.getName());
                if (wanted.contains(key) && !setterKeys.contains(key)) {
                    fields.putIfAbsent(key, field);
                }
            }
        }
        return fields;
    }

    /**
     * 解析 Lambda 表达式中的方法引用为属性名
     * <p>例如 {@code User::getName} 解析为 {@code "name"}。
     * 解析失败说明调用方传入的不是可序列化的属性方法引用，必须显式抛错：
     * 返回空列名会让条件静默失配、查询恒空。</p>
     *
     * @param column Lambda 方法引用
     * @return 属性名字符串
     */
    private String resolveLambdaColumn(SFunction<?, ?> column) {
        if (column == null) {
            throw new IllegalArgumentException("Lambda 列不能为空");
        }
        try {
            java.lang.reflect.Method writeReplace = column.getClass().getDeclaredMethod("writeReplace"); // [P3C 1.10 豁免] writeReplace 为 lambda 隐藏类的私有零参方法，ReflectUtils 的 MethodHandle 路径无法访问，只能精确反射
            // [P3C 1.10 豁免] 同上：必须拿到 SerializedLambda 才能还原属性名，无等价工具方法
            writeReplace.setAccessible(true);
            SerializedLambda lambda = (SerializedLambda) writeReplace.invoke(column); // [P3C 1.10 豁免] 同上：writeReplace 为 lambda 私有方法
            if (lambda == null) {
                throw new IllegalStateException("无法解析 Lambda 列（writeReplace 返回空）: " + column);
            }
            String name = lambda.getImplMethodName();
            if (name.startsWith("is")) {
                name = name.substring(2);
            } else if (name.startsWith("get") || name.startsWith("set")) {
                name = name.substring(3);
            } else {
                throw new IllegalStateException("无法从 Lambda 方法 " + name + " 解析属性名（需要 getXxx/isXxx/setXxx）");
            }
            return decapitalize(name);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Tablesaw Lambda 列解析失败: " + column, e);
        }
    }

    /**
     * 首字母小写（保留 {@code getURL} 这类连续大写的属性名），遵循 JavaBeans 命名约定。
     *
     * @param name 属性名（首字母大写形式）
     * @return 驼峰属性名
     */
    private static String decapitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * 将实体类名转换为表名
     * <p>
     * 驼峰命名转换为下划线命名，例如 {@code UserInfo} 转换为 {@code user_info}。
     * </p>
     *
     * @param entityClass 实体类类型
     * @return 转换后的表名
     */
    private static String getTableName(Class<?> entityClass) {
        String simpleName = entityClass.getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (char c : simpleName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 校验引擎处于可用状态，已关闭时显式抛错。
     */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Tablesaw 引擎已关闭，禁止继续读写");
        }
    }

    /**
     * 查询预解析结果：命中的内存表、已过滤排序的行号与 SELECT 投影列。
     *
     * @param table         命中的内存表
     * @param rows          命中行号列表
     * @param selectColumns SELECT 投影列（空列表表示全列）
     * @param <T>           实体类型
     */
    private record HitRows<T>(Table table, List<Integer> rows, List<String> selectColumns) {
    }
}
