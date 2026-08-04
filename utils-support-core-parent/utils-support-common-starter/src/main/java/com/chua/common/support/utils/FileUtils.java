package com.chua.common.support.utils;

import com.chua.common.support.constant.Projects;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.net.MediaType;

import java.io.File;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static com.chua.common.support.constant.CharConstant.*;
import static com.chua.common.support.constant.CharConstant.SYMBOL_DOT;
import static com.chua.common.support.constant.CommonConstant.*;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_COLON;
import static com.chua.common.support.constant.NameConstant.CLASSPATH_URL_PREFIX;
import static com.chua.common.support.constant.NameConstant.FILE_URL_PREFIX;
import static com.chua.common.support.constant.NumberConstant.NUMBER_2;
import org.jspecify.annotations.NullUnmarked;

/**
 * 文件工具类
 *
 * @author CH
 */
@NullUnmarked
public class FileUtils {

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
        List<String> rs = new LinkedList<>();
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
     * 获取文件的基础名称（不含路径和扩展名）
     *
     * <pre>
     * a/b/c.txt -> c
     * a.txt     -> a
     * a/b/c     -> c
     * a/b/c/    -> ""
     * </pre>
     *
     * @param file 文件对象
     * @return 基础名称，如果文件为null则返回null
     */
    public static String getBaseName(final File file) {
        if (null == file) {
            return null;
        }
        return getBaseName(file.getName());
    }

    /**
     * 获取文件名的基础名称（不含路径和扩展名）
     *
     * <pre>
     * a/b/c.txt -> c
     * a.txt     -> a
     * a/b/c     -> c
     * a/b/c/    -> ""
     * </pre>
     *
     * @param filename 文件名
     * @return 基础名称，如果filename为null则返回null
     */
    public static String getBaseName(final String filename) {
        return removeExtension(getName(filename));
    }

    /**
     * 移除文件名的扩展名
     *
     * <pre>
     * foo.txt -> foo
     * a\b\c.jpg -> a\b\c
     * a\b\c   -> a\b\c
     * a.b\c   -> a.b\c
     * </pre>
     *
     * @param filename 文件名
     * @return 移除扩展名后的文件名，如果filename为null则返回null
     */
    public static String removeExtension(final String filename) {
        if (filename == null) {
            return null;
        }
        failIfNullBytePresent(filename);

        final int index = indexOfExtension(filename);
        if (index == INDEX_NOT_FOUND) {
            return filename;
        } else {
            return filename.substring(0, index);
        }
    }

    /**
     * 查找扩展名的位置
     *
     * @param filename 文件名
     * @return 扩展名的起始位置，如果未找到则返回INDEX_NOT_FOUND
     */
    public static int indexOfExtension(final String filename) {
        if (filename == null) {
            return INDEX_NOT_FOUND;
        }
        final int extensionPos = filename.lastIndexOf(SYMBOL_DOT);
        final int lastSeparator = indexOfLastSeparator(filename);
        if (lastSeparator > extensionPos) {
            return INDEX_NOT_FOUND;
        }
        return extensionPos;
    }

    /**
     * 获取文件的完整路径
     *
     * <pre>
     * C:\a\b\c.txt -> C:\a\b\
     * ~/a/b/c.txt  -> ~/a/b/
     * a.txt        -> ""
     * a/b/c        -> a/b/
     * a/b/c/       -> a/b/c/
     * C:           -> C:
     * C:\          -> C:\
     * ~            -> ~/
     * ~/           -> ~/
     * ~user        -> ~user/
     * ~user/       -> ~user/
     * </pre>
     *
     * @param filename 文件名
     * @return 完整路径，如果filename为null则返回null
     */
    public static String getFullPath(final String filename) {
        return doGetFullPath(filename, true);
    }

    /**
     * 获取文件的路径部分
     *
     * <pre>
     * C:\a\b\c.txt -> a\b\
     * ~/a/b/c.txt  -> a/b/
     * a.txt        -> ""
     * a/b/c        -> a/b/
     * a/b/c/       -> a/b/c/
     * </pre>
     *
     * @param filename 文件名
     * @return 路径部分，如果filename为null则返回null
     */
    public static String getPath(final String filename) {
        return doGetPath(filename, 1);
    }

