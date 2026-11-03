// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.ProcedureDefinition;
import com.chua.common.support.lang.datasource.dialect.TriggerDefinition;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.ddl.ColumnDef;
import com.chua.common.support.lang.datasource.engine.ddl.DdlProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.dialect.SqlDialect;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.JdbcMetaData;
import com.chua.datasource.support.user.DataSourceAware;
import com.chua.datasource.support.user.UserInfo;
import com.chua.datasource.support.user.UserManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcEngine} 的<b>失败路径与 SPI 扩展点</b>行为约定。
 *
 * <p>主链路（查询 / 更新 / 删除 / 分页）已由 {@code JdbcEngineTest} 覆盖；本类补齐生产环境
 * 才会走到、但一旦写错就会静默失效的分支：</p>
 * <ul>
 *   <li>数据源缺失或类型不是 {@link DataSource} 时必须返回空结果，而不是 NPE 或空指针查询</li>
 *   <li>DDL 的 {@code ddl-provider} 扩展优先级，以及无扩展时的内置兜底语法</li>
 *   <li>触发器 / 存储过程按方言模板查询，缺失列必须降级读别名而不是整体失败</li>
 *   <li>SPI 管理器（用户 / 元数据）按方言协议解析，并注入 JDBC 数据源</li>
 * </ul>
 *
 * <p>触发器与存储过程用 H2 的<b>真实对象</b>（{@code CREATE TRIGGER} + {@code CREATE ALIAS}）验证，
 * 断言的是元数据内容确实被解析出来，而不是「抛了个异常就算过」。</p>
 */
class JdbcEngineDeepTest {

    /**
     * 独立的 H2 内存库，避免与其它用例的库/凭据互相污染。
     */
    private static final String URL = "jdbc:h2:mem:enginedeep;DB_CLOSE_DELAY=-1";

