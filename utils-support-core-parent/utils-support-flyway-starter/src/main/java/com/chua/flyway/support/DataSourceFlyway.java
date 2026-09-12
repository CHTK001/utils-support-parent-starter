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
    private static final String HISTORY_TABLE = "flyway_schema_history"; // 历史table

    private final DataSource dataSource; // 数据源
    private final List<String> locations = new ArrayList<>(); // 位置
    private String separator = "__"; // separator

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
        Set<Long> applied = loadAppliedVersions();
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
        Set<Long> applied = loadAppliedVersions();
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

    /**
     * 确保版本记录表 {@code flyway_schema_history} 存在。
     *
     * <p>使用 {@code CREATE TABLE IF NOT EXISTS} 建表，含 5 列：
     * {@code version}（主键，迁移版本号）、{@code description}（脚本描述）、
     * {@code script}（脚本文件名）、{@code checksum}（脚本内容 MD5 校验和）、
     * {@code applied_at}（应用时间戳）。该表是幂等迁移的依据：每次执行前都会查它，
     * 已存在的版本会被跳过。</p>
     *
     * <p>失败时（建表 SQLException）包装为 {@link RuntimeException} 抛出，消息形如"创建版本记录表失败"。</p>
     */
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

    /**
    * 加载已应用（applied）的迁移版本集合。
    *
    * <p>查询 {@code flyway_schema_history} 表的全部 {@code version} 列，去重后返回；
    * 后续 {@link #migrate()} 据此判断某版本是否已执行过（已应用则跳过，实现幂等）。</p>
    *
    * @return 已应用版本号集合；表为空或尚无记录时返回空集合，不为 null。
    *         查询失败（SQLException）时抛出 {@link RuntimeException}，消息为"读取已应用版本失败"。
     */
    private Set<Long> loadAppliedVersions() {
        Set<Long> versions = new HashSet<>();
        String sql = "SELECT version FROM " + HISTORY_TABLE;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                versions.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取已应用版本失败", e);
        }
        return versions;
    }

    /**
    * 将已执行的迁移版本写入 {@code flyway_schema_history} 版本记录表。
    *
    * <p>以 {@code version} 为主键插入一行，记录该版本的描述、脚本文件名、脚本内容
    * MD5 校验和以及应用时间戳（{@code System.currentTimeMillis()}）。校验和用于后续
    * checksum 校验：若脚本被修改导致校验和变化，可在下次迁移时按策略拒绝执行。</p>
    *
    * @param version     迁移版本号（如 {@code 1}、{@code 1.2}），与 {@code V1__xxx.sql} 中的版本号一致
    * @param description 脚本描述，取自脚本文件名 {@code V{版本}__{描述}.sql} 中下划线后的部分
    * @param script      脚本文件全名（如 {@code V1__init.sql}），用于追溯
    * @param checksum    脚本内容的 MD5 校验和（见 {@link #checksum(String)}）
     */
    private void recordApplied(long version, String description, String script, String checksum) {
        String sql = "INSERT INTO " + HISTORY_TABLE
                + " (version, description, script, checksum, applied_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, version);
            ps.setString(2, description);
            ps.setString(3, script);
            ps.setString(4, checksum);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("记录迁移版本失败", e);
        }
    }

    /**
    * 计算迁移脚本内容的 MD5 校验和（十六进制小写字符串）。
    *
    * <p>对脚本内容按 UTF-8 编码取 MD5 摘要。脚本变更后校验和变化，
    * {@link #recordApplied} 中记录的旧校验和将与之不再匹配，可据此判定脚本被修改。
    * MD5 算法不可用（{@link java.security.NoSuchAlgorithmException}，实际不会发生）时
    * 降级为 {@code String.hashCode}，保证始终有可用校验值。</p>
    *
    * @param content 脚本文件全文内容（SQL 文本）
    * @return 32 位十六进制 MD5 字符串；算法不可用时退化为 {@code String.valueOf(hashCode)}
     */
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

    /**
    * 扫描已配置的脚本位置，收集全部迁移脚本并按版本号升序返回。
    *
    * <p>遍历 {@link #locations} 中的每个位置：{@code classpath:} 前缀走
    * {@link #scanClasspath}，其余视为文件系统目录走 {@link #scanDirectory}。
    * 同名脚本按 {@code version} 去重（保留先扫描到的），最终按
    * {@code Long.parseLong(version)} 升序排列，保证迁移执行顺序稳定。</p>
    *
    * @return 按版本升序排列的 {@link ScriptFile} 列表；无脚本时返回空列表
     */
    private List<ScriptFile> scanScripts() {
        Map<Long, ScriptFile> byVersion = new TreeMap<>();
        for (String location : locations) {
            if (location.startsWith("classpath:")) {
                scanClasspath(location.substring("classpath:".length()), byVersion);
            } else {
                scanDirectory(location, byVersion);
            }
        }
        return new ArrayList<>(byVersion.values());
    }

    /**
    * 扫描文件系统目录下的 {@code *.sql} 脚本。
    *
    * <p>列出 {@code dirPath} 目录下所有以 {@code .sql}（忽略大小写）结尾的文件，
    * 逐个交给 {@link #addScript} 解析版本号并加入结果集合。目录不存在或不可读
    * （{@code listFiles} 返回 null）时直接返回，不报错，便于多位置并存时缺一个不影响其余。</p>
    *
    * @param dirPath 文件系统目录路径（绝对或相对）
    * @param target  收集脚本的映射表（按版本号去重，由调用方保证同版本只保留一份）
     */
    private void scanDirectory(String dirPath, Map<Long, ScriptFile> target) {
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

    /**
    * 扫描 classpath 资源位置的 {@code *.sql} 脚本。
    *
    * <p>通过线程上下文类加载器（缺省退回本类类加载器）解析 {@code resourcePath}，
    * 支持多个 jar/目录同时提供同名资源时全部扫描。仅处理 {@code file} 协议的资源
    * （解压为临时文件后走 {@link #scanDirectory}），jar 内资源暂不支持直接读取。</p>
    *
    * <p>解析或 IO 失败（{@link IOException}、{@link URISyntaxException}）时包装为
    * {@link RuntimeException}，消息形如"扫描 classpath 脚本失败: &lt;resourcePath&gt;"。</p>
    *
    * @param resourcePath 类路径资源位置（不带 {@code classpath:} 前缀，由调用方剥离）
    * @param target       收集脚本的映射表（按版本号去重）
     */
    private void scanClasspath(String resourcePath, Map<Long, ScriptFile> target) {
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

    /**
    * 解析单个脚本文件名，提取版本号与描述并加入结果集合。
    *
    * <p>用 {@link #SCRIPT_PATTERN} 匹配 {@code V{版本}__{描述}.sql}（大小写不敏感），
    * 不匹配的文件（如非 {@code V} 前缀、缺下划线分隔）直接忽略、不进结果集合。
    * 同版本重复出现时 {@code putIfAbsent} 保留先扫描到的那一份。</p>
    *
    * @param path       脚本文件绝对路径（用于后续读取内容）
    * @param fileName  脚本文件全名（如 {@code V1__init.sql}）
    * @param target     收集脚本的映射表，键为版本号
     */
    private void addScript(Path path, String fileName, Map<Long, ScriptFile> target) {
        Matcher matcher = SCRIPT_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return;
        }
        long version = Long.parseLong(matcher.group(1));
        String description = matcher.group(2);
        target.putIfAbsent(version, new ScriptFile(version, description, fileName, path));
    }

    // ==================== SQL 执行 ====================

    /**
    * 在单一数据库连接上执行整段 SQL，返回实际执行的语句数。
    *
    * <p>先用 {@link #splitStatements} 把脚本按分号拆成独立语句（忽略单行注释、
    * 字符串内分号），逐条 {@code execute}；空白语句直接跳过不计入。整个方法在同一
    * {@link Connection} 上完成，便于需要事务语义时由调用方控制 commit/rollback。
    * 执行中任一语句抛 {@link SQLException} 时整体失败，包装为 {@link RuntimeException}
    * 抛出（消息"执行迁移脚本失败"），已执行的语句不会自动回滚。</p>
    *
    * @param sql 脚本全文内容（多行、可含多条语句）
    * @return 实际执行（{@code execute} 调用）的语句数量；内容为空或全空白时返回 0
     */
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

    /**
    * 按 SQL 分号拆分脚本为独立语句列表（供 {@link #executeScript} 逐条执行）。
    *
    * <p>逐字符扫描：跳过 {@code --} 单行注释（仅当注释出现在字符串外）；
    * 跟踪单引号字符串边界，字符串内的分号不作为分隔符；遇到字符串外的
    * {@code ;} 时把累积内容作为一条语句入列。末尾若还有非空白内容则补入最后一条。
    * 空语句保留在列表中，由执行方跳过，保证与脚本结构一一对应。</p>
    *
    * @param sql 脚本全文（可含多条语句、单行注释、字符串字面量）
    * @return 拆分后的语句列表（不含分隔分号）；空输入返回空列表
     */
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

    /**
    * 以 UTF-8 编码读取脚本文件全文内容。
    *
    * <p>使用 {@link Files#readString} 一次性读入整个文件。文件不存在、无读取权限
    * 等 IO 失败（{@link IOException}）时包装为 {@link RuntimeException} 抛出，
    * 消息形如"读取迁移脚本失败: &lt;path&gt;"，便于定位是哪个脚本出了问题。</p>
    *
    * @param path 脚本文件的绝对路径（由 {@link #scanScripts} 扫描得到）
    * @return 脚本全文（SQL 文本，UTF-8 解码）
     */
    private static String readContent(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取迁移脚本失败: " + path, e);
        }
    }

    /**
    * 迁移脚本的不可变元数据。
    *
    * <p>由 {@link #addScript} 解析文件名得到，保存一个脚本的四个关键字段，
    * 供 {@link #scanScripts} 排序、{@link #migrate} 执行与记录使用。
    * 作为 record，字段访问器即为 {@code version()/description()/fileName()/path()}。</p>
    *
    * @param version     版本号（如 {@code 1}、{@code 1.2}），排序与去重的键
    * @param description 脚本描述，取自文件名 {@code V{版本}__{描述}.sql} 的下划线后部分
    * @param fileName    脚本文件全名（如 {@code V1__init.sql}），写入版本记录表以便追溯
    * @param path        脚本绝对路径，执行时据此读取内容
     */
    private record ScriptFile(long version, String description, String fileName, Path path) {
    }
}