    /**
     * 获取文件的前缀（如盘符、用户主目录等）
     *
     * <pre>
     * Windows:
     * a\b\c.txt           -> ""          -> relative
     * \a\b\c.txt          -> "\"         -> current drive absolute
     * C:a\b\c.txt         -> "C:"        -> drive relative
     * C:\a\b\c.txt        -> "C:\"       -> absolute
     * \\server\a\b\c.txt  -> "\\server\" -> UNC
     *
     * Unix:
     * a/b/c.txt           -> ""          -> relative
     * /a/b/c.txt          -> "/"         -> absolute
     * ~/a/b/c.txt         -> "~/"        -> current user
     * ~                   -> "~/"        -> current user (slash added)
     * ~user/a/b/c.txt     -> "~user/"    -> named user
     * ~user               -> "~user/"    -> named user (slash added)
     * </pre>
     *
     * @param filename 文件名
     * @return 前缀，如果filename为null则返回null
     */
    public static String getPrefix(final String filename) {
        if (filename == null) {
            return null;
        }
        final int len = getPrefixLength(filename);
        if (len < 0) {
            return null;
        }
        if (len > filename.length()) {
            failIfNullBytePresent(filename + SYMBOL_LEFT_SLASH);
            return filename + SYMBOL_LEFT_SLASH;
        }
        final String path = filename.substring(0, len);
        failIfNullBytePresent(path);
        return path;
    }

