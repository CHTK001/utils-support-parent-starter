package com.chua.datasource.support.flyway;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* H2 迁移执行器，在脚本执行前对 SQL 进行 H2 方言转换。
* <p>
* 处理 MySQL/PostgreSQL 特有语法，确保脚本在 H2 中可正常运行：
* <ul>
*   <li>{@code AUTO_INCREMENT} → {@code IDENTITY AUTOINCREMENT}</li>
* </ul>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class H2Flyway {

    private static final String SQL_EXT = "sql"; // SQL_EXT
    private static final String DEFAULT_SEP = "__"; // 默认sep
    private static final String CP_PREFIX = "classpath:"; // cp前缀
    private static final Pattern SCRIPT_PAT =
            Pattern.compile("^V(\\d+)__([^\\s]+)\\.sql$", Pattern.CASE_INSENSITIVE);
    private static final String HISTORY_TABLE = "flyway_schema_history"; // 历史table
    private static final String CREATE_HISTORY_SQL =
            "CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
                    + "version BIGINT PRIMARY KEY, "
                    + "description VARCHAR(255), "
                    + "script VARCHAR(255), "
                    + "applied_at BIGINT )";
    private static final String INSERT_HISTORY_SQL =
            "INSERT INTO " + HISTORY_TABLE
                    + " (version, description, script, applied_at) VALUES (?, ?, ?, ?)";
    private static final String SELECT_VERSIONS_SQL = "SELECT version FROM " + HISTORY_TABLE; // 选择版本SQL

    private final DataSource dataSource; // 数据源
    private final List<String> locations = new ArrayList<>(); // 位置
    private String separator = DEFAULT_SEP; // separator

    /**
    * H2Flyway。
    * @param dataSource 数据源
     */
    public H2Flyway(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
    * 添加迁移脚本位置，支持 类路径: 前缀与文件系统路径
    *
    * @param location 位置
    * @return 位置的结果
     */
    public H2Flyway location(String location) {
        if (location != null && !location.isBlank()) {
            locations.add(location.trim());
        }
        return this;
    }

    /**
    * 设置版本描述分隔符，默认 __
    *
    * @param sep sep
    * @return separator的结果
     */
    public H2Flyway separator(String sep) {
        if (sep != null && !sep.isEmpty()) {
            this.separator = sep;
        }
        return this;
    }

    /**
    * 列出所有迁移脚本及已应用状态
    *
    * @return 信息的结果
     */
    public List<Map<String, Object>> info() {
        try (var conn = dataSource.getConnection()) {
            Set<Long> applied = loadAppliedVersions(conn);
            List<Map<String, Object>> result = new ArrayList<>();
            for (ScriptFile s : scanScripts()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", s.version);
                m.put("description", s.description);
                m.put("script", s.fileName);
                m.put("applied", applied.contains(s.version));
                result.add(m);
            }
            result.sort(Comparator.comparingLong(a -> 0));
            return result;
        } catch (Exception e) {
            throw new RuntimeException("H2 迁移 info 失败", e);
        }
    }

    /**
    * 执行所有未应用的迁移脚本，返回执行数量
    *
    * @return migrate的结果
     */
    public int migrate() {
        try (var conn = dataSource.getConnection()) {
            execute(conn, CREATE_HISTORY_SQL);
            Set<Long> applied = loadAppliedVersions(conn);
            int count = 0;
            for (ScriptFile s : scanScripts()) {
                if (applied.contains(s.version)) {
                    continue;
                }
                execute(conn, readContent(s.path));
                insertHistory(conn, s.version, s.description, s.fileName);
                count++;
            }
            return count;
        } catch (Exception e) {
            throw new RuntimeException("H2 迁移失败", e);
        }
    }

    /**
    * 执行单个 SQL 脚本文件（不纳入版本记录）
    *
    * @param script script
    * @return 执行的结果
     */
    public int execute(Path script) {
        if (script == null) {
            throw new IllegalArgumentException("脚本路径不能为空");
        }
        try (var conn = dataSource.getConnection()) {
            return execute(conn, readContent(script));
        } catch (Exception e) {
            throw new RuntimeException("执行脚本失败: " + script, e);
        }
    }

    // ==================== 内部实现 ====================

    /**
    * 执行。
    * @param conn conn
    * @param sql SQL
    * @return 执行的结果
     */
    private int execute(java.sql.Connection conn, String sql) throws SQLException {
        String normalized = normalize(sql);
        if (normalized == null || normalized.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String stmt : splitStatements(normalized)) {
            if (!stmt.isBlank()) {
                try (var ps = conn.prepareStatement(stmt)) {
                    ps.execute();
                    count++;
                }
            }
        }
        return count;
    }

    private void insertHistory(java.sql.Connection conn, long version, String desc, String script)
            throws SQLException {
        try (var ps = conn.prepareStatement(normalize(INSERT_HISTORY_SQL))) {
            ps.setLong(1, version);
            ps.setString(2, desc);
            ps.setString(3, script);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
    * 加载applied版本。
    * @param conn conn
    * @return 加载applied版本的结果
     */
    private Set<Long> loadAppliedVersions(java.sql.Connection conn) throws SQLException {
        Set<Long> versions = new HashSet<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(SELECT_VERSIONS_SQL)) {
            while (rs.next()) {
                versions.add(rs.getLong(1));
            }
        } catch (SQLException ignored) {}
        return versions;
    }

    /**
    * 扫描script。
    * @return 扫描script的结果
     */
    private List<ScriptFile> scanScripts() {
        List<ScriptFile> scripts = new ArrayList<>();
        for (String loc : locations) {
            if (loc.startsWith(CP_PREFIX)) {
                scanClasspath(loc.substring(CP_PREFIX.length()), scripts);
            } else {
                scanDir(loc, scripts);
            }
        }
        scripts.sort(Comparator.comparingLong(s -> s.version));
        return scripts;
    }

    /**
    * 扫描dir。
    * @param dirPath dir路径
    * @param target Target
     */
    private void scanDir(String dirPath, List<ScriptFile> target) {
        java.io.File dir = new java.io.File(dirPath);
        if (!dir.isDirectory()) {
            return;
        }
        java.io.File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith("." + SQL_EXT));
        if (files == null) {
            return;
        }
        for (java.io.File f : files) {
            addScript(f.toPath(), f.getName(), target);
        }
    }

    /**
    * 扫描类路径。
    * @param resourcePath resource路径
    * @param target Target
     */
    private void scanClasspath(String resourcePath, List<ScriptFile> target) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = H2Flyway.class.getClassLoader();
            }
            Enumeration<URL> urls = cl.getResources(resourcePath);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDir(new java.io.File(url.toURI()).getPath(), target);
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("扫描 classpath 脚本失败: " + resourcePath, e);
        }
    }

    /**
    * 添加script。
    * @param path 路径
    * @param fileName 文件名称
    * @param target Target
     */
    private void addScript(Path path, String fileName, List<ScriptFile> target) {
        Matcher m = SCRIPT_PAT.matcher(fileName);
        if (!m.matches()) {
            return;
        }
        long version = Long.parseLong(m.group(1));
        String description = m.group(2);
        target.add(new ScriptFile(version, description, fileName, path));
    }

    /**
    * 分割对账单。
    * @param sql SQL
    * @return 分割对账单的结果
     */
    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-' && !inStr) {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\'') { inStr = !inStr; sb.append(c); }
            else if (c == ';' && !inStr) { statements.add(sb.toString()); sb.setLength(0); }
            else { sb.append(c); }
        }
        if (!sb.toString().isBlank()) {
            statements.add(sb.toString());
        }
        return statements;
    }

    /**
    * 读取内容。
    * @param path 路径
    * @return 读取内容的结果
     */
    private static String readContent(Path path) {
        try { return Files.readString(path, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new RuntimeException("读取迁移脚本失败: " + path, e); }
    }

    /**
    * 将 SQL 语句转换为 H2 兼容写法。
    * <ul>
    *   <li>{@code AUTO_INCREMENT} → {@code IDENTITY AUTOINCREMENT}</li>
    * </ul>
    * @param version 版本
     /**
      * normalize。
      * @param sql SQL
      * @return normalize的结果
      */
     * @param description description
     * @param fileName 文件名称
     * @param path 路径
     * @return script文件的结果
      * @param version 版本
     /**
     * normalize。
     * @param sql SQL
     * @return normalize的结果
      */
      * @param version 版本
      /**
      * normalize。
      * @param sql sql
      * @return normalize的结果
       */
      * @param description description
      * @param fileName 文件名称
      * @param path 路径
     */
    public static String normalize(String sql) {
        if (sql == null) {
            return null;
        }
        return sql.replaceAll("(?i)\\bAUTO_INCREMENT\\b", "IDENTITY AUTOINCREMENT");
    }

    private record ScriptFile(long version, String description, String fileName, Path path) {}
}
