package com.chua.common.support.lang.datasource.flyway;

import com.chua.common.support.file.resource.Resource;
import com.chua.common.support.file.resource.ResourceConfiguration;
import com.chua.common.support.file.resource.ResourceFlow;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迁移脚本扫描与解析工具，供所有 {@link Flyway} 实现共用。
 *
 * <p>收敛此前分散在 {@code DefaultFlyway} / {@code DataSourceFlyway} 中的重复实现，
 * 统一三件事：脚本命名解析、位置扫描、语句拆分。任何新增迁移实现都应复用本类，
 * 避免各实现的脚本命名兼容性和扫描范围再次分叉。</p>
 *
 * <h2>脚本命名</h2>
 * <p>{@code V{版本}__{描述}.sql}，版本号支持点分多段（{@code V1}、{@code V1.0}、
 * {@code V1.0.0.2}）；不匹配该命名的文件一律忽略（如 {@code README.sql}）。
 * 版本排序按段数值比较：{@code 1.2 < 1.10}，数值全等时段数少的在前。</p>
 *
 * <h2>位置语义</h2>
 * <ul>
 *   <li>{@code classpath:db/init}、{@code classpath*:db/init}：等价处理，都枚举类路径上
 *       <strong>全部</strong>同名根（目录与 jar 内资源都扫描）。多模块部署时各模块 jar 里的
 *       {@code db/init} 必须全部命中，因此不采用"仅首个匹配位置"语义。</li>
 *   <li>位置中已含通配符时按原样使用，否则自动追加 {@code /**&#47;*.sql} 递归匹配子目录。</li>
 *   <li>不带前缀的位置视为文件系统目录，递归扫描。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FlywayScripts {

    /**
     * 单模块类路径前缀
     */
    public static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * 全类路径前缀
     */
    public static final String CLASSPATH_ALL_PREFIX = "classpath*:";

    /**
     * 默认版本与描述分隔符
     */
    public static final String DEFAULT_SEPARATOR = "__";

    /**
     * 脚本扩展名
     */
    private static final String SQL_EXTENSION = ".sql";

    /**
     * 递归匹配子目录下全部脚本的通配后缀
     */
    private static final String RECURSIVE_SQL_GLOB = "/**/*" + SQL_EXTENSION;

    /**
     * 通配符标记
     */
    private static final String WILDCARD = "*";

    /**
     * 路径分隔符
     */
    private static final char PATH_SEPARATOR = '/';

    private FlywayScripts() {
    }

    /**
     * 扫描到的迁移脚本。
     *
     * @param version     版本字符串（如 {@code "1.0"}），排序与记录的主键成分
     * @param description 版本与 {@code .sql} 之间的描述段
     * @param fileName    脚本文件全名，写入版本记录表以便追溯
     * @param resource    脚本资源（目录文件或 jar 内资源），内容读取入口
     */
    public record Script(String version, String description, String fileName, Resource resource) {
    }

    /**
     * 以默认分隔符扫描位置列表。
     *
     * @param locations 脚本位置列表
     * @return 按版本升序排列的脚本列表
     */
    public static List<Script> scan(List<String> locations) {
        return scan(locations, DEFAULT_SEPARATOR, null);
    }

    /**
     * 扫描位置列表，返回按版本升序、同版本多脚本并存的脚本列表。
     *
     * <p>同版本同描述重复出现（多 jar 提供同名资源）时保留先扫描到的那一份；
     * 目录缺失不报错，便于多位置并存时缺一个不影响其余。</p>
     *
     * @param locations   脚本位置列表（{@code classpath:} / {@code classpath*:} / 文件系统目录）
     * @param separator   版本与描述分隔符，空则用 {@value #DEFAULT_SEPARATOR}
     * @param classLoader 类路径扫描使用的类加载器，空则取线程上下文类加载器
     * @return 按版本升序排列的脚本列表
     */
    public static List<Script> scan(List<String> locations, String separator, ClassLoader classLoader) {
        Pattern pattern = scriptPattern(separator);
        Map<String, Script> found = new LinkedHashMap<>();
        if (locations != null) {
            for (String location : locations) {
                collect(location, pattern, classLoader, found);
            }
        }
        List<Script> sorted = new ArrayList<>(found.values());
        sorted.sort(Comparator.comparing(Script::version, FlywayScripts::compareVersions)
                .thenComparing(Script::fileName));
        return sorted;
    }

    /**
     * 按单个位置收集脚本到结果集合。
     *
     * @param location    位置声明
     * @param pattern     脚本命名正则
     * @param classLoader 类加载器
     * @param found       结果集合（按 版本+描述 去重）
     */
    private static void collect(String location, Pattern pattern, ClassLoader classLoader, Map<String, Script> found) {
        if (location == null || location.isBlank()) {
            return;
        }
        String trimmed = location.trim();
        if (trimmed.startsWith(CLASSPATH_ALL_PREFIX) || trimmed.startsWith(CLASSPATH_PREFIX)) {
            scanClasspath(normalizeClasspathGlob(trimmed), pattern, classLoader, found);
        } else {
            scanDirectory(new File(trimmed), pattern, found);
        }
    }

    /**
     * 归一化类路径匹配模式：剥离前缀、去掉结尾斜杠，未含通配符时追加递归后缀。
     *
     * @param location 带 {@code classpath} 前缀的位置声明
     * @return 可直接交给资源查找器的模式（始终以 {@code classpath*:} 起头）
     */
    private static String normalizeClasspathGlob(String location) {
        String root = location.startsWith(CLASSPATH_ALL_PREFIX)
                ? location.substring(CLASSPATH_ALL_PREFIX.length())
                : location.substring(CLASSPATH_PREFIX.length());
        root = root.trim();
        while (root.length() > 1 && root.charAt(root.length() - 1) == PATH_SEPARATOR) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.contains(WILDCARD)) {
            return CLASSPATH_ALL_PREFIX + root;
        }
        return CLASSPATH_ALL_PREFIX + root + RECURSIVE_SQL_GLOB;
    }

    /**
     * 扫描类路径（含 jar 内资源）。
     *
     * @param glob        资源匹配模式
     * @param pattern     脚本命名正则
     * @param classLoader 类加载器
     * @param found       结果集合
     */
    private static void scanClasspath(String glob, Pattern pattern, ClassLoader classLoader, Map<String, Script> found) {
        ResourceConfiguration configuration = ResourceConfiguration.builder()
                .classLoader(resolveClassLoader(classLoader))
                .build();
        ResourceFlow flow = ResourceFlow.ofNoCache(glob, configuration);
        if (flow == null) {
            return;
        }
        Set<Resource> resources = flow.getResources();
        if (resources == null) {
            return;
        }
        for (Resource resource : resources) {
            if (resource == null) {
                continue;
            }
            String fileName = resource.getName();
            addScript(found, pattern, fileName, resource);
        }
    }

    /**
     * 递归扫描文件系统目录。
     *
     * @param dir     目录
     * @param pattern 脚本命名正则
     * @param found   结果集合
     */
    private static void scanDirectory(File dir, Pattern pattern, Map<String, Script> found) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        Path root = dir.toPath();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(SQL_EXTENSION))
                    .sorted()
                    .forEach(path -> addScript(found, pattern, path.getFileName().toString(),
                            Resource.create(path.toFile())));
        } catch (IOException e) {
            throw new RuntimeException("扫描迁移脚本目录失败: " + root, e);
        }
    }

    /**
     * 解析脚本文件名并入集合；命名不匹配则忽略。
     *
     * @param found    结果集合
     * @param pattern  脚本命名正则
     * @param fileName 文件名
     * @param resource 资源
     */
    private static void addScript(Map<String, Script> found, Pattern pattern, String fileName, Resource resource) {
        if (fileName == null) {
            return;
        }
        Matcher matcher = pattern.matcher(fileName);
        if (!matcher.matches()) {
            return;
        }
        String version = matcher.group(1);
        String description = matcher.group(2);
        found.putIfAbsent(version + DEFAULT_SEPARATOR + description,
                new Script(version, description, fileName, resource));
    }

    /**
     * 构造脚本命名正则。
     *
     * @param separator 版本与描述分隔符
     * @return 命名正则
     */
    private static Pattern scriptPattern(String separator) {
        String sep = separator == null || separator.isEmpty()
                ? DEFAULT_SEPARATOR : Pattern.quote(separator);
        return Pattern.compile("^V([0-9]+(?:\\.[0-9]+)*)" + sep + "(.*)"
                + Pattern.quote(SQL_EXTENSION) + "$", Pattern.CASE_INSENSITIVE);
    }

    /**
     * 解析可用类加载器。
     *
     * @param classLoader 调用方指定的类加载器，可为空
     * @return 线程上下文类加载器，缺失时退回本类类加载器
     */
    private static ClassLoader resolveClassLoader(ClassLoader classLoader) {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : FlywayScripts.class.getClassLoader();
    }

    /**
     * 版本号段数值比较（对齐三方 Flyway 版本语义）。
     *
     * @param a 版本 a
     * @param b 版本 b
     * @return 比较结果
     */
    public static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int na = i < left.length ? parseSegment(left[i]) : 0;
            int nb = i < right.length ? parseSegment(right[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        if (left.length != right.length) {
            return Integer.compare(left.length, right.length);
        }
        return a.compareTo(b);
    }

    /**
     * 取版本首段作为主版本号，供仅支持数值的迁移信息使用。
     *
     * @param version 版本字符串
     * @return 主版本号，无法解析时为 0
     */
    public static long majorVersion(String version) {
        return parseSegment(version == null ? "" : version.split("\\.")[0]);
    }

    /**
     * 解析版本段为整数，非数字段按 0 处理。
     *
     * @param segment 版本段
     * @return 段数值
     */
    private static int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 读取脚本内容（UTF-8）。
     *
     * @param script 脚本
     * @return 脚本全文
     */
    public static String readContent(Script script) {
        return readContent(script.resource(), script.fileName());
    }

    /**
     * 读取资源内容（UTF-8）。
     *
     * @param resource 资源
     * @param label    失败信息中使用的名称
     * @return 资源全文
     */
    public static String readContent(Resource resource, String label) {
        try (InputStream in = resource.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取迁移脚本失败: " + label, e);
        }
    }

    /**
     * 计算脚本内容校验和（MD5 十六进制小写）。
     *
     * <p>MD5 仅用于检测脚本内容是否被改动，不承担安全职责。
     * 算法不可用时退化为 {@code String#hashCode()}。</p>
     *
     * @param content 脚本全文
     * @return 校验和
     */
    public static String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * 按分号拆分脚本为独立语句。
     *
     * <p>逐字符扫描：跳过字符串外的 {@code --} 行注释；单引号字符串内的分号不作分隔符；
     * 字符串外的 {@code ;} 处切分。末尾非空白内容补为最后一条。空语句保留在列表中，
     * 由执行方跳过，保证与脚本结构一一对应。</p>
     *
     * @param sql 脚本全文
     * @return 拆分后的语句列表（不含分隔分号）；空输入返回空列表
     */
    public static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return statements;
        }
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
     * 截断语句用于日志展示。
     *
     * @param sql 语句
     * @param max 最大长度
     * @return 截断后的语句
     */
    public static String truncate(String sql, int max) {
        if (sql == null || sql.length() <= max) {
            return sql;
        }
        return sql.substring(0, max) + "...";
    }
}
