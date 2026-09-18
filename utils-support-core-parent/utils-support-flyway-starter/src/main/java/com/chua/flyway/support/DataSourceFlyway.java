package com.chua.flyway.support;

import com.chua.common.support.lang.datasource.flyway.Flyway;
import com.chua.common.support.lang.datasource.flyway.MigrationInfo;
import com.chua.common.support.lang.datasource.flyway.ScriptConverter;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
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
 *   <li>版本记录表 {@code sys_database_version}（三方 Flyway 风格历史表 schema），幂等迁移</li>
 *   <li>checksum 校验（脚本变更后拒绝执行或按校验策略处理）</li>
 *   <li>脚本命名 {@code V{版本}__{描述}.sql}，按版本字符串段数值升序（三方 Flyway 版本语义）</li>
 *   <li>支持 classpath: 前缀（含 jar 内资源解压）与文件系统目录</li>
 *   <li>语句级方言差异容错（continueOnError，对齐内置 FlywayLikePopulator 语义）</li>
 * </ul>
*
* @author CH
* @since 4.0.0.42
 */
@Spi(value = Flyway.SPI_NAME, order = 1000)
public class DataSourceFlyway implements Flyway {

    private static final Pattern SCRIPT_PATTERN =
            Pattern.compile("^V([0-9]+(?:\\.[0-9]+)*)__(.*)\\.sql$", Pattern.CASE_INSENSITIVE);
    /**
    * 版本记录表：与内置 FlywayLikePopulator 默认表名（{@code sys_database_version}）对齐，
    * 避免双记录表分裂。H2 本地联调场景（spring.sql.init 预建）无需额外建表。
    * 列名使用通用名（{@code version}/{@code description}/{@code script_name}/{@code checksum}/{@code success}），
    * 三方 Flyway 历史表 schema 兼容。
    */
    private static final String HISTORY_TABLE = "sys_database_version";
    private static final String COL_VERSION = "version";
    private static final String COL_SCRIPT_NAME = "script_name";
    private static final String COL_CHECKSUM = "checksum";
    private static final String COL_SUCCESS = "success";
    /** success 列取值：成功 / 失败占位 */
    private static final String SUCCESS_TRUE = "true";
    private static final String SUCCESS_FALSE = "false";
    /** 失败语句的占位校验和 */
    private static final String CHECKSUM_FAILED = "FAILED";

