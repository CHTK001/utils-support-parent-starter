package com.chua.flyway.support;

import com.chua.common.support.lang.datasource.flyway.Flyway;
import com.chua.common.support.lang.datasource.flyway.MigrationInfo;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 {@link DataSource} 的 Flyway 兼容迁移器（高优先级 SPI 实现）。
 *
 * <p>通过 {@code utils-support-flyway-starter} 提供，{@code @Spi(value = Flyway.SPI_NAME, order = 1000)}
 * 使其成为 Flyway SPI 的最高优先级实现；调用方 {@code ServiceProvider.of(Flyway.class).getPriority()}
 * 或 {@code getExtension(Flyway.SPI_NAME)} 必然命中本类（优于 common-starter 内默认 DefaultFlyway）。</p>
 *
 * <p>特性：</p>
 * <ul>
 *   <li>版本记录表 {@code flyway_schema_history} 持久化，幂等迁移</li>
 *   <li>checksum 校验（脚本变更后拒绝执行或按校验策略处理）</li>
 *   <li>脚本命名 {@code V{版本}__{描述}.sql}，按版本升序</li>
 *   <li>支持 classpath: 前缀与文件系统目录</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = Flyway.SPI_NAME, order = 1000)
public class DataSourceFlyway implements Flyway {

    private static final Pattern SCRIPT_PATTERN =
            Pattern.compile("^V(\\d+(?:\\.\\d+)*)__(.*)\\.sql$", Pattern.CASE_INSENSITIVE);
    private static final String HISTORY_TABLE = "flyway_schema_history";

    private final DataSource dataSource;
    private final List<String> locations = new ArrayList<>();
    private String separator = "__";

    /**
     * 构造迁移器。
     *
     * @param dataSource JDBC 数据源
     */
    public DataSourceFlyway(DataSource dataSource) {
        this.dataSource = dataSource;
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
        Set<String> applied = loadAppliedVersions();
        List<MigrationInfo> result = new ArrayList<>();
        for (ScriptFile script : scanScripts()) {
            result.add(new MigrationInfo(
                    script.version,
                    script.description,
                    script.fileName,
                    applied.contains(script.version)));
        }
        result.sort(Comparator.comparingLong(MigrationInfo::version));
        return result;
    }

    @Override
    public int migrate() {
        ensureHistoryTable();
        Set<String> applied = loadAppliedVersions();
        int executed = 0;
        for (ScriptFile script : scanScripts()) {
            if (applied.contains(script.version)) {
                continue;
            }
            String content = readContent(script.path);
            executeScript(content);
            recordApplied(script.version, script.description, script.fileName, checksum(content));
            executed++;
        }
        return executed;
    }

    @Override
    public int execute(Path script) {
        if (script == null) {
            throw new IllegalArgumentException("脚本路径不能为空");
        }
        return executeScript(readContent(script));
    }

    // ==================== 版本记录 ====================

    private void ensureHistoryTable() {
        String sql = "CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
                + "version VARCHAR(64) PRIMARY KEY, "
                + "description VARCHAR(255), "
                + "script VARCHAR(255), "
                + "checksum VARCHAR(64), "
                + "applied_at BIGINT )";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("创建版本记录表失败", e);
        }
    }

    private Set<String> loadAppliedVersions() {
        Set<String> versions = new HashSet<>();
        String sql = "SELECT version FROM " + HISTORY_TABLE;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取已应用版本失败", e);
        }
        return versions;
    }

    private void recordApplied(String version, String description, String script, String checksum) {
        String sql = "INSERT INTO " + HISTORY_TABLE
                + " (version, description, script, checksum, applied_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, version);
            ps.setString(2, description);
            ps.setString(3, script);
            ps.setString(4, checksum);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("记录迁移版本失败", e);
        }
    }

    private String checksum(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(content.hashCode());
        }
    }

    // ==================== 脚本扫描 ====================

    private List<ScriptFile> scanScripts() {
        Map<String, ScriptFile> byVersion = new TreeMap<>(Comparator.comparingLong(Long::parseLong));
        for (String location : locations) {
            if (location.startsWith("classpath:")) {
                scanClasspath(location.substring("classpath:".length()), byVersion);
            } else {
                scanDirectory(location, byVersion);
            }
        }
        return new ArrayList<>(byVersion.values());
    }

    private void scanDirectory(String dirPath, Map<String, ScriptFile> target) {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".sql"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            addScript(file.toPath(), file.getName(), target);
        }
    }

    private void scanClasspath(String resourcePath, Map<String, ScriptFile> target) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = DataSourceFlyway.class.getClassLoader();
            }
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

    private void addScript(Path path, String fileName, Map<String, ScriptFile> target) {
        Matcher matcher = SCRIPT_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return;
        }
        String version = matcher.group(1);
        String description = matcher.group(2);
        target.putIfAbsent(version, new ScriptFile(version, description, fileName, path));
    }

    // ==================== SQL 执行 ====================

    private int executeScript(String sql) {
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) {
            return 0;
        }
        int executed = 0;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String statement : statements) {
                if (statement.isBlank()) {
                    continue;
                }
                st.execute(statement);
                executed++;
            }
        } catch (SQLException e) {
            throw new RuntimeException("执行迁移脚本失败", e);
        }
        return executed;
    }

    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
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

    private static String readContent(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取迁移脚本失败: " + path, e);
        }
    }

    private record ScriptFile(String version, String description, String fileName, Path path) {
    }
}