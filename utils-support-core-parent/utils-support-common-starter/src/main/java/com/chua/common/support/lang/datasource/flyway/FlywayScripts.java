package com.chua.common.support.lang.datasource.flyway;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.file.resource.Resource;
import com.chua.common.support.file.resource.ResourceConfiguration;
import com.chua.common.support.file.resource.ResourceFlow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.chua.common.support.utils.StringUtils;

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

    /**
     * 默认分隔符下的脚本命名正则。
     *
     * <p>[P3C C1 修复] 原先每次扫描都在方法体内重新编译同一正则；现把默认分隔符
     * （覆盖「未传分隔符」与显式传默认值两种情况，二者编译出的匹配语义一致）的
     * 正则预编译为类级常量，仅自定义分隔符仍按原逻辑动态编译。</p>
     */
    private static final Pattern DEFAULT_SCRIPT_PATTERN = Pattern.compile(
            "^V([0-9]+(?:\\.[0-9]+)*)" + DEFAULT_SEPARATOR + "(.*)"
                    + Pattern.quote(SQL_EXTENSION) + "$", Pattern.CASE_INSENSITIVE);

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
        Map<String, Script> found = Wraps.linkedMap();
        if (locations != null) {
            for (String location : locations) {
                collect(location, pattern, classLoader, found);
            }
        }
        List<Script> sorted = Wraps.list(found.values());
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
        if (StringUtils.isBlank(location)) {
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
    private static void scanClasspath(String glob, Pattern pattern, ClassLoader classLoader,
                                      Map<String, Script> found) {
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
        // [P3C C1 修复] 默认分隔符（含未传与显式传默认值）复用预编译常量，避免重复编译；
        // 自定义分隔符仍按原逻辑动态编译（正则串与原实现逐字一致）
        if (StringUtils.isEmpty(separator) || DEFAULT_SEPARATOR.equals(separator)) {
            return DEFAULT_SCRIPT_PATTERN;
        }
        return Pattern.compile("^V([0-9]+(?:\\.[0-9]+)*)" + Pattern.quote(separator) + "(.*)"
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
     * 判断脚本是否为初始化数据脚本。
     *
     * @param fileName 脚本文件名
     * @return 文件名以 {@code initdata_} 开头时返回 {@code true}
     */
    public static boolean isInitData(String fileName) {
        return isInitData(fileName, DEFAULT_SEPARATOR);
    }

    /**
     * 按指定分隔符判断脚本是否为初始化数据脚本。
     *
     * @param fileName 脚本文件名
     * @param separator 版本与描述分隔符
     * @return 文件名以 {@code initdata_} 开头时返回 {@code true}
     */
    public static boolean isInitData(String fileName, String separator) {
        if (fileName == null) {
            return false;
        }
        String actualSeparator = StringUtils.isEmpty(separator) ? DEFAULT_SEPARATOR : separator;
        int index = fileName.indexOf(actualSeparator);
        if (index < 0) {
            return false;
        }
        String description = fileName.substring(index + actualSeparator.length());
        return description.startsWith("initdata_");
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
            var builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                // [P3C A20 豁免]
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                // [P3C A20 豁免]
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
     * <p><b>反斜杠转义</b>：字符串内出现 {@code \'} 时不结束字符串（MySQL 风格转义），
     * {@code \\} 也不结束字符串。否则一旦种子数据里出现 {@code \'}
     * 这类被转义的引号（中文诗词里引用词句很常见），字符串会被提前闭合，
     * 其后到下一个 {@code '} 之间的内容（含大量分号）会被当成 SQL 语句 ——
     * 实测 eduai 的 16MB 种子因此被并成一条 6 万字符的「语句」，
     * 执行时整条报语法错。</p>
     *
     * @param sql 脚本全文
     * @return 拆分后的语句列表（不含分隔分号）；空输入返回空列表
     */
    public static List<String> splitStatements(String sql) {
        List<String> statements = Wraps.list();
        if (StringUtils.isEmpty(sql)) {
            return statements;
        }
        // 字节级核心与流式扫描共用同一套语句边界状态机；UTF-8 多字节序列的字节均
        // >= 0x80，不会与 ; ' - \ \n 等 ASCII 分隔符混淆，字节域判定与字符域等价。
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        try {
            scanStatements(new ByteArrayInputStream(bytes), statements::add);
        } catch (IOException e) {
            // ByteArrayInputStream 的读取不会抛出 IOException；仅为满足受检异常契约
            throw new UncheckedIOException(e);
        }
        return statements;
    }

    /**
     * 流式按分号拆分脚本，逐条回调，不在内存中保留全部语句。
     *
     * <p>切分语义与 {@link #splitStatements(String)} 完全一致（跳过字符串外的
     * {@code --} 行注释、单引号字符串内的分号不作分隔符、字符串内 {@code \'} /
     * {@code \\} 反斜杠转义、末尾非空白内容补为最后一条、空语句照常回调），
     * 差别仅在于逐块读取、逐条消费：任意时刻内存中只保留当前语句，因此
     * 数百 MB 的备份脚本也不会撑爆堆（全量读入 + 全量切分会产生数倍于脚本
     * 体积的瞬时内存，Xmx 1G 的进程实测直接 OutOfMemoryError）。</p>
     *
     * <p>字节级扫描直接消费原始字节，不再逐字符解码：UTF-8 的多字节序列字节
     * 均 >= 0x80，永远不会与 {@code ; ' - \ \n} 等 ASCII 分隔符混淆，因此
     * 语句边界判定在字节域与字符域完全等价，同时免去解码器逐字节状态转换
     * 的开销（305MB 备份脚本实测为预检热路径的主要成本）。</p>
     *
     * <p>适用于预检语句计数、试运行逐条执行等大脚本场景；调用方负责关闭
     * 传入的 {@link InputStream}（建议 try-with-resources）。回调内抛出的
     * 运行时异常会中止读取并向调用方传播。</p>
     *
     * @param in       脚本字节流（UTF-8 编码）
     * @param consumer 语句回调（每遇到一条语句调用一次，含空语句）
     * @throws IOException 读取失败
     */
    public static void forEachStatement(InputStream in, Consumer<String> consumer) throws IOException {
        scanStatements(in, consumer);
    }

    /**
     * 流式统计非空语句数（计数专用轻量路径）。
     *
     * <p>与 {@link #forEachStatement(InputStream, Consumer)} 共用同一套语句边界状态机，
     * 语义等价于「对每条语句做 {@code !raw.trim().isEmpty()} 过滤后计数」；
     * 差别在于<b>不物化语句字符串</b>——只跟踪当前语句是否出现过非空白字节，
     * 数十万条语句的大脚本计数时不再产生海量瞬时 {@link String}，
     * 显著降低分配速率与 GC 压力（恢复预检对 305MB 备份脚本计数时，原实现
     * 每条语句分配 builder + toString + trim 三份副本，实测为预检热路径）。</p>
     *
     * @param in 脚本字节流（UTF-8 编码）
     * @return 非空语句数
     * @throws IOException 读取失败
     */
    public static int countStatements(InputStream in) throws IOException {
        return scanStatements(in, null);
    }

    /**
     * 流式按分号拆分脚本（字符流兼容入口）。
     *
     * @param reader   脚本字符流（UTF-8 文本）
     * @param consumer 语句回调（每遇到一条语句调用一次，含空语句）
     * @throws IOException 读取失败
     * @deprecated 字符流入口仅为旧调用方保留：内部在线编码为 UTF-8 字节流后走同一
     * 字节级核心，不产生第二套拆分逻辑。新代码请直接使用
     * {@link #forEachStatement(InputStream, Consumer)}，免一层编码转换。
     */
    @Deprecated
    public static void forEachStatement(Reader reader, Consumer<String> consumer) throws IOException {
        scanStatements(utf8Stream(reader), consumer);
    }

    /**
     * 流式统计非空语句数（字符流兼容入口）。
     *
     * @param reader 脚本字符流（UTF-8 文本）
     * @return 非空语句数
     * @throws IOException 读取失败
     * @deprecated 字符流入口仅为旧调用方保留：内部在线编码为 UTF-8 字节流后走同一
     * 字节级核心，不产生第二套拆分逻辑。新代码请直接使用
     * {@link #countStatements(InputStream)}。
     */
    @Deprecated
    public static int countStatements(Reader reader) throws IOException {
        return scanStatements(utf8Stream(reader), null);
    }

    /**
     * 语句边界扫描核心（字节级）：consumer 为 null 时走纯计数路径（不构建缓冲）。
     *
     * <p>全部语句入口（{@link #splitStatements(String)}、
     * {@link #forEachStatement(InputStream, Consumer)}、
     * {@link #countStatements(InputStream)} 与两个字符流兼容桥）都收敛到本方法，
     * 保证只有这一套语句边界状态机。</p>
     *
     * @param in       脚本字节流（UTF-8 编码）
     * @param consumer 语句回调；null 表示仅计数
     * @return 非空语句数（物化路径也返回计数值，便于统一）
     * @throws IOException 读取失败
     */
    private static int scanStatements(InputStream in, Consumer<String> consumer) throws IOException {
        return scanStatements(in, consumer, 1 << 16);
    }

    /**
     * 语句边界扫描核心（字节级，缓冲区大小可调）。
     *
     * <p>缓冲区大小参数化是为了等价性验证：用 1/2/3 字节小缓冲锤打
     * {@code --} 注释与反斜杠转义跨块边界的分支，确保与整块读取结果一致。</p>
     *
     * @param in         脚本字节流（UTF-8 编码）
     * @param consumer   语句回调；null 表示仅计数
     * @param bufferSize 读取缓冲区字节数（>= 1）
     * @return 非空语句数（物化路径也返回计数值，便于统一）
     * @throws IOException 读取失败
     */
    private static int scanStatements(InputStream in, Consumer<String> consumer, int bufferSize) throws IOException {
        boolean materialize = consumer != null;
        var current = materialize ? new ByteArrayOutputStream(8192) : null;
        boolean hasContent = false;    // 当前语句是否含非空白字节（等价于 !raw.trim().isEmpty()）
        boolean inString = false;
        boolean inComment = false;     // 正处于 -- 行注释中（注释内容连同换行一并丢弃）
        boolean prevDash = false;      // 上一字节为字符串外的 '-'，尚待与后续字节定性（可能组成 --）
        boolean pendingEscape = false; // 上一字节为字符串内的 '\'，下一字节是被转义字节，直接消费
        byte[] buf = new byte[bufferSize];
        int count = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xFF; // Java 字节有符号，UTF-8 多字节序列为负值，统一转无符号比较
                if (pendingEscape) {
                    // 字符串内的反斜杠转义：\' 不结束字符串，\\ 也不结束
                    pendingEscape = false;
                    if (materialize) {
                        current.write(b);
                    }
                    if (b > ' ') {
                        hasContent = true;
                    }
                    continue;
                }
                if (inComment) {
                    // 行注释：直到换行为止；换行符本身也丢弃（与字符版一致）
                    if (b == '\n') {
                        inComment = false;
                    }
                    continue;
                }
                if (prevDash) {
                    prevDash = false;
                    if (b == '-' && !inString) {
                        // 字符串外的 -- 行注释开始
                        inComment = true;
                        continue;
                    }
                    // 前一个 '-' 只是普通字符，先落笔，再按常规处理当前字节
                    if (materialize) {
                        current.write('-');
                    }
                    hasContent = true;
                }
                if (b == '\\' && inString) {
                    if (i + 1 < n) {
                        if (materialize) {
                            current.write(b);
                            current.write(buf[i + 1] & 0xFF);
                        }
                        hasContent = true;
                        i++;
                    } else {
                        // 块尾孤立 '\'：被转义字节在下一块
                        if (materialize) {
                            current.write(b);
                        }
                        hasContent = true;
                        pendingEscape = true;
                    }
                    continue;
                }
                if (b == '-' && !inString) {
                    // 先记下，若下一字节也是 '-' 则组成注释，否则作为普通字符落笔
                    prevDash = true;
                    continue;
                }
                if (b == '\'') {
                    inString = !inString;
                    if (materialize) {
                        current.write(b);
                    }
                    hasContent = true;
                } else if (b == ';' && !inString) {
                    if (materialize) {
                        consumer.accept(current.toString(StandardCharsets.UTF_8));
                        current.reset();
                    } else if (hasContent) {
                        count++;
                    }
                    hasContent = false;
                } else {
                    if (materialize) {
                        current.write(b);
                    }
                    if (b > ' ') {
                        hasContent = true;
                    }
                }
            }
        }
        if (prevDash) {
            // EOF 前的孤立 '-' 按普通字符补齐
            if (materialize) {
                current.write('-');
            }
            hasContent = true;
        }
        if (materialize) {
            // 与字符版一致：末尾非空白内容补为最后一条
            String tail = current.toString(StandardCharsets.UTF_8);
            if (!tail.isBlank()) {
                consumer.accept(tail);
            }
        } else if (hasContent) {
            count++;
        }
        return count;
    }

    /**
     * 把字符流在线编码为 UTF-8 字节流（旧字符流入口的兼容桥）。
     *
     * <p>仅作适配用，不追求吞吐：读一小块字符、编码成字节后按需供给字节级扫描核心。</p>
     *
     * @param reader 字符流
     * @return UTF-8 字节流
     */
    private static InputStream utf8Stream(Reader reader) {
        return new InputStream() {
            private final CharBuffer chars = CharBuffer.allocate(1 << 13);
            private ByteBuffer bytes = ByteBuffer.allocate(0);
            private boolean eof = false;

            private void fill() throws IOException {
                if (eof) {
                    return;
                }
                chars.clear();
                int n = reader.read(chars);
                if (n == -1) {
                    eof = true;
                    return;
                }
                chars.flip();
                bytes = StandardCharsets.UTF_8.encode(chars);
            }

            @Override
            public int read() throws IOException {
                if (!bytes.hasRemaining()) {
                    fill();
                    if (!bytes.hasRemaining()) {
                        return -1;
                    }
                }
                return bytes.get() & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                int total = 0;
                while (total < len) {
                    if (!bytes.hasRemaining()) {
                        fill();
                        if (!bytes.hasRemaining()) {
                            break;
                        }
                    }
                    int toCopy = Math.min(len - total, bytes.remaining());
                    bytes.get(b, off + total, toCopy);
                    total += toCopy;
                }
                return total == 0 ? -1 : total;
            }
        };
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

    /**
     * 取出语句操作的目标表名。
     *
     * <p>用于「目标表不存在就跳过该语句」的判存：迁移脚本里存在操作<b>外部系统表</b>的语句
     * （本仓没有该表的建表脚本、库也不共用），这类语句在目标库里必然失败。
     * 先把表名取出来，用元数据接口判存在与否，不存在就跳过，
     * 避免每次启动都刷一屏失败日志。</p>
     *
     * <p>支持的语句形态（只看第一处目标表）：</p>
     * <ul>
     *   <li>{@code INSERT INTO t} / {@code INSERT IGNORE INTO t}</li>
     *   <li>{@code UPDATE t SET ...}</li>
     *   <li>{@code DELETE FROM t} / {@code DELETE t1 FROM t t1 ...}</li>
     *   <li>{@code MERGE INTO t}</li>
     *   <li>{@code TRUNCATE TABLE t}</li>
     * </ul>
     *
     * <p>只取<b>第一处</b>目标表：语句里的第二张表（自连接、子查询里的表）若也不存在，
     * 执行时仍会失败，但那种语句本就依赖多表，判存第一处已能覆盖「表整体缺失」的场景。</p>
     *
     * @param sql 单条语句
     * @return 目标表名（不含引号、大小写按原样）；取不到时返回 null
     */
    public static String extractTargetTable(String sql) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        String s = sql.trim();
        Matcher m = TARGET_TABLE.matcher(s);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        if (raw == null) {
            return null;
        }
        raw = raw.trim();
        if (raw.length() >= 2) {
            char q = raw.charAt(0);
            if ((q == '`' || q == '"') && raw.charAt(raw.length() - 1) == q) {
                return raw.substring(1, raw.length() - 1);
            }
        }
        return raw.matches("[A-Za-z_]\\w*") ? raw : null;
    }

    /**
     * 语句的目标表：{@code INSERT INTO} / {@code UPDATE} / {@code DELETE FROM} / {@code MERGE INTO}
     * / {@code TRUNCATE TABLE} 之后的表名（可带反引号或双引号）
     *
     * <p>MySQL 的多表删除 {@code DELETE t1 FROM tbl t1 ...} 里的 {@code t1} 是<b>别名</b>、
     * 真正的表是 {@code FROM} 后面那个，故 DELETE 分支优先取 {@code FROM} 后的表名。</p>
     */
    private static final Pattern TARGET_TABLE = Pattern.compile(
            "(?is)^\\s*(?:"
                    + "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+"
                    + "|UPDATE\\s+"
                    + "|DELETE\\s+(?:[A-Za-z_]\\w*\\s+)?FROM\\s+"
                    + "|MERGE\\s+INTO\\s+"
                    + "|TRUNCATE\\s+(?:TABLE\\s+)?"
                    + ")[`\"]?([A-Za-z_]\\w*)[`\"]?\\b");
}
