package com.chua.common.support.lang.datasource.flyway;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* 默认数据库迁移实现，基于 {@link Engine#execute(String, Object...)} 执行 SQL。
*
* <p>迁移版本持久化到 {@code flyway_schema_history} 表，跨实例幂等：
* 已执行的迁移记录在表中，重复 {@link #migrate()} 不会重复执行。</p>
*
* <p>特性：</p>
* <ul>
*   <li>扫描 {@code V{N}__{描述}.sql} 命名脚本，按版本升序执行</li>
*   <li>版本记录表 {@code flyway_schema_history}，保证幂等</li>
*   <li>支持文件系统目录与 classpath 前缀（{@code classpath:}）</li>
*   <li>SQL 按分号分割，忽略 {@code --} 行注释与单引号字符串内的分号</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
@SpiDefault
@Spi(value = Flyway.SPI_NAME, order = 0)
public class DefaultFlyway implements Flyway {

    /**
    * 迁移脚本文件扩展名
     */
    private static final String SQL_EXTENSION = "sql";

    /**
    * 默认版本与描述分隔符
     */
    private static final String DEFAULT_SEPARATOR = "__";

    /**
    * classpath 前缀
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
    * 匹配 {@code V{N}__{描述}.sql} 的脚本名
     */
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("^V(\\d+)__([^\\s]+)\\.sql$", Pattern.CASE_INSENSITIVE);

    /**
    * 版本记录表名
     */
    private static final String HISTORY_TABLE = "flyway_schema_history";

    /**
    * 创建版本记录表 SQL
     */
    private static final String CREATE_HISTORY_SQL =
            "CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
                    + "version BIGINT PRIMARY KEY, "
                    + "description VARCHAR(255), "
                    + "script VARCHAR(255), "
                    + "applied_at BIGINT )";

    /**
    * 插入版本记录 SQL
     */
    private static final String INSERT_HISTORY_SQL =
            "INSERT INTO " + HISTORY_TABLE + " (version, description, script, applied_at) VALUES (?, ?, ?, ?)";

    /**
    * 查询已应用版本 SQL
     */
    private static final String SELECT_VERSIONS_SQL =
            "SELECT version FROM " + HISTORY_TABLE;

    /**
    * 所属引擎，用于执行迁移 SQL
     */
    private final Engine engine;

    /**
    * 脚本位置列表
     */
    private final List<String> locations = new ArrayList<>();

    /**
    * 版本与描述分隔符
     */
    private String separator = DEFAULT_SEPARATOR;

    /**
    * 构造迁移执行器。
    *
    * @param engine 引擎实例
     */
    public DefaultFlyway(Engine engine) {
        this.engine = engine;
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
    public List<MigrationInfo> info() {
        Set<Long> appliedVersions = loadAppliedVersions();
        List<MigrationInfo> result = new ArrayList<>();
        for (ScriptFile script : scanScripts()) {
            result.add(new MigrationInfo(
                    script.version,
                    script.description,
                    script.fileName,
                    appliedVersions.contains(script.version)));
        }
        result.sort(Comparator.comparingLong(MigrationInfo::version));
        return result;
    }

    @Override
    public int migrate() {
        ensureHistoryTable();
        Set<Long> appliedVersions = loadAppliedVersions();
        int executed = 0;
        for (ScriptFile script : scanScripts()) {
            if (appliedVersions.contains(script.version)) {
                continue;
            }
            executeScriptContent(readContent(script.path));
            recordApplied(script.version, script.description, script.fileName);
            executed++;
        }
        return executed;
    }

    @Override
    public int execute(Path script) {
        if (script == null) {
            throw new IllegalArgumentException("脚本路径不能为空");
        }
        try {
            return executeScriptContent(Files.readString(script, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("读取脚本失败: " + script, e);
        }
    }

    // ==================== 版本记录 ====================

    /**
    * 确保版本记录表存在。
     */
    private void ensureHistoryTable() {
        engine.execute(CREATE_HISTORY_SQL);
    }

    /**
    * 加载已应用版本集合。
    *
    * @return 已应用版本集合
     */
    @SuppressWarnings("deprecation")
    private Set<Long> loadAppliedVersions() {
        Set<Long> versions = new HashSet<>();
        if (engine.getExecutor() == null) {
            return versions;
        }
        List<Map<String, Object>> rows = engine.getExecutor().query(SELECT_VERSIONS_SQL);
        for (Map<String, Object> row : rows) {
            Object value = row.values().iterator().next();
            if (value instanceof Number number) {
                versions.add(number.longValue());
            }
        }
        return versions;
    }

    /**
    * 记录已应用版本。
    *
    * @param version     版本号
    * @param description 描述
    * @param script      脚本文件名
     */
    private void recordApplied(long version, String description, String script) {
        engine.execute(INSERT_HISTORY_SQL, version, description, script, System.currentTimeMillis());
    }

    // ==================== 脚本扫描 ====================

    /**
    * 扫描所有位置的迁移脚本。
    *
    * @return 脚本列表（按版本升序）
     */
    private List<ScriptFile> scanScripts() {
        List<ScriptFile> scripts = new ArrayList<>();
        for (String location : locations) {
            if (location.startsWith(CLASSPATH_PREFIX)) {
                scanClasspath(location.substring(CLASSPATH_PREFIX.length()), scripts);
            } else {
                scanDirectory(location, scripts);
            }
        }
        scripts.sort(Comparator.comparingLong(ScriptFile::version));
        return scripts;
    }

    /**
    * 扫描文件系统目录。
    *
    * @param dirPath 目录路径
    * @param target  结果集合
     */
    private void scanDirectory(String dirPath, List<ScriptFile> target) {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith("." + SQL_EXTENSION));
        if (files == null) {
            return;
        }
        for (File file : files) {
            addScript(file.toPath(), file.getName(), target);
        }
    }

    /**
    * 扫描 classpath 资源目录。
    *
    * @param resourcePath classpath 路径
    * @param target       结果集合
     */
    private void scanClasspath(String resourcePath, List<ScriptFile> target) {
        try {
            ClassLoader classLoader = defaultClassLoader();
            Enumeration<URL> resources = classLoader.getResources(resourcePath);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDirectory(new File(url.toURI()).getPath(), target);
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("扫描 classpath 脚本失败: " + resourcePath, e);
        }
    }

    /**
    * 解析单个脚本并加入集合。
    *
    * @param path     脚本路径
    * @param fileName 脚本文件名
    * @param target   结果集合
     */
    private void addScript(Path path, String fileName, List<ScriptFile> target) {
        Matcher matcher = SCRIPT_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return;
        }
        long version = Long.parseLong(matcher.group(1));
        String description = matcher.group(2);
        target.add(new ScriptFile(version, description, fileName, path));
    }

    // ==================== SQL 执行 ====================

    /**
    * 读取脚本内容，执行并返回语句数量。
    *
    * @param sql 脚本 SQL 内容
    * @return 语句数量
     */
    private int executeScriptContent(String sql) {
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) {
            return 0;
        }
        int executed = 0;
        for (String statement : statements) {
            if (statement.isBlank()) {
                continue;
            }
            engine.execute(statement);
            executed++;
        }
        return executed;
    }

    /**
    * 按分号分割 SQL 语句，忽略 {@code --} 行注释与单引号字符串内的分号。
    *
    * @param sql 原始 SQL
    * @return 语句列表
     */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            // 行注释：-- 到行尾
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-' && !inString) {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                inString = !inString;
                current.append(c);
            } else if (c == ';' && !inString) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }

    /**
    * 获取默认类加载器。
    *
    * @return 类加载器
     */
    private static ClassLoader defaultClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            return context;
        }
        ClassLoader own = DefaultFlyway.class.getClassLoader();
        return own != null ? own : ClassLoader.getSystemClassLoader();
    }

    /**
    * 读取脚本文件内容。
    *
    * @param path 脚本路径
    * @return 文件内容
     */
    private static String readContent(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取迁移脚本失败: " + path, e);
        }
    }

    /**
    * 扫描到的脚本文件信息。
    *
    * @param version     版本号
    * @param description 描述
    * @param fileName    文件名
    * @param path        文件路径
    * @author CH
    * @since 4.0.0.42
     */
    private record ScriptFile(
            long version,
            String description,
            String fileName,
            Path path
    ) {
    }
}