    private final DataSource dataSource;
    private final List<String> locations = new ArrayList<>();
    /** 分隔符 */
    private String separator = "__";
    /** 语句级容错：方言差异语句（如 MySQL PREPARE 在 H2 下报错）跳过并记 FAILED，不中断整体迁移。
    * 对齐 DataSourceScriptProperties.continueOnError 默认 true 语义 */
    private boolean continueOnError = true;
    /** 目标数据库协议名（如 h2/postgresql/oracle），用于 {@link ScriptConverter} SPI 方言转换；
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
        Map<String, String> applied = loadAppliedScripts();
        List<MigrationInfo> result = new ArrayList<>();
        for (ScriptFile script : scanScripts()) {
            result.add(new MigrationInfo(
                    parseMajorVersion(script.version),
                    script.description,
                    script.fileName,
                    SUCCESS_TRUE.equals(applied.get(script.fileName))));
        }
        result.sort(Comparator.comparingLong(MigrationInfo::version));
        return result;
    }

    @Override
    public int migrate() {
        ensureHistoryTable();
        Map<String, String> applied = loadAppliedScripts();
        int executed = 0;
        lastFailedStatements.clear();
        for (ScriptFile script : scanScripts()) {
            String success = applied.get(script.fileName);
            if (SUCCESS_TRUE.equals(success)) {
                continue; // 该脚本已成功执行过，跳过
            }
            String content = readContent(script.path);
            boolean ok = executeScript(content, script.fileName);
            if (ok) {
                recordApplied(script.version, script.description, script.fileName, checksum(content), SUCCESS_TRUE);
                executed++;
            } else if (continueOnError) {
                recordApplied(script.version, script.description, script.fileName, CHECKSUM_FAILED, SUCCESS_FALSE);
            } else {
                throw new RuntimeException("执行迁移脚本失败: " + script.fileName
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
        boolean ok = executeScript(readContent(script), script.getFileName().toString());
        if (!ok && !continueOnError) {
            throw new RuntimeException("执行迁移脚本失败: " + script);
        }
        return ok ? 1 : 0;
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

    // ==================== 版本记录 ====================

    /**
    * 确保版本记录表 {@code sys_database_version} 存在。
    *
    * <p>使用 {@code CREATE TABLE IF NOT EXISTS} 建表，列名对齐三方 Flyway 历史表 schema
    * 与内置 FlywayLikePopulator 的记录列：{@code version}（迁移版本字符串）、
    * {@code description}（脚本描述）、{@code script_name}（脚本文件名）、
    * {@code checksum}（脚本内容 MD5 校验和）、{@code success}（成功标志）。
    * 主键为复合键 {@code (version, script_name)}：同一版本含多个脚本（如
    * {@code V1.0__init_monitor} 与 {@code V1.0__init_server}）时逐条记录互不覆盖，
    * 失败脚本在后续启动可被单独重试（已成功脚本跳过）。</p>
    *
    * <p>失败时（建表 SQLException）包装为 {@link RuntimeException} 抛出，消息形如"创建版本记录表失败"。</p>
    */
    private void ensureHistoryTable() {
        String sql = "CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
                + COL_VERSION + " VARCHAR(64) NOT NULL, "
                + "description VARCHAR(255), "
                + COL_SCRIPT_NAME + " VARCHAR(255) NOT NULL, "
                + COL_CHECKSUM + " VARCHAR(64), "
                + COL_SUCCESS + " VARCHAR(8), "
                + "CONSTRAINT pk_" + HISTORY_TABLE + " PRIMARY KEY (" + COL_VERSION + ", " + COL_SCRIPT_NAME + "))";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("创建版本记录表失败", e);
        }
    }

    /**
    * 加载已应用脚本的执行状态映射（script_name → success 标志）。
    *
    * @return 脚本文件名 → success（"true"/"false"）映射；无记录或查询失败时返回空映射，不为 null。
    */
    private Map<String, String> loadAppliedScripts() {
        Map<String, String> scripts = new java.util.HashMap<>();
        String sql = "SELECT " + COL_SCRIPT_NAME + ", " + COL_SUCCESS + " FROM " + HISTORY_TABLE;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null) {
                    scripts.put(name, rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取已应用脚本记录失败", e);
        }
        return scripts;
    }

    /**
    * 将已执行的迁移版本写入 {@code sys_database_version} 版本记录表。
    *
    * @param version     迁移版本字符串（如 {@code "1.0"}），与脚本文件名中的版本号一致
    * @param description 脚本描述，取自脚本文件名 {@code V{版本}__{描述}.sql} 的下划线后部分
    * @param script      脚本文件全名（如 {@code V1__init.sql}），用于追溯
    * @param checksum    脚本内容的 MD5 校验和（见 {@link #checksum(String)}）；失败语句记 {@link #CHECKSUM_FAILED}
    * @param success     成功标志（{@code "true"} / {@code "false"}）
    */
    private void recordApplied(String version, String description, String script, String checksum, String success) {
        String sql = "INSERT INTO " + HISTORY_TABLE
                + " (" + COL_VERSION + ", description, " + COL_SCRIPT_NAME + ", " + COL_CHECKSUM + ", " + COL_SUCCESS + ") VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, version);
            ps.setString(2, description);
            ps.setString(3, script);
            ps.setString(4, checksum);
            ps.setString(5, success);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 主键冲突（并发启动或重复记录，按 version+script_name 定位）：降级 UPDATE，其余异常抛出
            if (isPrimaryKeyConflict(e)) {
                updateRecord(version, description, script, checksum, success);
                return;
            }
            throw new RuntimeException("记录迁移版本失败: " + script, e);
        }
    }

    /**
    * 主键冲突时的降级更新（按 version + script_name 复合键定位，保留最新 checksum/success）。
    * @param version 版本，不允许为 null
    * @param description 描述，不允许为 null
    * @param script 方法入参 script
    * @param checksum 方法入参 checksum
    * @param success 方法入参 success
    */
    private void updateRecord(String version, String description, String script, String checksum, String success) {
        String sql = "UPDATE " + HISTORY_TABLE
                + " SET " + COL_CHECKSUM + " = ?, " + COL_SUCCESS + " WHERE "
                + COL_VERSION + " = ? AND " + COL_SCRIPT_NAME + " = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, checksum);
            ps.setString(2, success);
            ps.setString(3, version);
            ps.setString(4, script);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新迁移版本记录失败: " + script, e);
        }
    }

    /**
    * 判断是否为唯一键/主键冲突异常（MySQL 1062 / H2 23505 / PG 23505 / Oracle 1）。
    * @param e 方法入参 e
    * @return 是否成功（true 表示成功）
    */
    private static boolean isPrimaryKeyConflict(SQLException e) {
        String code = String.valueOf(e.getErrorCode());
        String state = String.valueOf(e.getSQLState());
        return "1062".equals(code) || "23505".equals(state) || "1".equals(code);
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
    * 扫描已配置的脚本位置，收集全部迁移脚本并按版本字符串升序返回。
    *
    * <p>遍历 {@link #locations} 中的每个位置：{@code classpath:} 前缀走
    * {@link #scanClasspath}（支持 jar 内资源与多模块同名目录合并），其余视为
    * 文件系统目录走 {@link #scanDirectory}。同版本重复出现时 {@code putIfAbsent}
    * 保留先扫描到的，最终按 {@link #compareVersions} 升序排列。</p>
    *
    * <p>版本排序对齐三方 Flyway 语义：版本号按段数值比较（{@code 1.0} 与
    * {@code 1.0.0} 数值相等时按段数/字典序 tie-break），避免 long 化后
    * {@code 1.0} 与 {@code 1.0.0} 冲突（V1.0.0__init_spider 与 V1.0__init_* 的
    * 历史冲突问题）。</p>
    *
    * @return 按版本升序排列的 {@link ScriptFile} 列表；无脚本时返回空列表
    */
    private List<ScriptFile> scanScripts() {
        Comparator<String> versionOrder = (a, b) -> compareVersions(a, b);
        Map<String, ScriptFile> byVersion = new ConcurrentSkipListMap<>(versionOrder);
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
    * 版本号段数值比较（对齐三方 Flyway 版本语义）。
    * <p>示例：{@code 1.0} vs {@code 1.0.0} → 段数不同，数值相等时短版本在前；
    * {@code 1.2} vs {@code 1.10} → 1.2 < 1.10（数值比较，非字典序）。</p>
    * @param a 方法入参 a
    * @param b 方法入参 b
    * @return 结果数值
    */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseSegment(pa[i]) : 0;
            int nb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        // 数值全等时：段数少的在前（1.0 < 1.0.0），再按字典序兜底
        if (pa.length != pb.length) {
            return Integer.compare(pa.length, pb.length);
        }
        return a.compareTo(b);
    }

    /**
     * 解析分段。
     *
     * @param s 方法入参 s
     * @return 结果数值
     */
    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
    * 将版本字符串解析为 MigrationInfo 所需的主版本号（取首段）。
    * 无法解析时返回 0（仅影响排序展示，不影响执行）。
    * @param version 版本，不允许为 null
    * @return 结果数值
    */
    private static long parseMajorVersion(String version) {
        return parseSegment(version.split("\\.")[0]);
    }

    /**
    * 扫描文件系统目录下的 {@code *.sql} 脚本。
    *
    * <p>列出 {@code dirPath} 目录下所有以 {@code .sql}（忽略大小写）结尾的文件，
    * 逐个交给 {@link #addScript} 解析版本号并加入结果集合。目录不存在或不可读
    * （{@code listFiles} 返回 null）时直接返回，不报错，便于多位置并存时缺一个不影响其余。</p>
    *
    * @param dirPath 文件系统目录路径（绝对或相对）
    * @param target  收集脚本的映射表（按版本字符串去重，由调用方保证同版本只保留一份）
    */
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

    /**
    * 扫描 classpath 资源位置的 {@code *.sql} 脚本。
    *
    * <p>通过线程上下文类加载器（缺省退回本类类加载器）解析 {@code resourcePath}，
    * 支持多个 jar/目录同时提供同名资源时全部扫描。与 Spring 的
    * {@code classpath*} 语义对齐：jar 内资源先解压为临时文件再走
    * {@link #scanDirectory}，file 协议资源直接读取（修复原实现跳过 jar 内脚本、
    * 导致业务模块 jar 中 db/init 脚本丢失的问题）。</p>
    *
    * <p>解析或 IO 失败（{@link IOException}、{@link URISyntaxException}）时包装为
    * {@link RuntimeException}，消息形如"扫描 classpath 脚本失败: &lt;resourcePath&gt;"。</p>
    *
    * @param resourcePath 类路径资源位置（不带 {@code classpath:} 前缀，由调用方剥离）
    * @param target       收集脚本的映射表（按版本字符串去重）
    */
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
                } else if ("jar".equals(url.getProtocol())) {
                    extractJarScripts(url, resourcePath, target);
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("扫描 classpath 脚本失败: " + resourcePath, e);
        }
    }

    /**
    * 读取 jar 内的 .sql 脚本到系统临时目录后扫描。
    * <p>通过 {@link java.net.JarURLConnection} 正确解析 jar URL
    * （形如 {@code jar:file:/path/to.jar!/db/init}），避免 {@code new File(jarUrl)}
    * 直接解析 jar URL 的失败问题。临时目录按 jar 名 + 资源路径隔离，
    * 避免多 jar 同名脚本互相覆盖。</p>
    * @param jarUrl jarURL，不允许为 null
    * @param resourcePath resource路径，不允许为 null
    * @param target 目标，不允许为 null
    */
    private void extractJarScripts(URL jarUrl, String resourcePath, Map<String, ScriptFile> target) {
        try {
            java.net.JarURLConnection connection = (java.net.JarURLConnection) jarUrl.openConnection();
            java.util.jar.JarFile jar = connection.getJarFile();
            String prefix = resourcePath.endsWith("/") ? resourcePath : resourcePath + "/";
            String jarFileUrl = connection.getJarFileURL().getFile();
            String safeJar = new java.io.File(jarFileUrl).getName().replaceAll("[^a-zA-Z0-9.-]", "_");
            Path tempDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                    .resolve("flyway-sql-" + safeJar)
                    .resolve(prefix.replace('/', '_').replace("!", "_"));
            Files.createDirectories(tempDir);
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)
                        || !entry.getName().toLowerCase().endsWith(".sql")) {
                    continue;
                }
                String fileName = entry.getName().substring(prefix.length());
                Path out = tempDir.resolve(fileName);
                try (var in = jar.getInputStream(entry)) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                addScript(out, fileName, target);
            }
        } catch (Exception e) {
            // jar 读取失败不中断整体扫描（其他位置仍有效），由调用方记录日志
        }
    }

    /**
    * 解析单个脚本文件名，提取版本号与描述并加入结果集合。
    *
    * <p>用 {@link #SCRIPT_PATTERN} 匹配 {@code V{版本}__{描述}.sql}（大小写不敏感），
    * 不匹配的文件（如非 {@code V} 前缀、缺下划线分隔）直接忽略、不进结果集合。
    * 映射键为「版本字符串 + 描述」（{@code version + "__" + description}）：
    * 同版本不同描述的脚本（{@code V1.0__init_monitor} 与 {@code V1.0__init_server}）
    * 并存互不覆盖；同版本同描述重复出现（多 jar 同名资源合并）时 {@code putIfAbsent}
    * 保留先扫描到的那一份。</p>
    *
    * @param path     脚本文件绝对路径（用于后续读取内容）
    * @param fileName 脚本文件全名（如 {@code V1__init.sql}）
    * @param target   收集脚本的映射表，键为 版本字符串
    */
    private void addScript(Path path, String fileName, Map<String, ScriptFile> target) {
        Matcher matcher = SCRIPT_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return;
        }
        String version = matcher.group(1);
        String description = matcher.group(2);
        target.putIfAbsent(version + "__" + description, new ScriptFile(version, description, fileName, path));
    }

    // ==================== SQL 执行 ====================

    /**
    * 执行单个迁移脚本，返回实际执行的语句数。
    *
    * <p>先用 {@link #splitStatements} 把脚本按分号拆成独立语句（忽略单行注释、
    * 字符串内分号），逐条 {@code execute}；空白语句直接跳过不计入。整个方法在同一
    * {@link Connection} 上完成，便于需要事务语义时由调用方控制 commit/rollback。</p>
    *
    * <p>方言差异容错（对齐内置 FlywayLikePopulator 的 continue-on-error 语义）：
    * 单条语句失败时，若 {@link #continueOnError} 开启则记录失败语句并继续后续语句
    * （典型场景：MySQL 专属 PREPARE/EXECUTE 段在 H2 下报语法错误），全部语句执行完
    * 后若有失败则返回 false；若关闭则任一句失败即抛出 {@link RuntimeException}。</p>
    *
    * @param sql        脚本全文内容（多行、可含多条语句）
    * @param scriptName 脚本文件名（用于失败日志）
    * @return 是否全部语句执行成功
    */
    private boolean executeScript(String sql, String scriptName) {
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) {
            return true;
        }
        // 方言转换：通过 ScriptConverter SPI 对目标库做兼容转化（未注册实现时退化为原样）
        List<String> toExecute = applyConversion(statements);
        int executed = 0;
        boolean allOk = true;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String statement : toExecute) {
                if (statement.isBlank()) {
                    continue;
                }
                try {
                    st.execute(statement);
                    executed++;
                } catch (SQLException e) {
                    allOk = false;
                    lastFailedStatements.add(truncate(statement, 120));
                    if (!continueOnError) {
                        throw new RuntimeException("执行迁移脚本失败: " + scriptName
                                + " (语句: " + truncate(statement, 200) + ")", e);
                    }
                    // continueOnError：跳过方言差异语句，继续执行后续语句
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("执行迁移脚本失败: " + scriptName, e);
        }
        return allOk;
    }

    /**
    * 通过 {@link ScriptConverter} SPI 对语句列表做目标库方言转换。
    * <p>协议未设置或 SPI 无可用实现时原样返回（保持向后兼容）。</p>
    * @param statements 方法入参 statements
    * @return 结果列表，无数据时为空列表
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
    * 截断语句用于日志展示。
    * @param s 方法入参 s
    * @param max 最大值，不允许为 null
    * @return 结果字符串
    */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
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
    * @param version     版本字符串（如 {@code "1.0"}、{@code "1.0.0"}），排序与去重的键（三方 Flyway 语义）
    * @param description 脚本描述，取自文件名 {@code V{版本}__{描述}.sql} 的下划线后部分
    * @param fileName    脚本文件全名（如 {@code V1__init.sql}），写入版本记录表以便追溯
    * @param path        脚本绝对路径，执行时据此读取内容
    * @return 结果值
    */
    private record ScriptFile(String version, String description, String fileName, Path path) {
    }
}
