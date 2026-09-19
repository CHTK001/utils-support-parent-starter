package com.chua.flyway.support;

import com.chua.common.support.file.resource.Resource;
import com.chua.common.support.lang.datasource.flyway.Flyway;
import com.chua.common.support.lang.datasource.flyway.FlywayHistory;
import com.chua.common.support.lang.datasource.flyway.FlywayScripts;
import com.chua.common.support.lang.datasource.flyway.MigrationInfo;
import com.chua.common.support.lang.datasource.flyway.ScriptConverter;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 {@link DataSource} 的 Flyway 兼容迁移器（高优先级 SPI 实现）。
 *
 * <p>通过 {@code utils-support-flyway-starter} 提供，{@code @Spi(value = Flyway.SPI_NAME, order = 1000)}
 * 使其在 {@code flyway} 扩展键下排序最前。本类以 {@link DataSource} 为构造上下文，命中方式：
 * {@code ServiceProvider.of(Flyway.class).getNewExtensions(Flyway.SPI_NAME, dataSource)} 返回列表首个即本类。
 * 以 {@code Engine} 为构造上下文的 {@code Engine#flyway()} 无法供给 {@link DataSource} 参数，
 * 实测命中 common-starter 的 {@code DefaultFlyway}（语句走引擎执行链、可被拦截器增强）；
 * 两个实现共用同一套扫描与记录语义，任选其一都不会重复迁移。</p>
 *
 * <p>脚本扫描、命名解析、语句拆分与版本记录全部复用
 * {@link FlywayScripts} / {@link FlywayHistory}，与默认实现共享同一套语义与同一张记录表，
 * 因此两个实现交替使用不会出现"同一脚本在各自记录表里被判定为未执行"的重复迁移。</p>
 *
 * <p>特性：</p>
 * <ul>
 *   <li>脚本命名 {@code V{版本}__{描述}.sql}，版本支持点分多段并按段数值升序</li>
 *   <li>位置支持 {@code classpath:}（枚举全部类路径根，含 jar 内资源）与文件系统目录（递归）</li>
 *   <li>版本记录表 {@code sys_database_version}，复合主键 {@code (version, script_name)}，幂等迁移</li>
 *   <li>{@link #protocol(String)} 指定目标库后，语句经 {@link ScriptConverter} SPI 做方言转换</li>
 *   <li>语句级方言差异容错（continueOnError，对齐内置 FlywayLikePopulator 语义）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = Flyway.SPI_NAME, order = 1000)
public class DataSourceFlyway implements Flyway {

    /**
     * 失败语句日志展示长度
     */
    private static final int LOG_STATEMENT_LENGTH = 120;

    /**
     * 异常消息中的语句长度
     */
    private static final int ERROR_STATEMENT_LENGTH = 200;

    private final DataSource dataSource;

    /**
     * 历史表读写执行器
     */
    private final FlywayHistory.Runner runner;

    /**
     * 脚本位置列表
     */
    private final List<String> locations = new ArrayList<>();

    /**
     * 版本与描述分隔符
     */
    private String separator = FlywayScripts.DEFAULT_SEPARATOR;

    /** 语句级容错：方言差异语句（如 MySQL PREPARE 在 H2 下报错）跳过并记 FAILED，不中断整体迁移。
     * 对齐 DataSourceScriptProperties.continueOnError 默认 true 语义 */
    private boolean continueOnError = true;

    /** 目标数据库协议名（如 h2/postgresql/oracle/sqlite），用于 {@link ScriptConverter} SPI 方言转换；
     * null 表示不做转换（脚本按目标库原生方言编写） */
    private String protocol;

    /**
     * 最近一次 migrate 中失败（continueOnError 跳过）的语句，供日志/排查
     */
    private final List<String> lastFailedStatements = new ArrayList<>();

    /**
     * 构造迁移器。
     *
     * @param dataSource JDBC 数据源
     */
    public DataSourceFlyway(DataSource dataSource) {
        this.dataSource = dataSource;
        this.runner = new JdbcRunner(dataSource);
    }

    /**
     * 设置目标数据库协议名，启用 {@link ScriptConverter} SPI 方言转换。
     * <p>执行链：原始脚本 --拆分--> 语句列表 --{@code ScriptConverter.convert(protocol)}-->
     * 转换后语句列表 --JDBC--> 目标库。SPI 未注册实现时退化为原样执行（仅 continueOnError 容错）。</p>
     *
     * @param protocol 数据库协议名（如 {@code h2}、{@code postgresql}、{@code oracle}、{@code sqlserver}）
     * @return this
     */
    public DataSourceFlyway protocol(String protocol) {
        this.protocol = protocol;
        return this;
    }

    @Override
    public Flyway location(String location) {
        if (StringUtils.hasText(location)) {
            locations.add(location.trim());
        }
        return this;
    }

    @Override
    public Flyway separator(String separator) {
        if (StringUtils.hasText(separator)) {
            this.separator = separator;
        }
        return this;
    }

    @Override
    public List<MigrationInfo> info() {
        Map<String, String> applied = FlywayHistory.loadApplied(runner);
        List<MigrationInfo> result = new ArrayList<>();
        for (FlywayScripts.Script script : scanScripts()) {
            result.add(new MigrationInfo(
                    FlywayScripts.majorVersion(script.version()),
                    script.description(),
                    script.fileName(),
                    FlywayHistory.SUCCESS_TRUE.equals(applied.get(script.fileName()))));
        }
        return result;
    }

    @Override
    public int migrate() {
        FlywayHistory.ensure(runner);
        Map<String, String> applied = FlywayHistory.loadApplied(runner);
        List<FlywayScripts.Script> scripts = scanScripts();
        warnIfEmpty(scripts);
        int executed = 0;
        lastFailedStatements.clear();
        for (FlywayScripts.Script script : scripts) {
            if (FlywayHistory.SUCCESS_TRUE.equals(applied.get(script.fileName()))) {
                continue; // 该脚本已成功执行过，跳过
            }
            String content = FlywayScripts.readContent(script);
            ScriptResult result = executeScript(content, script.fileName());
            if (result.allSucceeded()) {
                FlywayHistory.record(runner, script.version(), script.description(), script.fileName(),
                        FlywayScripts.checksum(content), FlywayHistory.SUCCESS_TRUE);
                executed++;
            } else if (continueOnError) {
                FlywayHistory.record(runner, script.version(), script.description(), script.fileName(),
                        FlywayHistory.CHECKSUM_FAILED, FlywayHistory.SUCCESS_FALSE);
            } else {
                throw new IllegalStateException("执行迁移脚本失败: " + script.fileName()
                        + " (失败语句: " + lastFailedStatements + ")");
            }
        }
        return executed;
    }

    @Override
    public int execute(Path script) {
        if (script == null) {
            throw new IllegalArgumentException("脚本路径不能为空");
        }
        String content = FlywayScripts.readContent(Resource.create(script.toFile()), script.toString());
        ScriptResult result = executeScript(content, script.getFileName().toString());
        if (!result.allSucceeded() && !continueOnError) {
            throw new IllegalStateException("执行迁移脚本失败: " + script
                    + " (失败语句: " + lastFailedStatements + ")");
        }
        return result.executed();
    }

    /**
     * 设置语句级容错开关（对齐 DataSourceScriptProperties.continueOnError 默认 true 语义）。
     *
     * @param value true=单条方言差异语句失败时跳过并记录，不中断整体迁移
     * @return this
     */
    public DataSourceFlyway continueOnError(boolean value) {
        this.continueOnError = value;
        return this;
    }

    /**
     * 获取最近一次 migrate 中被容错跳过的失败语句（用于日志/排查）。
     *
     * @return 失败语句列表（截断展示），无失败时为空列表
     */
    public List<String> getFailedStatements() {
        return List.copyOf(lastFailedStatements);
    }

    /**
     * 扫描已配置位置下的迁移脚本。
     *
     * @return 按版本升序排列的脚本列表；无脚本时为空列表
     */
    private List<FlywayScripts.Script> scanScripts() {
        return FlywayScripts.scan(locations, separator, Thread.currentThread().getContextClassLoader());
    }

    /**
     * 配置了位置却扫不到脚本时告警，避免"迁移静默无操作"被误判为成功。
     *
     * @param scripts 扫描结果
     */
    private void warnIfEmpty(List<FlywayScripts.Script> scripts) {
        if (!scripts.isEmpty() || locations.isEmpty()) {
            return;
        }
        log.warn("[flyway] 位置 {} 下未扫描到任何 {} 命名的迁移脚本", locations, "V{版本}__{描述}.sql");
    }

    /**
     * 执行单个迁移脚本。
     *
     * <p>先按分号拆分（忽略行注释与字符串内分号），再按 {@link #protocol} 经
     * {@link ScriptConverter} SPI 转换方言，最后在同一 {@link Connection} 上逐条执行。</p>
     *
     * <p>容错：单条语句失败时，{@code continueOnError} 开启则记录失败语句并继续执行后续语句
     * （典型场景：MySQL 专属 PREPARE/EXECUTE 段在 H2 下报语法错误）；关闭则任一句失败即抛出
     * {@link IllegalStateException} 中断本次迁移。</p>
     *
     * @param sql        脚本全文内容（多行、可含多条语句）
     * @param scriptName 脚本文件名（用于失败日志）
     * @return 执行结果（成功语句数与是否全部成功）
     */
    private ScriptResult executeScript(String sql, String scriptName) {
        List<String> statements = applyConversion(FlywayScripts.splitStatements(sql));
        int expected = 0;
        for (String statement : statements) {
            if (!statement.isBlank()) {
                expected++;
            }
        }
        if (expected == 0) {
            return new ScriptResult(0, true);
        }
        int executed = 0;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String statement : statements) {
                if (statement.isBlank()) {
                    continue;
                }
                try {
                    st.execute(statement);
                    executed++;
                } catch (SQLException e) {
                    lastFailedStatements.add(FlywayScripts.truncate(statement, LOG_STATEMENT_LENGTH));
                    if (!continueOnError) {
                        throw new IllegalStateException("执行迁移脚本失败: " + scriptName
                                + " (语句: " + FlywayScripts.truncate(statement, ERROR_STATEMENT_LENGTH) + ")", e);
                    }
                    // continueOnError：跳过方言差异语句，继续执行后续语句
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行迁移脚本失败: " + scriptName, e);
        }
        return new ScriptResult(executed, executed == expected);
    }

    /**
     * 通过 {@link ScriptConverter} SPI 对语句列表做目标库方言转换。
     * <p>协议未设置或 SPI 无可用实现时原样返回（保持向后兼容）。</p>
     *
     * @param statements 拆分后的语句列表
     * @return 可执行语句列表
     */
    private List<String> applyConversion(List<String> statements) {
        if (protocol == null || protocol.isBlank()) {
            return statements;
        }
        ScriptConverter converter = ScriptConverter.getExtension(protocol);
        if (converter == null) {
            return statements;
        }
        return converter.convertAll(statements, protocol);
    }

    /**
     * 单个脚本的执行结果。
     *
     * @param executed      成功执行的语句数
     * @param allSucceeded  是否全部非空白语句都执行成功
     */
    private record ScriptResult(int executed, boolean allSucceeded) {
    }

    /**
     * 以 JDBC {@link DataSource} 为底座的历史表读写执行器。
     */
    private record JdbcRunner(DataSource dataSource) implements FlywayHistory.Runner {

        @Override
        public int update(String sql, Object... params) {
            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                ps.execute();
                return Math.max(ps.getUpdateCount(), 0);
            } catch (SQLException e) {
                throw new IllegalStateException("执行迁移记录语句失败: "
                        + FlywayScripts.truncate(sql, ERROR_STATEMENT_LENGTH), e);
            }
        }

        @Override
        public List<Map<String, Object>> select(String sql, Object... params) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(meta.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取迁移记录失败: "
                        + FlywayScripts.truncate(sql, ERROR_STATEMENT_LENGTH), e);
            }
            return rows;
        }

        /**
         * 绑定占位参数。
         *
         * @param ps     预编译语句
         * @param params 参数数组
         * @throws SQLException 绑定失败
         */
        private static void bind(PreparedStatement ps, Object... params) throws SQLException {
            if (params == null) {
                return;
            }
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
        }
    }
}
