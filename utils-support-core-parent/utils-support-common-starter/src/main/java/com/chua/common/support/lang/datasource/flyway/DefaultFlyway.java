package com.chua.common.support.lang.datasource.flyway;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认数据库迁移实现，基于 {@link Engine#execute(String, Object...)} 执行 SQL。
 *
 * <p>脚本扫描、命名解析、语句拆分与版本记录全部复用
 * {@link FlywayScripts} / {@link FlywayHistory}，与增强实现
 * （{@code utils-support-flyway-starter}）共享同一套语义与同一张记录表。</p>
 *
 * <p>特性：</p>
 * <ul>
 *   <li>脚本命名 {@code V{版本}__{描述}.sql}，版本支持点分多段并按段数值升序</li>
 *   <li>位置支持 {@code classpath:}（枚举全部根，含 jar 内资源）与文件系统目录（递归）</li>
 *   <li>记录表 {@code sys_database_version}，复合主键 {@code (version, script_name)}，跨实例幂等</li>
 *   <li>{@link #protocol(String)} 指定目标库后，语句经 {@link ScriptConverter} SPI 做方言转换</li>
 * </ul>
 *
 * <p>执行严格：任一句失败即抛出并中断本次迁移，不写成功记录，下次启动重跑该脚本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiDefault
@Spi(value = Flyway.SPI_NAME, order = 0)
public class DefaultFlyway implements Flyway {

    /**
     * 所属引擎，用于执行迁移 SQL
     */
    private final Engine engine;

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

    /**
     * 目标数据库协议名，为空表示脚本按目标库原生方言编写、不做转换
     */
    private String protocol;

    /**
     * 构造迁移执行器。
     *
     * @param engine 引擎实例
     */
    public DefaultFlyway(Engine engine) {
        this.engine = engine;
        this.runner = new EngineRunner(engine);
    }

    @Override
    public Flyway location(String location) {
        if (location != null && !location.isBlank()) {
            locations.add(location.trim());
        }
        return this;
    }

    @Override
    public Flyway separator(String separator) {
        if (separator != null && !separator.isEmpty()) {
            this.separator = separator;
        }
        return this;
    }

    @Override
    public Flyway protocol(String protocol) {
        this.protocol = protocol;
        return this;
    }

    @Override
    public List<MigrationInfo> info() {
        Map<String, String> applied = FlywayHistory.loadApplied(runner);
        List<MigrationInfo> result = new ArrayList<>();
        for (FlywayScripts.Script script : scan()) {
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
        int executed = 0;
        for (FlywayScripts.Script script : scan()) {
            if (FlywayHistory.SUCCESS_TRUE.equals(applied.get(script.fileName()))) {
                continue;
            }
            String content = FlywayScripts.readContent(script);
            executeScript(content, script.fileName());
            FlywayHistory.record(runner, script.version(), script.description(), script.fileName(),
                    FlywayScripts.checksum(content), FlywayHistory.SUCCESS_TRUE);
            executed++;
        }
        return executed;
    }

    @Override
    public int execute(Path script) {
        if (script == null) {
            throw new IllegalArgumentException("脚本路径不能为空");
        }
        return executeScript(FlywayScripts.readContent(
                com.chua.common.support.file.resource.Resource.create(script.toFile()), script.toString()), script.getFileName().toString());
    }

    /**
     * 扫描已配置位置下的迁移脚本。
     *
     * @return 按版本升序的脚本列表
     */
    private List<FlywayScripts.Script> scan() {
        return FlywayScripts.scan(locations, separator, Thread.currentThread().getContextClassLoader());
    }

    /**
     * 拆分、按协议转换并逐条执行脚本内容。
     *
     * @param sql        脚本全文
     * @param scriptName 脚本文件名（失败信息中使用）
     * @return 实际执行语句数
     */
    private int executeScript(String sql, String scriptName) {
        List<String> statements = convert(FlywayScripts.splitStatements(sql));
        int executed = 0;
        for (String statement : statements) {
            if (statement == null || statement.isBlank()) {
                continue;
            }
            try {
                engine.execute(statement);
                executed++;
            } catch (RuntimeException e) {
                throw new IllegalStateException("执行迁移脚本失败: " + scriptName
                        + " (语句: " + FlywayScripts.truncate(statement, 200) + "): " + e.getMessage(), e);
            }
        }
        return executed;
    }

    /**
     * 按目标协议做方言转换；未指定协议或 SPI 无可用实现时原样返回。
     *
     * @param statements 拆分后的语句列表
     * @return 可执行语句列表
     */
    private List<String> convert(List<String> statements) {
        if (protocol == null || protocol.isBlank()) {
            return statements;
        }
        ScriptConverter converter = ScriptConverter.getExtension(protocol);
        return converter == null ? statements : converter.convertAll(statements, protocol);
    }

    /**
     * 以 {@link Engine} 为底座的历史表读写执行器。
     *
     * <p>依赖 {@link Engine} 的默认 SQL 代理：不支持原生语句的引擎会抛出
     * {@link UnsupportedOperationException}，迁移在首个建表语句即失败，而不是静默无记录。</p>
     */
    private record EngineRunner(Engine engine) implements FlywayHistory.Runner {

        @Override
        public int update(String sql, Object... params) {
            return engine.execute(sql, params);
        }

        @Override
        public List<Map<String, Object>> select(String sql, Object... params) {
            return engine.query(sql, params);
        }
    }
}
