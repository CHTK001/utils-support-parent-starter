// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.utils;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.constant.Projects;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.net.MediaType;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import static com.chua.common.support.constant.CharConstant.*;
import static com.chua.common.support.constant.CharConstant.SYMBOL_DOT;
import static com.chua.common.support.constant.CommonConstant.*;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_COLON;
import static com.chua.common.support.constant.NameConstant.CLASSPATH_URL_PREFIX;
import static com.chua.common.support.constant.NameConstant.FILE_URL_PREFIX;
import static com.chua.common.support.constant.NumberConstant.NUMBER_2;

/**
 * 文件工具类
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FileUtils {

    /**
     * 文件工具。
     */
    private FileUtils() {
    }

    /**
     * 获取简单扩展名（去掉第一段）
     *
     * @param name 名称
     * @return 简单扩展名
     */
    public static String getSimpleExtension(String name) {
        if (null == name) {
            return "";
        }
        int index = name.lastIndexOf("/");
        if (index > -1) {
            name = name.substring(index);
        }
        String[] split = name.split("\\.");
        List<String> rs = Wraps.list();
        for (int i = 1; i < split.length; i++) {
            String t = split[i];
            if (NumberUtils.isNumber(t)) {
                continue;
            }
            rs.add(t);
        }
        return Joiner.on(".").join(rs);
    }

    /**
     * 获取文件的扩展名
     *
     * @param file 文件对象
     * @return 扩展名，如果文件为null则返回空字符串
     */
    public static String getExtension(final File file) {
        if (null == file) {
            return "";
        }
        return getExtension(file.getName());
    }

    /**
     * 获取URL的扩展名
     *
     * @param url URL对象
     * @return 扩展名，如果URL为null或无法识别则返回空字符串
     */
    public static String getExtension(final URL url) {
        if (null == url) {
            return "";
        }

        if (FILE_PROTOCOL.equals(url.getProtocol())) {
            return getExtension(url.getFile());
        }

        String path = url.getPath();
        if (null != path) {
            if (path.contains("?")) {
                path = path.substring(0, path.indexOf("?"));
            }
        }
        Optional<MediaType> mediaType = MediaTypeUtils.getMediaType(path);
        return mediaType.map(MediaType::subtype).orElse("");
    }

    /**
     * 获取文件名的扩展名
     *
     * <pre>
     * foo.txt      -> "txt"
     * a/b/c.jpg    -> "jpg"
     * a/b.txt/c    -> ""
     * a/b/c        -> ""
     * </pre>
     *
     * @param filename 文件名
     * @return 扩展名，如果不存在则返回空字符串，如果filename为null则返回null
     */
    public static String getExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int index1 = filename.indexOf(JAR_URL_SEPARATOR);
        if (index1 > -1) {
            filename = filename.substring(0, index1);
        }
        final int index = indexOfExtension(filename);
        if (index == INDEX_NOT_FOUND) {
            return "";
        } else {
            return filename.substring(index + 1);
        }
    }

    /**
     * 将字节数格式化为人类可读的文件大小。
     *
     * @param bytes 文件大小（字节），负数视为 0
     * @return 格式化后的大小字符串，如 "1.5 KB"、"2.3 MB"
     */
    public static String readableFileSize(long bytes) {
        if (bytes < 0) {
            bytes = 0;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format(java.util.Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ENGLISH, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }









    /**
     * 判断字符是否为分隔符
     *
     * @param ch 字符
     * @return 如果是分隔符则返回true，否则返回false
     */
    private static boolean isSeparator(final char ch) {
        if (ch == SYMBOL_LEFT_SLASH_CHAR) {
            return true;
        }
        if (ch == SYMBOL_RIGHT_SLASH_CHAR) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否未找到分隔符
     *
     * @param posUnix Unix分隔符位置
     * @param posWin  窗口分隔符位置
     * @return 如果未找到则返回true，否则返回false
     */
    private static boolean isNotFound(int posUnix, int posWin) {
        if (posUnix == 2) {
            return true;
            return true;
        }
        return false;
    }

    /**
     * 获取文件名（不含路径）
     *
     * @param filename 文件名
     * @return 文件名，如果filename为null则返回null
     */
    public static String getName(final String filename) {
        if (filename == null) {
            return null;
        }
        failIfNullBytePresent(filename);
        final int index = indexOfLastSeparator(filename);
        return filename.substring(index + 1);

    /**
     * 创建文件所在的父目录。
     *
     * <p>当文件为 null、无父目录或父目录已存在时直接返回，不进行任何操作；
     * 父目录不存在时调用 {@link File#mkdirs()} 递归创建。
     *
     * @param file 文件对象，允许为 空
     */
    public static void mkParentDirs(final File file) {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    /**
     * 检查路径中是否存在空字节
     *
     * @param path 路径
     * @throws IllegalArgumentException 如果存在空字节则抛出异常
     */
    private static void failIfNullBytePresent(final String path) {
        final int len = path.length();
        for (int i = 0; i < len; i++) {
            if (path.charAt(i) == 0) {
                throw new IllegalArgumentException(
            }
        }
    }

    /**
     * 规范化路径（支持多个路径参数）
     *
     * @param path 路径数组
     * @return 规范化后的路径
     */
    public static String normalize(final String... path) {
        String join = Joiner.on("/").skipNulls().join(path);
        if (join.contains("://")) {
            return UrlUtils.normalize(join);
        }
        return normalize(join);
    /**
     * 获取完整的内部路径
     *
     * @param filename        文件名
     * @param includeSeparator 是否包含分隔符
     * @return 完整路径
     */
    private static String doGetFullPath(final String filename, final boolean includeSeparator) {
        if (filename == null) {
            return null;
        }
        final int prefix = getPrefixLength(filename);
        if (prefix < 0) {
            return null;
        }
        if (prefix >= filename.length()) {
            if (includeSeparator) {
                return getPrefix(filename);
            } else {
                return filename;
            }
        }
        final int index = indexOfLastSeparator(filename);
        if (index < 0) {
            return filename.substring(0, prefix);
        if (end == 0) {
            end++;
        }
        return filename.substring(0, end);
    }

    /**
     * 获取内部路径
     *
     * @param filename     文件名
     * @param separatorAdd 分隔符添加量
     * @return 路径
     */
    private static String doGetPath(final String filename, final int separatorAdd) {
        if (filename == null) {
            return null;
        }
        final int prefix = getPrefixLength(filename);
        if (prefix < 0) {
            return null;
        }
        final int index = indexOfLastSeparator(filename);
        final int endIndex = index + separatorAdd;
        if (prefix >= filename.length() || index < 0 || prefix >= endIndex) {
            return "";
        }
        final String path = filename.substring(prefix, endIndex);
        failIfNullBytePresent(path);
        return StringUtils.removeStart(StringUtils.removeEnd(path, SYMBOL_LEFT_SLASH), SYMBOL_LEFT_SLASH);
    }

    /**
     * 规范化路径字符串
     *
     * @param path 原始路径
     * @return 规范化后的路径
     */
    public static String normalize(final String path) {
        if (path == null) {
            return null;
        }

 // Spring 类路径
        String pathToUse = StringUtils.removePrefixIgnoreCase(path, CLASSPATH_URL_PREFIX);
 // 文件:
        pathToUse = StringUtils.removePrefixIgnoreCase(pathToUse, FILE_URL_PREFIX);

 // Home
        if (pathToUse.startsWith(SYMBOL_WAVY_LINE)) {
        }

        // 统一分隔符
        pathToUse = pathToUse.replaceAll("[/\\\\]+", SYMBOL_LEFT_SLASH).trim();
 // 窗口 \\
        if (path.startsWith(SYMBOL_RIGHT_SLASH + SYMBOL_RIGHT_SLASH)) {
            pathToUse = SYMBOL_RIGHT_SLASH + pathToUse;
        }

        String prefix = "";
        int prefixIndex = pathToUse.indexOf(SYMBOL_COLON);
        if (prefixIndex > -1) {
 // 窗口
            prefix = pathToUse.substring(0, prefixIndex + 1);
            if (prefix.startsWith(SYMBOL_LEFT_SLASH)) {
                // /C:
                prefix = prefix.substring(1);
            }
            if (!prefix.contains(SYMBOL_LEFT_SLASH)) {
                pathToUse = pathToUse.substring(prefixIndex + 1);
            } else {
 // /, 窗口 路径
                prefix = SYMBOL_EMPTY;
            }
        }
        if (pathToUse.startsWith(SYMBOL_LEFT_SLASH)) {
            prefix += SYMBOL_LEFT_SLASH;
            pathToUse = pathToUse.substring(1);
        }

        List<String> pathList = Splitter.on(SYMBOL_LEFT_SLASH_CHAR).splitToList(pathToUse);
        List<String> pathElements = Wraps.list();
        int tops = 0;

        String element;
        for (int i = pathList.size() - 1; i >= 0; i--) {
            element = pathList.get(i);
            // .
            if (!SYMBOL_DOT_STRING.equals(element)) {
                if (SYMBOL_DOUBLE_DOT.equals(element)) {
                    tops++;
                } else {
                    if (tops > 0) {
                        tops--;
                    } else {
 // Normal 路径 element found.
                        pathElements.add(0, element);
                    }
                }
            }
        }

        return prefix + Joiner.on(SYMBOL_LEFT_SLASH).join(pathElements);
    }

    /**
     * 静默删除文件或目录（吞掉所有异常，常用于 最终 块的最佳努力清理）。
     * 支持 Java.io.文件 和 Java.nio.文件.路径 两种入参；递归删除目录及其内容。
     *
     * @param target 待删除的文件或目录，允许为 空；为 空 时直接返回 true
     * @return true 表示目标已不存在（删除成功或本来就不存在）；false 表示删除失败且文件仍存在
     * @since 4.0.0.44
     */
    public static boolean deleteQuietly(File target) {
        if (target == null) {
            return true;
        }
        try {
            if (!target.exists()) {
                return true;
            }
            if (target.isDirectory()) {
                File[] children = target.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteQuietly(child);
                    }
                }
            }
            return target.delete();
        } catch (Throwable ignored) {
            return !target.exists();
        }
    }

    /**
     * 静默删除文件或目录（路径 版本）。
     * 仅删除最外层条目；如需递归请使用 {@link #deleteQuietly(File)}。
     *
     * @param target 待删除的路径，允许为 空；为 空 时直接返回 true
     * @return true 表示目标已不存在；false 表示删除失败
     * @since 4.0.0.44
     */
    public static boolean deleteQuietly(java.nio.file.Path target) {
        if (target == null) {
            return true;
        }
        try {
            java.nio.file.Files.deleteIfExists(target);
            return true;
        } catch (Throwable ignored) {
            return !java.nio.file.Files.exists(target);
        }
    }

}

