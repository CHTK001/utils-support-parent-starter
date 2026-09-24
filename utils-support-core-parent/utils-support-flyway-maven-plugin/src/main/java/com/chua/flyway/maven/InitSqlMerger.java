package com.chua.flyway.maven;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 初始化脚本合并器.
 *
 * <p>纯 JDK 实现，无第三方依赖：递归扫描指定根目录下匹配通配模式的 SQL 脚本，
 * 按相对路径的稳定顺序合并为一份完整的初始化脚本内容。用于将按模块拆分的
 * {@code db/init/*.sql} 聚合为单一可直接执行的 init SQL。</p>
 *
 * <p>通配匹配基于将相对路径统一为正斜杠后的 glob→正则实现，跨平台一致，
 * 规避 {@code PathMatcher} 在 Windows 下对反斜杠路径匹配不稳定的问题。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class InitSqlMerger {

    /**
     * 分隔线长度.
     */
    private static final int DIVIDER_LENGTH = 72;

    /**
     * 头部时间戳格式.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 默认头部标题.
     */
    private static final String DEFAULT_TITLE = "Flyway 合并生成：按模块初始化的完整 init SQL";

    /**
     * 版本与描述分隔符（与运行期 Flyway 命名口径一致）.
     */
    private static final String VERSION_SEPARATOR = "__";

    /**
     * 工具类禁止实例化.
     */
    private InitSqlMerger() {
    }

    /**
     * 递归扫描根目录下匹配包含模式且不匹配排除模式的普通文件.
     *
     * <p>匹配基于根目录的相对路径（统一为正斜杠）与 glob 模式；结果按相对路径升序排列，
     * 保证跨平台与重复执行的稳定顺序。</p>
     *
     * @param root     扫描根目录
     * @param includes 包含通配模式列表，命中任意一条即保留
     * @param excludes 排除通配模式列表，命中任意一条即剔除
     * @return 命中的脚本文件列表（稳定顺序）
     * @throws IOException 目录遍历失败时抛出
     */
    public static List<Path> scan(final Path root, final List<String> includes, final List<String> excludes) throws IOException {
        final List<Pattern> includePatterns = toPatterns(includes);
        final List<Pattern> excludePatterns = toPatterns(excludes);
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        final String relative = toUnixPath(root.relativize(path));
                        return matchesAny(includePatterns, relative) && !matchesAny(excludePatterns, relative);
                    })
                    .sorted(Comparator.comparing(path -> toUnixPath(root.relativize(path))))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 将给定脚本列表合并为一份完整的初始化 SQL 文本.
     *
     * @param scripts   待合并脚本（调用方保证顺序稳定）
     * @param root      相对路径基准目录，仅用于注释中展示来源
     * @param charset   脚本读取字符集
     * @param header    是否输出总头部说明
     * @param timestamp 头部是否包含生成时间戳（默认关闭以获得确定性输出）
     * @return 合并后的完整 SQL 文本
     * @throws IOException 脚本读取失败时抛出
     */
    public static String merge(final List<Path> scripts, final Path root, final Charset charset,
                               final boolean header, final boolean timestamp) throws IOException {
        return merge(scripts, root, charset, header, timestamp, DEFAULT_TITLE);
    }

    /**
     * 将给定脚本列表合并为一份完整的初始化 SQL 文本（自定义头部标题）.
     *
     * @param scripts   待合并脚本（调用方保证顺序稳定）
     * @param root      相对路径基准目录，仅用于注释中展示来源
     * @param charset   脚本读取字符集
     * @param header    是否输出总头部说明
     * @param timestamp 头部是否包含生成时间戳（默认关闭以获得确定性输出）
     * @param title     头部标题
     * @return 合并后的完整 SQL 文本
     * @throws IOException 脚本读取失败时抛出
     */
    public static String merge(final List<Path> scripts, final Path root, final Charset charset,
                               final boolean header, final boolean timestamp, final String title) throws IOException {
        final StringBuilder builder = new StringBuilder();
        if (header) {
            appendHeader(builder, scripts.size(), timestamp, title);
        }
        for (final Path script : scripts) {
            appendScript(builder, root, script, charset);
        }
        return builder.toString();
    }

    /**
     * 追加分块头部说明.
     *
     * @param builder   目标缓冲
     * @param count     合并脚本数量
     * @param timestamp 是否写入时间戳
     * @param title     头部标题
     */
    private static void appendHeader(final StringBuilder builder, final int count, final boolean timestamp, final String title) {
        divider(builder);
        builder.append("-- ").append(title).append('\n');
        if (timestamp) {
            builder.append("-- 生成时间: ").append(LocalDateTime.now().format(TIMESTAMP_FORMAT)).append('\n');
        }
        builder.append("-- 脚本数量: ").append(count).append('\n');
        builder.append("-- 本文件由 Maven 插件自动生成，请勿手工编辑；修改源脚本后重新生成即可覆盖\n");
        divider(builder);
        builder.append('\n');
    }

    /**
     * 追加单个脚本内容.
     *
     * @param builder 目标缓冲
     * @param root    相对路径基准目录
     * @param script  脚本文件
     * @param charset 字符集
     * @throws IOException 读取失败时抛出
     */
    private static void appendScript(final StringBuilder builder, final Path root, final Path script, final Charset charset) throws IOException {
        final String relative = toUnixPath(root.relativize(script));
        final String content = new String(Files.readAllBytes(script), charset).trim();
        builder.append("-- source: ").append(relative).append('\n');
        divider(builder);
        builder.append(content);
        if (!content.endsWith(";")) {
            builder.append(';');
        }
        builder.append("\n\n");
    }

    /**
     * 输出一条分隔线.
     *
     * @param builder 目标缓冲
     */
    private static void divider(final StringBuilder builder) {
        builder.append("-- ");
        builder.append("-".repeat(DIVIDER_LENGTH));
        builder.append('\n');
    }

    /**
     * 提取脚本文件名中的版本号（{@code V{版本}__{描述}.sql} 的版本段）.
     *
     * @param script 脚本文件
     * @return 版本字符串；文件名不符合命名规范时返回 {@code null}
     */
    public static String versionOf(final Path script) {
        final String name = script.getFileName().toString();
        if (name.isEmpty() || name.charAt(0) != 'V') {
            return null;
        }
        final int end = name.indexOf(VERSION_SEPARATOR);
        if (end <= 1) {
            return null;
        }
        return name.substring(1, end);
    }

    /**
     * 是否为 initdata 脚本（{@code V{版本}__initdata_*.sql}，运行期按异步数据脚本执行）.
     *
     * @param script 脚本文件
     * @return true 表示 initdata 脚本
     */
    public static boolean isInitData(final Path script) {
        return "initdata".equals(scriptType(script.getFileName().toString()));
    }

    /**
     * 提取 {@code __} 之后的类型前缀，与运行期分类口径一致（initdata_/init_/add_/其它）.
     *
     * @param fileName 脚本文件名
     * @return 类型；不含分隔符时返回 {@code null}
     */
    private static String scriptType(final String fileName) {
        final int index = fileName.indexOf(VERSION_SEPARATOR);
        if (index < 0) {
            return null;
        }
        final String after = fileName.substring(index + VERSION_SEPARATOR.length());
        if (after.startsWith("initdata_")) {
            return "initdata";
        }
        if (after.startsWith("init_")) {
            return "init";
        }
        if (after.startsWith("add_")) {
            return "add";
        }
        return "normal";
    }

    /**
     * 去掉版本后缀（{@code 1.0.0-beta → 1.0.0}），保证合并文件名按 STABLE 通过运行期版本类型过滤.
     *
     * @param version 原始版本
     * @return 清理后的版本；为空时返回 {@code null}
     */
    public static String cleanVersion(final String version) {
        if (version == null) {
            return null;
        }
        final int dash = version.indexOf('-');
        final String cleaned = (dash >= 0 ? version.substring(0, dash) : version).trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 版本号比较：点分多段按数值逐段比较，缺段按 0，非数值段退化为字符串比较.
     *
     * @param left  左版本
     * @param right 右版本
     * @return 负数/0/正数
     */
    public static int compareVersions(final String left, final String right) {
        final String[] leftParts = left.split("\\.");
        final String[] rightParts = right.split("\\.");
        final int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            final String l = i < leftParts.length ? leftParts[i] : "0";
            final String r = i < rightParts.length ? rightParts[i] : "0";
            final int compared = compareSegment(l, r);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    /**
     * 比较单个版本段.
     *
     * @param left  左段
     * @param right 右段
     * @return 负数/0/正数
     */
    private static int compareSegment(final String left, final String right) {
        if (isNumeric(left) && isNumeric(right)) {
            try {
                return Long.compare(Long.parseLong(left), Long.parseLong(right));
            } catch (NumberFormatException ignored) {
                // 超长数字退化为字符串比较
            }
        }
        return left.compareTo(right);
    }

    /**
     * 判断字符串是否为纯数字段.
     *
     * @param value 字符串
     * @return true 表示纯数字
     */
    private static boolean isNumeric(final String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按「版本号数值升序 + 相对路径」重排脚本，与运行期扫描顺序一致.
     *
     * @param scripts 脚本列表（原地排序）
     * @param root    相对路径基准目录
     */
    public static void sortByVersionThenPath(final List<Path> scripts, final Path root) {
        scripts.sort((left, right) -> {
            final String leftVersion = cleanVersion(versionOf(left));
            final String rightVersion = cleanVersion(versionOf(right));
            int compared;
            if (leftVersion == null && rightVersion == null) {
                compared = 0;
            } else if (leftVersion == null) {
                compared = 1;
            } else if (rightVersion == null) {
                compared = -1;
            } else {
                compared = compareVersions(leftVersion, rightVersion);
            }
            if (compared != 0) {
                return compared;
            }
            return toUnixPath(root.relativize(left)).compareTo(toUnixPath(root.relativize(right)));
        });
    }

    /**
     * 取脚本列表中的最高版本（已去后缀）.
     *
     * @param scripts 脚本列表
     * @return 最高版本；无可解析版本时返回 {@code null}
     */
    public static String maxVersion(final List<Path> scripts) {
        String max = null;
        for (final Path script : scripts) {
            final String version = cleanVersion(versionOf(script));
            if (version == null) {
                continue;
            }
            if (max == null || compareVersions(version, max) > 0) {
                max = version;
            }
        }
        return max;
    }

    /**
     * 将相对路径统一为正斜杠形式.
     *
     * @param relative 相对路径
     * @return 正斜杠路径
     */
    private static String toUnixPath(final Path relative) {
        return relative.toString().replace('\\', '/');
    }

    /**
     * 将 glob 模式列表编译为正则列表.
     *
     * @param patterns glob 模式列表，可为 null
     * @return 已锚定的正则列表
     */
    private static List<Pattern> toPatterns(final List<String> patterns) {
        final List<Pattern> result = new ArrayList<>();
        if (patterns == null) {
            return result;
        }
        for (final String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                result.add(Pattern.compile(globToRegex(pattern.trim())));
            }
        }
        return result;
    }

    /**
     * 判断字符串是否命中任意正则.
     *
     * @param patterns 正则列表
     * @param value    待判定字符串
     * @return 命中任意一条返回 true
     */
    private static boolean matchesAny(final List<Pattern> patterns, final String value) {
        for (final Pattern pattern : patterns) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 glob 模式转换为锚定正则.
     *
     * <p>支持 {@code **}（跨目录）、{@code *}（同目录内任意字符）、{@code ?}（单字符）；
     * 其余字符按字面量转义。目录分隔符固定为正斜杠。</p>
     *
     * @param glob glob 模式
     * @return 已锚定正则
     */
    private static String globToRegex(final String glob) {
        final StringBuilder regex = new StringBuilder("^");
        final int length = glob.length();
        for (int i = 0; i < length; i++) {
            final char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < length && glob.charAt(i + 1) == '*') {
                    if (i + 2 < length && glob.charAt(i + 2) == '/') {
                        regex.append("(?:[^/]*/)*");
                        i += 2;
                    } else {
                        regex.append(".*");
                        i++;
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(escapeLiteral(c));
            }
        }
        regex.append('$');
        return regex.toString();
    }

    /**
     * 转义 glob 中的正则元字符.
     *
     * @param c 字符
     * @return 转义后的片段
     */
    private static String escapeLiteral(final char c) {
        switch (c) {
            case '.':
            case '(':
            case ')':
            case '[':
            case ']':
            case '{':
            case '}':
            case '+':
            case '^':
            case '$':
            case '|':
            case '\\':
                return "\\" + c;
            default:
                return String.valueOf(c);
        }
    }
}