    /**
     * 获取文件前缀的长度
     *
     * <p>此方法将处理Unix或Windows格式的文件。
     *
     * <p>前缀长度包括完整文件名中适用的第一个斜杠。因此，返回的长度可能大于输入字符串的长度。
     *
     * <pre>
     * Windows:
     * a\b\c.txt           -> ""          -> relative
     * \a\b\c.txt          -> "\"         -> current drive absolute
     * C:a\b\c.txt         -> "C:"        -> drive relative
     * C:\a\b\c.txt        -> "C:\"       -> absolute
     * \\server\a\b\c.txt  -> "\\server\" -> UNC
     * \\\a\b\c.txt        -> error, length = -1
     *
     * Unix:
     * a/b/c.txt           -> ""          -> relative
     * /a/b/c.txt          -> "/"         -> absolute
     * ~/a/b/c.txt         -> "~/"        -> current user
     * ~                   -> "~/"        -> current user (slash added)
     * ~user/a/b/c.txt     -> "~user/"    -> named user
     * ~user               -> "~user/"    -> named user (slash added)
     * //server/a/b/c.txt  -> "//server/"
     * ///a/b/c.txt        -> error, length = -1
     * </pre>
     *
     * <p>无论代码在哪台机器上运行，输出都将相同。即：无论Unix还是Windows前缀，都会被匹配。
     *
     * <p>注意：在Windows上，前导//（或\\）用于指示UNC名称。这些必须后跟服务器名称，因此双斜杠不会在文件名开头折叠为单斜杠。
     *
     * @param filename 要查找前缀的文件名
     * @return 前缀的长度，如果无效或为null则返回-1
     */
    public static int getPrefixLength(final String filename) {
        if (filename == null) {
            return INDEX_NOT_FOUND;
        }
        final int len = filename.length();
        if (len == 0) {
            return 0;
        }
        char ch0 = filename.charAt(0);
        if (ch0 == SYMBOL_COLON_CHAR) {
            return INDEX_NOT_FOUND;
        }
        if (len == 1) {
            if (ch0 == SYMBOL_WAVY_LINE_CHAR) {
                return 2;
            }
            if (isSeparator(ch0)) {
                return 1;
            }
            return 0;
        } else {
            if (ch0 == SYMBOL_WAVY_LINE_CHAR) {
                int posUnix = filename.indexOf(SYMBOL_LEFT_SLASH, 1);
                int posWin = filename.indexOf(SYMBOL_RIGHT_SLASH, 1);
                if (posUnix == INDEX_NOT_FOUND && posWin == INDEX_NOT_FOUND) {
                    return len + 1;
                }
                if (posUnix == INDEX_NOT_FOUND) {
                    posUnix = posWin;
                }
                if (posWin == INDEX_NOT_FOUND) {
                    posWin = posUnix;
                }
                return Math.min(posUnix, posWin) + 1;
            }
            final char ch1 = filename.charAt(1);
            if (ch1 == SYMBOL_COLON_CHAR) {
                ch0 = Character.toUpperCase(ch0);
                if (ch0 >= SYMBOL_UPPER_A && ch0 <= SYMBOL_UPPER_Z) {
                    if (len == NUMBER_2 || !isSeparator(filename.charAt(NUMBER_2))) {
                        return 2;
                    }
                    return 3;
                } else if (ch0 == SYMBOL_LEFT_SLASH_CHAR) {
                    return 1;
                }
                return INDEX_NOT_FOUND;

            } else if (isSeparator(ch0) && isSeparator(ch1)) {
                int posUnix = filename.indexOf(SYMBOL_LEFT_SLASH, 2);
                int posWin = filename.indexOf(SYMBOL_RIGHT_SLASH, 2);
                if (isNotFound(posUnix, posWin)) {
                    return INDEX_NOT_FOUND;
                }
                if (posUnix == INDEX_NOT_FOUND) {
                    posUnix = posWin;
                }
                if (posWin == INDEX_NOT_FOUND) {
                    posWin = posUnix;
                }
                return Math.min(posUnix, posWin) + 1;
            } else {
                if (isSeparator(ch0)) {
                    return 1;
                }
                return 0;
            }
        }
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
     * @param posWin  Windows分隔符位置
     * @return 如果未找到则返回true，否则返回false
     */
    private static boolean isNotFound(int posUnix, int posWin) {
        if (posUnix == 2) {
            return true;
        }
        if (posWin == posUnix) {
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
    }

    /**
     * 获取最后一个分隔符的位置
     *
     * @param filename 文件名
     * @return 最后一个分隔符的位置，如果未找到则返回INDEX_NOT_FOUND
     */
    public static int indexOfLastSeparator(final String filename) {
        if (filename == null) {
            return INDEX_NOT_FOUND;
        }
        final int lastUnixPos = filename.lastIndexOf(SYMBOL_LEFT_SLASH);
        final int lastWindowsPos = filename.lastIndexOf(SYMBOL_RIGHT_SLASH);
        if (lastUnixPos > lastWindowsPos) {
            return lastUnixPos;
        }
        return lastWindowsPos;
    }

    /**
     * 创建文件所在的父目录。
     *
     * <p>当文件为 null、无父目录或父目录已存在时直接返回，不进行任何操作；
     * 父目录不存在时调用 {@link File#mkdirs()} 递归创建。
     *
     * @param file 文件对象，允许为 null
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
                        "Null byte present in file/path name. There are no "
                                + "known legitimate use cases for such data, but several injection attacks may use it");
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
    }

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
        }
        int end = index + (includeSeparator ? 1 : 0);
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

        // Spring ClassPath
        String pathToUse = StringUtils.removePrefixIgnoreCase(path, CLASSPATH_URL_PREFIX);
        // file:
        pathToUse = StringUtils.removePrefixIgnoreCase(pathToUse, FILE_URL_PREFIX);

        // home
        if (pathToUse.startsWith(SYMBOL_WAVY_LINE)) {
            pathToUse = pathToUse.replace(SYMBOL_WAVY_LINE, Projects.getUserHomePath());
        }

        // 统一分隔符
        pathToUse = pathToUse.replaceAll("[/\\\\]+", SYMBOL_LEFT_SLASH).trim();
        // Windows \\
        if (path.startsWith(SYMBOL_RIGHT_SLASH + SYMBOL_RIGHT_SLASH)) {
            pathToUse = SYMBOL_RIGHT_SLASH + pathToUse;
        }

        String prefix = "";
        int prefixIndex = pathToUse.indexOf(SYMBOL_COLON);
        if (prefixIndex > -1) {
            // Windows
            prefix = pathToUse.substring(0, prefixIndex + 1);
            if (prefix.startsWith(SYMBOL_LEFT_SLASH)) {
                // /C:
                prefix = prefix.substring(1);
            }
            if (!prefix.contains(SYMBOL_LEFT_SLASH)) {
                pathToUse = pathToUse.substring(prefixIndex + 1);
            } else {
                // /, Windows path
                prefix = SYMBOL_EMPTY;
            }
        }
        if (pathToUse.startsWith(SYMBOL_LEFT_SLASH)) {
            prefix += SYMBOL_LEFT_SLASH;
            pathToUse = pathToUse.substring(1);
        }

        List<String> pathList = Splitter.on(SYMBOL_LEFT_SLASH_CHAR).splitToList(pathToUse);
        List<String> pathElements = new LinkedList<>();
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
                        // Normal path element found.
                        pathElements.add(0, element);
                    }
                }
            }
        }

        return prefix + Joiner.on(SYMBOL_LEFT_SLASH).join(pathElements);
    }

}


