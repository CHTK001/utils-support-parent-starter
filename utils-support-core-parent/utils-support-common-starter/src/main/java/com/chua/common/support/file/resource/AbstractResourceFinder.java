package com.chua.common.support.file.resource;

import com.chua.common.support.matcher.PathMatcher;
import com.chua.common.support.utils.FileUtils;
import com.chua.common.support.utils.StringUtils;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_ASTERISK;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_ASTERISK_ANY;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SLASH;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SLASH_CHAR;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_QUESTION;
import static com.chua.common.support.constant.CommonConstant.JAR_URL_SEPARATOR;

/**
 * 资源查找器抽象基类，封装各协议查找器共用的路径匹配与排除规则逻辑。
 *
 * <p>子类（如 {@link ClassPathResourceFinder}、{@link ClassPathAnyResourceFinder}）只需关注
 * 协议特定的资源枚举，复用本类提供的：</p>
 * <ul>
 *   <li>路径模式判定（{@link #isPattern(String)}）</li>
 *   <li>排除规则匹配（{@link #isExclude(String)}）</li>
 *   <li>模式根路径提取（{@link #findPathRootPath(String)}）</li>
 *   <li>Ant 通配符匹配（{@link #isMatch(String, String)}）</li>
 *   <li>JAR URL 识别（{@link #isJarUrl(java.net.URL)}）</li>
 * </ul>
 *
 * @since 1.0.0
 */
public abstract class AbstractResourceFinder implements ResourceFinder {

    /**
     * JAR URL 协议前缀 {@value}。
     */
    protected static final String JAR_URL_PREFIX = "jar:";

    /**
     * JAR 协议名称 {@value}。
     */
    protected static final String JAR_PROTOCOL = "jar";

    /**
     * 路径匹配器。
     */
    protected final PathMatcher matcher;

    /**
     * 查找配置。
     */
    protected final ResourceConfiguration configuration;

    /**
     * 排除规则集合。
     */
    protected final Set<String> excludes;

    /**
     * 类加载器。
     */
    protected final ClassLoader classLoader;

    /**
     * 资源命中回调。
     */
    protected final Consumer<Resource> consumer;

    /**
     * 使用指定配置构造查找器，并从配置中提取匹配器、排除规则、类加载器与回调。
     *
     * @param configuration 查找配置
     */
    public AbstractResourceFinder(ResourceConfiguration configuration) {
        this.configuration = configuration;
        this.matcher = configuration.getPathMatcher();
        this.excludes = configuration.getExcludes();
        this.classLoader = configuration.getClassLoader();
        this.consumer = configuration.getConsumer();
    }

    /**
     * 判断路径是否为 Ant 模式（包含通配符）。
     *
     * @param path 待判定路径
     * @return 如果是模式返回 true
     */
    protected boolean isPattern(String path) {
        return matcher.isPattern(path);
    }

    /**
     * 判断资源路径是否命中排除规则。
     *
     * @param absolutePath 资源路径
     * @return 命中排除规则返回 true
     */
    protected boolean isExclude(String absolutePath) {
        if (excludes == null || excludes.isEmpty()) {
            return false;
        }
        for (String exclude : excludes) {
            if (isMatch(absolutePath, exclude)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断名称是否匹配指定模式。
     *
     * <p>当模式为 {@code *} 或 {@code **} 时直接通过；当模式含通配符时使用 {@link PathMatcher#match}；
     * 否则执行包含判定。</p>
     *
     * @param name      待匹配名称
     * @param matchPath 匹配模式
     * @return 匹配返回 true
     */
    protected boolean isMatch(String name, String matchPath) {
        if (SYMBOL_ASTERISK.equals(matchPath) || SYMBOL_ASTERISK_ANY.equals(matchPath)) {
            return true;
        }
        if (matchPath.contains(SYMBOL_ASTERISK) || matchPath.contains(SYMBOL_QUESTION)) {
            return matcher.match(matchPath, name);
        }
        return name.contains(matchPath);
    }

    /**
     * 从包含通配符的路径中提取确定性的根路径部分（首个通配符之前的路径）。
     *
     * @param name 完整路径（含协议前缀）
     * @return 根路径
     */
    protected String findPathRootPath(String name) {
        int prefixEnd = name.indexOf(':') + 1;
        int rootDirEnd = name.length();
        while (rootDirEnd > prefixEnd && isPattern(name.substring(prefixEnd, rootDirEnd))) {
            rootDirEnd = name.lastIndexOf(SYMBOL_LEFT_SLASH_CHAR, rootDirEnd - 2) + 1;
        }
        if (rootDirEnd == 0) {
            rootDirEnd = prefixEnd;
        }
        return name.substring(0, rootDirEnd);
    }

    /**
     * 获取路径中首个通配符之前的确定部分。
     *
     * @param path 完整路径
     * @return 确定部分路径
     */
    public String getFullPath(String path) {
        path = path.replace("\\", SYMBOL_LEFT_SLASH);
        List<String> sep = new LinkedList<>();
        for (String item : path.split(SYMBOL_LEFT_SLASH)) {
            if (item.contains(SYMBOL_ASTERISK) || item.contains(SYMBOL_QUESTION)) {
                break;
            }
            sep.add(item);
        }
        return String.join(SYMBOL_LEFT_SLASH, sep);
    }

    /**
     * 获取路径中含通配符的匹配部分。
     *
     * @param path 完整路径
     * @return 匹配部分路径
     */
    public String getMatchPath(String path) {
        path = path.replace("\\", SYMBOL_LEFT_SLASH);
        List<String> sep = new LinkedList<>();
        for (String item : path.split(SYMBOL_LEFT_SLASH)) {
            if (item.contains(SYMBOL_ASTERISK) || item.contains(SYMBOL_QUESTION)) {
                sep.add(item);
            }
        }
        return String.join(SYMBOL_LEFT_SLASH, sep);
    }

    /**
     * 从 URL 外部形式中提取所属 JAR 文件名。
     *
     * @param urlForm URL 外部形式
     * @return JAR 文件名，非 JAR URL 时返回原字符串
     */
    protected String getUrlName(String urlForm) {
        int index = urlForm.indexOf(JAR_URL_SEPARATOR);
        if (index == -1) {
            return urlForm;
        }
        String substring = urlForm.substring(0, index);
        return FileUtils.getName(substring);
    }

    /**
     * 判断 URL 是否指向 JAR/WAR/ZIP 归档。
     *
     * <p>判定依据：协议为 {@code jar}，或路径以 {@code .jar}/ {@code .war}/ {@code .zip}/ {@code .ear} 结尾。</p>
     *
     * @param url 待判定 URL
     * @return 是 JAR 归档返回 true
     */
    protected boolean isJarUrl(java.net.URL url) {
        if (url == null) {
            return false;
        }
        if (JAR_PROTOCOL.equals(url.getProtocol())) {
            return true;
        }
        return isJarPath(url.getPath());
    }

    /**
     * 判断路径字符串是否指向 JAR/WAR/ZIP 归档。
     *
     * @param path 待判定路径
     * @return 是 JAR 归档返回 true
     */
    protected boolean isJarPath(String path) {
        if (StringUtils.isEmpty(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".war")
                || lower.endsWith(".zip") || lower.endsWith(".ear");
    }
}