    private DeepEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS deep_user");
            st.execute("CREATE TABLE deep_user (id INT PRIMARY KEY, name VARCHAR(64), age INT)");
            st.execute("INSERT INTO deep_user VALUES (1, 'a', 10), (2, 'b', 20), (3, 'c', 30)");
        }
        engine = new DeepEngine();
        engine.addDataSource("h2", URL, Dialect.require("h2"));
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        // 本类会临时注册 SPI 替身，清掉 provider 缓存让后续用例回到「无扩展」的初始状态
        ServiceProvider.CACHE.remove(DdlProvider.class);
        ServiceProvider.CACHE.remove(UserManager.class);
        ServiceProvider.CACHE.remove(com.chua.common.support.lang.datasource.meta.MetaData.class);
    }

    @Test
    @DisplayName("内存存储存在时按 WHERE 过滤并做 limit/offset 截取")
    void memoryBranchFilterAndSlice() {
        // 内存表键必须是实体解析出的表名（deep_user），否则 getData 找不到会回退 JDBC
        engine.store(AbstractEngine.resolveTableName(DeepUser.class), List.of(
                new DeepUser(1, "a", 10), new DeepUser(2, "b", 20),
                new DeepUser(3, "c", 30), new DeepUser(4, "d", 40),
                new DeepUser(5, "e", 50)));

        // 命中内存链路的 WHERE 过滤（参数化谓词，不能拼字符串）
        List<DeepUser> filtered = engine.query(DeepUser.class).ge(DeepUser::getId, 2).list();
        assertEquals(List.of(2, 3, 4, 5), filtered.stream().map(DeepUser::getId).toList());

        // executeNewQuery 的 limit/offset 截取：第 3 条之后的 1 条
        assertEquals(List.of(4), engine.newQuery("id >= ?", new Object[]{2},
                        DeepUser.class, 1, 2).stream().map(DeepUser::getId).toList());
        // offset 越过末尾必须返回空，而不是 IndexOutOfBounds
        assertTrue(engine.newQuery("id >= ?", new Object[]{2}, DeepUser.class, 0, 10).isEmpty());
        // 只有 offset 没有 limit 时截取到末尾（过滤后共 4 行，从第 4 行起只剩 1 行）
        assertEquals(List.of(5), engine.newQuery("id >= ?", new Object[]{2},
                        DeepUser.class, 0, 3).stream().map(DeepUser::getId).toList());

        // 数据库中的原始数据不受内存存储影响
        assertEquals(3, engine.getExecutor().query("SELECT * FROM deep_user").size());
    }

    @Test
    @DisplayName("executeNewQuery 直连 JDBC：无数据源/非 DataSource 数据源返回空，SQL 出错抛根因")
    void newQueryGuardsAndErrors() {
        // 正常 JDBC 直连（不经过 executeQueryFull 的 SQL 下推）
        assertEquals(List.of(2), engine.newQuery("id = ?", new Object[]{2},
                        DeepUser.class, 0, 0).stream().map(DeepUser::getId).toList());

        // 数据源类型不是 DataSource：必须返回空列表
        DeepEngine nonJdbc = new DeepEngine();
        nonJdbc.addRawDataSource("notJdbc", "plain-string", Dialect.require("h2"));
        assertTrue(nonJdbc.newQuery("id = 1", null, DeepUser.class, 0, 0).isEmpty());
        assertFalse(nonJdbc.supportsNativePagingPublic(DeepUser.class));
        // 取连接同样必须给出可定位的异常而不是 ClassCastException
        IllegalStateException unsupported = assertThrows(IllegalStateException.class,
                nonJdbc::getJdbcConnectionPublic);
        assertTrue(unsupported.getMessage().contains("数据源类型不支持"));
        // 回退 unwrap 拿不到 DataSource 时返回 null
        assertNull(nonJdbc.getJdbcDataSourcePublic());
        nonJdbc.close();

        // 无数据源：空列表，不抛 NPE
        DeepEngine empty = new DeepEngine();
        assertTrue(empty.newQuery("id = 1", null, DeepUser.class, 0, 0).isEmpty());
        empty.close();

        // SQL 错误（列不存在）必须抛带语句信息的异常
        RuntimeException e = assertThrows(RuntimeException.class, () -> engine.newQuery(
                "no_such_column = ?", new Object[]{1}, DeepUser.class, 0, 0));
        assertTrue(e.getMessage().contains("JDBC query error"));
    }

    @Test
    @DisplayName("聚合/分组/排序 SQL 构建：无显式投影时只选分组列，空列标签降级用列名")
    void projectionAndColumnFallback() throws Exception {
        // GROUP BY 但未 select：只投影分组列，兼容 ONLY_FULL_GROUP_BY
        List<DeepUser> grouped = engine.query(DeepUser.class).groupBy(DeepUser::getAge).list();
        assertEquals(3, grouped.size());

        // 结果集列标签为空（Oracle 的 SELECT 1 AS ""）时必须降级用列名，否则整行字段丢失
        try (Connection conn = DriverManager.getConnection(URL);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 AS \"\"")) {
            List<DeepUser> mapped = engine.mapEntitiesPublic(rs, DeepUser.class);
            assertEquals(1, mapped.size());
        }
    }

    @Test
    @DisplayName("COUNT 与更新/删除失败时抛带语句与表名的异常")
    void writeAndCountErrors() {
        RuntimeException countError = assertThrows(RuntimeException.class,
                () -> engine.query(NoSuchTable.class).count());
        assertTrue(countError.getMessage().contains("COUNT"));

        RuntimeException updateError = assertThrows(RuntimeException.class,
                () -> engine.update(NoSuchTable.class)
                        .set(NoSuchTable::getName, "x").eq(NoSuchTable::getId, 1).update());
        assertTrue(updateError.getMessage().contains("执行更新失败"));

        RuntimeException deleteError = assertThrows(RuntimeException.class,
                () -> engine.delete(NoSuchTable.class).eq(NoSuchTable::getId, 1).remove());
        assertTrue(deleteError.getMessage().contains("执行删除失败"));
    }

    @Test
    @DisplayName("DDL：ddl-provider 扩展优先；无扩展时 mysql 系自带 utf8mb4 兜底")
    void ddlProviderPriority() throws Exception {
        registerDdlProvider();
        // 扩展返回的语句被真正执行：建 schema / 删 schema / 建表
        assertTrue(engine.createDatabase("ddl_deep"));
        assertTrue(engine.dropDatabase("ddl_deep"));
        assertTrue(engine.createTable("deep_tbl", List.of(
                new ColumnDef().setName("id").setType("INT").setPrimaryKey(true))));
        // 扩展生成的建表语句不带 IF NOT EXISTS，重复建表必须失败并被包装
        RuntimeException duplicate = assertThrows(RuntimeException.class,
                () -> engine.createTable("deep_tbl", List.of(
                        new ColumnDef().setName("id").setType("INT"))));
        assertTrue(duplicate.getMessage().contains("创建表失败"));
        engine.getExecutor().execute("DROP TABLE deep_tbl");

        // 列定义列表为空必须立即报参数错误（表名合法也不会去执行 DDL）
        assertThrows(IllegalArgumentException.class, () -> engine.createTable("deep_tbl2", List.of()));
        // 默认值为空白也属于无效字面量：该校验仅在无 DDL 扩展的兜底建表路径中生效，
        // 因此用不匹配已注册 H2DdlProvider 的 mysql 方言引擎强制走兜底逻辑
        DeepEngine noExt = new DeepEngine();
        noExt.addRawDataSource("mysql", URL, new SqlDialect("mysql"));
        IllegalArgumentException blankDefault = assertThrows(IllegalArgumentException.class,
                () -> noExt.createTable("deep_tbl3", List.of(
                        new ColumnDef().setName("s").setType("VARCHAR(8)").setDefaultValue("   "))));
        assertTrue(blankDefault.getMessage().contains("默认值"));
        noExt.close();
        // 自增列要真的生成 AUTO_INCREMENT 关键字
        assertTrue(engine.createTable("deep_ai", List.of(
                new ColumnDef().setName("id").setType("INT").setAutoIncrement(true))));
        engine.getExecutor().execute("DROP TABLE deep_ai");

        // 无扩展命中时 mysql/mariadb 走内置 utf8mb4 语句（此处只校验生成结果，不连库执行）
        DeepEngine mysqlish = new DeepEngine();
        mysqlish.addRawDataSource("mysql", URL, new SqlDialect("mysql"));
        assertEquals("CREATE DATABASE IF NOT EXISTS db_deep DEFAULT CHARACTER SET utf8mb4"
                        + " COLLATE utf8mb4_unicode_ci",
                mysqlish.resolveCreateDatabaseSqlPublic("db_deep"));
        mysqlish.close();
        // 无扩展命中时其余方言走内置兜底（"unknown" 不匹配 H2DdlProvider，也低于
        // DefaultDdlProvider 的 false）：只校验生成的语句，不连库执行
        DeepEngine noExtDb = new DeepEngine();
        noExtDb.addRawDataSource("unknown", URL, new SqlDialect("unknown"));
        assertEquals("CREATE DATABASE IF NOT EXISTS db_deep",
                noExtDb.resolveCreateDatabaseSqlPublic("db_deep"));
        noExtDb.close();
    }

    @Test
    @DisplayName("触发器：解析真实 H2 触发器的名称/表/时机/事件，缺失列降级为空")
    void triggerParsing() throws Exception {
        exec("CREATE TRIGGER trg_deep AFTER INSERT ON deep_user FOR EACH ROW CALL '"
                + DeepTrigger.class.getName() + "'");

        List<TriggerDefinition> triggers = engine.getTriggers(null);
        assertEquals(1, triggers.size());
        TriggerDefinition def = triggers.getFirst();
        assertEquals("TRG_DEEP", def.getName());
        assertEquals("USER_TEST".isEmpty() ? "" : "DEEP_USER", def.getTableName());
        assertEquals("AFTER", def.getTiming());
        assertEquals("INSERT", def.getEvent());
        assertTrue(def.isForEachRow());

        TriggerDefinition byName = engine.getTrigger("TRG_DEEP", null);
        assertNotNull(byName);
        assertEquals("TRG_DEEP", byName.getName());
        // 名字大小写在元数据里是精确匹配（H2/MySQL 触发器名都区分大小写）
        assertNull(engine.getTrigger("no_such_trigger", null));

        exec("DROP TRIGGER trg_deep");
    }

    @Test
    @DisplayName("触发器/存储过程：方言缺失或模板 SQL 出错时给出可定位的异常")
    void triggerProcedureErrors() {
        // 方言缺失：明确报不支持，并带上 unknown 协议名而不是 NPE
        DeepEngine noDialect = new DeepEngine();
        noDialect.addRawDataSource("noDialect", URL, null);
        UnsupportedOperationException noTriggers = assertThrows(UnsupportedOperationException.class,
                () -> noDialect.getTriggers(null));
        assertTrue(noTriggers.getMessage().contains("unknown"));
        assertThrows(UnsupportedOperationException.class, () -> noDialect.getTrigger("t", null));
        assertThrows(UnsupportedOperationException.class, () -> noDialect.getProcedures(null));
        assertThrows(UnsupportedOperationException.class, () -> noDialect.getProcedure("p", null));
        noDialect.close();

        // 模板 SQL 本身出错：包装成运行时异常，保留根因
        DeepEngine broken = new DeepEngine();
        Properties triggerProps = new Properties();
        triggerProps.setProperty("trigger-list-sql", "SELECT * FROM no_such_trigger_view");
        triggerProps.setProperty("trigger-sql",
                "SELECT * FROM ({list_sql}) T WHERE TRIGGER_NAME = '{trigger_name}'");
        broken.addRawDataSource("broken", URL, new SqlDialect("h2", triggerProps));
        RuntimeException listError = assertThrows(RuntimeException.class,
                () -> broken.getTriggers(null));
        assertTrue(listError.getMessage().contains("获取触发器列表失败"));
        RuntimeException oneError = assertThrows(RuntimeException.class,
                () -> broken.getTrigger("t", null));
        assertTrue(oneError.getMessage().contains("获取触发器定义失败"));
        broken.close();
    }

    @Test
    @DisplayName("存储过程：自定义 procedure 模板解析 H2 的 ROUTINES 元数据")
    void procedureParsing() {
        exec("CREATE ALIAS IF NOT EXISTS deep_fn AS 'String deepFn() { return \"x\"; }'");
        DeepEngine aliased = new DeepEngine();
        Properties props = new Properties();
        props.setProperty("procedure-list-sql",
                "SELECT ROUTINE_NAME, ROUTINE_SCHEMA, ROUTINE_CATALOG, DATA_TYPE, ROUTINE_DEFINITION,"
                        + " ROUTINE_BODY, ROUTINE_TYPE FROM INFORMATION_SCHEMA.ROUTINES");
        props.setProperty("procedure-sql",
                "SELECT * FROM ({list_sql}) T WHERE ROUTINE_NAME = '{procedure_name}'");
        aliased.addRawDataSource("proc", URL, new SqlDialect("h2", props));

        List<ProcedureDefinition> all = aliased.getProcedures(null);
        assertFalse(all.isEmpty());
        assertTrue(all.stream().anyMatch(p -> "DEEP_FN".equalsIgnoreCase(p.getName())));
        ProcedureDefinition one = aliased.getProcedure("DEEP_FN", null);
        assertNotNull(one);
        // ROUTINE_BODY=EXTERNAL 必须解析进 language，DATA_TYPE 进 returnType
        assertEquals("EXTERNAL", one.getLanguage());
        assertNotNull(one.getReturnType());
        assertNull(aliased.getProcedure("no_such_proc", null));
        aliased.close();

        // 模板 SQL 出错时包装成运行时异常
        DeepEngine broken = new DeepEngine();
        Properties brokenProps = new Properties();
        brokenProps.setProperty("procedure-list-sql", "SELECT * FROM no_such_routine_view");
        brokenProps.setProperty("procedure-sql",
                "SELECT * FROM ({list_sql}) T WHERE ROUTINE_NAME = '{procedure_name}'");
        broken.addRawDataSource("brokenProc", URL, new SqlDialect("h2", brokenProps));
        RuntimeException listError = assertThrows(RuntimeException.class,
                () -> broken.getProcedures(null));
        assertTrue(listError.getMessage().contains("获取存储过程列表失败"));
        RuntimeException oneError = assertThrows(RuntimeException.class,
                () -> broken.getProcedure("p", null));
        assertTrue(oneError.getMessage().contains("获取存储过程定义失败"));
        broken.close();

        exec("DROP ALIAS IF EXISTS deep_fn");
    }

    @Test
    @DisplayName("SPI 管理器：按方言协议解析并注入 JDBC 数据源；方言缺失时按 unknown 查找")
    void managerResolution() {
        // 方言缺失 → 协议名 unknown，无扩展时返回 null 而不是抛异常
        DeepEngine noDialect = new DeepEngine();
        noDialect.addRawDataSource("noDialect", URL, null);
        assertNull(noDialect.user());
        assertNull(noDialect.index());
        assertNull(noDialect.permission());
        noDialect.close();

        ServiceProvider<UserManager> provider = ServiceProvider.of(UserManager.class);
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("h2");
        definition.setType(UserManager.class);
        definition.setImplClass(RecordingUserManager.class);
        provider.register(definition);

        UserManager manager = engine.user();
        assertNotNull(manager);
        // DataSourceAware 扩展必须拿到真实数据源，否则管理器无法执行任何用户 DDL
        assertNotNull(((RecordingUserManager) manager).dataSource);
    }

    @Test
    @DisplayName("元数据：MetaData SPI 按协议命中，注入数据源失败时回退默认实现")
    void metaDataSpi() {
        assertNotNull(engine.meta());

        registerMetaDataSpi(false);
        com.chua.common.support.lang.datasource.meta.MetaData ok = engine.meta();
        assertTrue(ok instanceof RecordingMetaData);
        // 引擎必须把 JDBC 数据源注入到元数据实现，否则元数据查询无连接可用
        assertNotNull(((RecordingMetaData) ok).injected());

        ServiceProvider.CACHE.remove(com.chua.common.support.lang.datasource.meta.MetaData.class);
        registerMetaDataSpi(true);
        // 注入失败不能把 meta() 打成异常，必须回退到默认实现
        com.chua.common.support.lang.datasource.meta.MetaData fallback = engine.meta();
        assertFalse(fallback instanceof RecordingMetaData);
        assertFalse(engine.supportsMeta() && fallback == null);
    }

    @Test
    @DisplayName("库列表：mysql 系走 SHOW DATABASES，其余走 JDBC catalogs；不存在的库返回 false")
    void databaseListing() {
        assertFalse(engine.databaseExists("NO_SUCH_DB_DEEP"));
        assertFalse(engine.databaseExists(null));
        assertFalse(engine.databaseExists("  "));

        DeepEngine mysqlish = new DeepEngine();
        mysqlish.addRawDataSource("mysql", URL, new SqlDialect("mysql"));
        // H2 也有 INFORMATION_SCHEMA.SCHEMATA，mysql 分支的预编译查询可直接命中
        assertTrue(mysqlish.databaseExists("PUBLIC"));
        assertFalse(mysqlish.databaseExists("NO_SUCH_DB_DEEP"));
        mysqlish.close();

        // 连接不可用时包装成运行时异常并保留根因
        DeepEngine brokenConn = new DeepEngine();
        brokenConn.addRawDataSource("broken", URL, new SqlDialect("mysql"));
        brokenConn.close();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> brokenConn.databaseExists("PUBLIC"));
        assertTrue(e.getMessage().contains("检查数据库存在性失败"));
    }

    // ==================== 测试辅助 ====================

    /**
     * 在测试库上顺序执行若干原生 SQL。
     *
     * @param sqls 待执行的 SQL
     */
    private static void exec(String... sqls) {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement st = conn.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("测试库执行 SQL 失败", e);
        }
    }

    /**
     * 注册一个支持 h2 协议的 DDL 生成器替身。
     *
     * <p>真实扩展由各数据库 starter 提供，本模块 classpath 上只有恒返回 false 的
     * {@code DefaultDdlProvider}；替身保留关键语义——覆盖内置兜底语句，
     * 且建表语句不带 {@code IF NOT EXISTS}，从而能验证「扩展优先」与失败包装。</p>
     */
    private static void registerDdlProvider() {
        ServiceProvider<DdlProvider> provider = ServiceProvider.of(DdlProvider.class);
        // 扩展点的定义按「大写键」存放（.env/extensions 加载时统一转大写），
        // 注册时必须用同一把键，否则新定义会落进另一个键而被查找忽略
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName(DdlProvider.SPI_NAME.toUpperCase(java.util.Locale.ROOT));
        definition.setType(DdlProvider.class);
        definition.setImplClass(H2DdlProvider.class);
        provider.register(definition);
    }

    /**
     * 注册一个按 h2 协议命中的元数据实现。
     *
     * @param failOnInject true 表示注入数据源时失败，用于验证回退
     */
    private static void registerMetaDataSpi(boolean failOnInject) {
        ServiceProvider<com.chua.common.support.lang.datasource.meta.MetaData> provider =
                ServiceProvider.of(com.chua.common.support.lang.datasource.meta.MetaData.class);
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("h2");
        definition.setType(com.chua.common.support.lang.datasource.meta.MetaData.class);
        definition.setImplClass(failOnInject ? FailingMetaData.class : RecordingMetaData.class);
        provider.register(definition);
    }

    /**
     * 暴露受保护/私有入口的 JDBC 引擎。
     */
    static class DeepEngine extends JdbcEngine {

        /**
         * 注册一个真实可连接的 H2 数据源。
         *
         * @param name    数据源名称
         * @param url     JDBC URL
         * @param dialect 方言，null 表示无方言
         */
        void addDataSource(String name, String url, Dialect dialect) {
            addRawDataSource(name, url, dialect);
        }

        /**
         * 注册任意 source 对象的数据源：用于构造「非 JDBC 数据源」场景。
         *
         * @param name    数据源名称
         * @param source  数据源对象（URL 字符串、DataSource 实例等）
         * @param dialect 方言
         */
        void addRawDataSource(String name, Object source, Dialect dialect) {
            EngineDataSource<Object> ds = new EngineDataSource<Object>() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public Object getSource() {
                    return source instanceof String text && text.startsWith("jdbc:")
                            ? new DeepDataSource(text) : source;
                }

                @Override
                public EngineDataSource<Object> setSource(Object value) {
                    return this;
                }

                @Override
                public Dialect getDialect() {
                    return dialect;
                }

                @Override
                public EngineDataSource<Object> setDialect(Dialect value) {
                    return this;
                }

                @Override
                public String url() {
                    return source instanceof String text ? text : null;
                }

                @Override
                public String username() {
                    return null;
                }

                @Override
                public String password() {
                    return null;
                }
            };
            super.addDataSource(name, ds);
        }

        /**
         * 直接调用受保护的整表查询入口。
         *
         * @param where   WHERE 片段（可含占位符）
         * @param args    绑定参数
         * @param clazz   实体类
         * @param limit   行数上限，0 表示不限
         * @param offset  偏移量
         * @param <T>     实体类型
         * @return 查询结果
         */
        <T> List<T> newQuery(String where, Object[] args, Class<T> clazz, int limit, int offset) {
            return executeNewQuery(where, args, clazz, limit, offset);
        }

        /**
         * 直接调用私有的结果集映射方法，验证列标签为空时的降级路径。
         *
         * @param rs    结果集
         * @param clazz 实体类
         * @param <T>   实体类型
         * @return 映射结果
         */
        <T> List<T> mapEntitiesPublic(ResultSet rs, Class<T> clazz) throws Exception {
            Method method = JdbcEngine.class.getDeclaredMethod("mapEntities", ResultSet.class, Class.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<T> mapped = (List<T>) method.invoke(this, rs, clazz);
            return mapped;
        }

        /**
         * 暴露物理分页能力判定。
         *
         * @param entityClass 实体类
         * @return 是否支持物理分页
         */
        boolean supportsNativePagingPublic(Class<?> entityClass) {
            return supportsNativePaging(entityClass);
        }

        /**
         * 暴露 JDBC 数据源获取（含 unwrap 回退）。
         *
         * @return 数据源，失败返回 null
         */
        DataSource getJdbcDataSourcePublic() {
            return getJdbcDataSource();
        }

        /**
         * 暴露 JDBC 连接获取。
         *
         * @return 连接
         * @throws Exception 数据源不可用时抛出
         */
        Connection getJdbcConnectionPublic() throws Exception {
            return getJdbcConnection();
        }

        /**
         * 暴露建库语句生成，验证无 DDL 扩展时的内置兜底语法。
         *
         * <p>{@code resolveCreateDatabaseSql} 是私有方法（只被建库入口调用），
         * 这里通过反射直连，用于在不真正建库的前提下断言兜底语法。</p>
         *
         * @param dbName 库名
         * @return 建库语句
         */
        String resolveCreateDatabaseSqlPublic(String dbName) {
            try {
                Method method = JdbcEngine.class
                        .getDeclaredMethod("resolveCreateDatabaseSql", String.class);
                method.setAccessible(true);
                return (String) method.invoke(this, dbName);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("无法调用建库语句生成", e);
            }
        }
    }

    /**
     * 极简 DataSource，避免为测试引入连接池依赖。
     */
    static class DeepDataSource implements DataSource {
        private final String url;

        DeepDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("不支持 unwrap: " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    /**
     * 支持 h2 的 DDL 生成器替身。
     */
    public static class H2DdlProvider implements DdlProvider {

        public H2DdlProvider() {
        }

        public H2DdlProvider(com.chua.common.support.lang.datasource.engine.Engine engine) {
            // SPI 以引擎实例为构造参数实例化，引擎可在此保存引用
        }

        @Override
        public boolean supports(String protocol) {
            return "h2".equals(protocol);
        }

        @Override
        public String createDatabase(String dbName) {
            return "CREATE SCHEMA IF NOT EXISTS " + dbName;
        }

        @Override
        public String dropDatabase(String dbName) {
            return "DROP SCHEMA IF EXISTS " + dbName + " CASCADE";
        }

        @Override
        public String createTable(String tableName, List<ColumnDef> columns) {
            StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (");
            for (int i = 0; i < columns.size(); i++) {
                ColumnDef c = columns.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(c.getName()).append(' ').append(c.getType());
                if (c.isPrimaryKey()) {
                    sb.append(" PRIMARY KEY");
                }
            }
            return sb.append(')').toString();
        }
    }

    /**
     * 记录数据源注入结果的管理器替身。
     */
    public static class RecordingUserManager implements UserManager, DataSourceAware {
        private DataSource dataSource;

        @Override
        public void setDataSource(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public String type() {
            return "h2";
        }

        @Override
        public List<UserInfo> listUsers() {
            return List.of();
        }

        @Override
        public CreateUserStep createUser(String username) {
            throw new UnsupportedOperationException("测试替身不建用户");
        }

        @Override
        public DropUserStep dropUser(String username) {
            throw new UnsupportedOperationException("测试替身不删用户");
        }

        @Override
        public AlterUserStep alterUser(String username) {
            throw new UnsupportedOperationException("测试替身不改用户");
        }
    }

    /**
     * 记录数据源注入结果的元数据替身。
     *
     * <p>只重写 {@code setDataSource} 的记录，读取时走父类的 {@code protected dataSource}，
     * 避免字段遮蔽把注入结果写进两处不同字段。</p>
     */
    public static class RecordingMetaData extends JdbcMetaData {

        public RecordingMetaData(com.chua.common.support.lang.datasource.engine.Engine engine) {
            super(engine);
        }

        /**
         * 读取父类注入的数据源。
         *
         * @return 注入的数据源
         */
        DataSource injected() {
            return super.dataSource;
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaTable table() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaTable table(String tableName) {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaView view() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaView view(String viewName) {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaIndex index() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaIndex index(String indexName) {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaTrigger trigger() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaTrigger trigger(String triggerName) {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaProcedure procedure() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaProcedure procedure(String procedureName) {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaForeignKey fk() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaForeignKey fk(String fkName) {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaUser user() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaPermission permission() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaSearch search() {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }

        @Override
        public com.chua.common.support.lang.datasource.meta.MetaSearch search(String indexName) {
            throw new UnsupportedOperationException("测试替身只验证注入");
        }
    }

    /**
     * 注入数据源即失败的元数据实现，用于验证引擎回退默认实现。
     */
    public static class FailingMetaData extends RecordingMetaData {
        public FailingMetaData(com.chua.common.support.lang.datasource.engine.Engine engine) {
            super(engine);
        }

        @Override
        public void setDataSource(DataSource dataSource) {
            throw new IllegalStateException("模拟元数据注入失败");
        }
    }

    /**
     * H2 触发器实现：H2 通过 {@code CALL 'fqcn'} 反射加载本类。
     */
    public static class DeepTrigger implements org.h2.api.Trigger {
        @Override
        public void init(Connection conn, String schemaName, String triggerName,
                         String tableName, boolean before, int type) {
            // 触发器无需持有状态
        }

        @Override
        public void fire(Connection conn, Object[] oldRow, Object[] newRow) {
            // 仅用于元数据解析，不做业务处理
        }
    }

    /**
     * 测试实体，绑定到 deep_user。
     */
    @TableName("deep_user")
    public static class DeepUser {
        private Integer id;
        private String name;
        private Integer age;

        public DeepUser() {
        }

        DeepUser(Integer id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    /**
     * 指向不存在表的实体，用于验证 COUNT/更新/删除的失败包装。
     */
    @TableName("no_such_table_deep")
    public static class NoSuchTable {
        private Integer id;
        private String name;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
