package com.chua.common.support.spi.resolver;

import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SLASH;
import static com.chua.common.support.spi.definition.ServiceDefinitionUtils.buildDefinition;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * SPI 自定义解析器
 * <p>
 *     从 {@code META-INF/extensions} 目录加载 SPI 配置，将配置行解析为 {@link ServiceDefinition} 对象
 * <p>
 *     配置文件格式为 {@code META-INF/extensions/<接口全限定名>}
 * <ul>
 *     <li>{@code 实现类全限定名}</li>
 *     <li>{@code 别名=实现类全限定名}</li>
 * </ul>
 *     自动处理 UTF-8 BOM、不可见字符等边界情况
 *
 * @author CH
 * @since 2024-01-03
 */
public class CustomServiceResolver implements ServiceResolver {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(CustomServiceResolver.class);

    /**
     * SPI 配置文件在 classpath 中的路径
     */
    private static final String PATH = "META-INF/extensions";

    /**
     * UTF-8 BOM 字符（Zero Width No-Break Space U+FEFF）
     */
    private static final char BOM = '\uFEFF';

    /**
     * 从文件中加载 SPI 定义
     *
     * @param path SPI 配置文件路径（以 {@code /} 结尾）
     * @param type 服务接口类型
     * @param classLoader 类加载器
     * @return SPI 定义列表
     */
    protected synchronized List<ServiceDefinition> loadFromFile(String path, Class<?> type, ClassLoader classLoader) {
        if (log.isTraceEnabled()) {
            log.trace("从路径 {} 加载类型 {} 的 SPI 定义", type.getTypeName(), path);
        }
        if (!path.endsWith(SYMBOL_LEFT_SLASH)) {
            path += SYMBOL_LEFT_SLASH;
        }
        String fullFileName = path + type.getTypeName();
        try {
            return loadFromClassLoader(fullFileName, type, classLoader);
        } catch (Throwable t) {
            if (log.isTraceEnabled()) {
                log.trace("从路径 {} 加载类型 {} 的 SPI 定义失败", type.getTypeName(), fullFileName, t);
            }
        }
        return null;
    }

    /**
     * 通过类加载器加载 SPI 配置文件
     *
     * @param fullFileName SPI 配置文件全路径
     * @param type 服务接口类型
     * @param classLoader 类加载器
     * @return SPI 扩展类列表
     * @throws Throwable 加载异常
     */
    private List<ServiceDefinition> loadFromClassLoader(final String fullFileName, Class<?> type, ClassLoader classLoader) throws Throwable {
        Enumeration<URL> urls = classLoader.getResources(fullFileName);
        List<ServiceDefinition> allExtensionClass = new ArrayList<>();
        if (urls != null) {
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (log.isTraceEnabled()) {
                    log.trace("类加载器 {} 找到资源 {}，正在加载类型 {}", classLoader, url, type.getTypeName());
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        if (log.isTraceEnabled()) {
                            log.trace("读取到配置行 {}：{}", type.getTypeName(), line);
                        }
                        allExtensionClass.addAll(Optional.ofNullable(readLine(line, url, type, classLoader)).orElse(Collections.emptyList()));
                    }
                } catch (Throwable t) {
                    if (log.isTraceEnabled()) {
                        log.trace("类加载器 {} 处理资源 {} 时发生异常：{}", classLoader, url, type.getTypeName(), t);
                    }
                }
                if (log.isTraceEnabled()) {
                    log.trace("完成类型 {} 的加载", type.getTypeName());
                }
            }
        }
        return allExtensionClass;
    }

    /**
     * 解析单行 SPI 配置
     *
     * @param line 配置行内容
     * @param url 配置文件 URL
     * @param type 服务接口类型
     * @param classLoader 类加载器
     * @return 服务定义列表
     */
    protected List<ServiceDefinition> readLine(final String line, URL url, Class<?> type, ClassLoader classLoader) {
        String[] aliasAndClassName = parseSpiNameAndClassName(line);
        int size = 2;
        if (aliasAndClassName == null || aliasAndClassName.length != size) {
            return null;
        }
        String alias = aliasAndClassName[0];
        String className = aliasAndClassName[1];
        Class<?> tmp;
        try {
            tmp = Class.forName(className, false, classLoader);
        } catch (Throwable e) {
            if (log.isTraceEnabled()) {
                log.trace("类 {} 加载失败：{}", className, type.getTypeName());
            }
            return null;
        }
        return buildDefinition(type, CustomServiceResolver.class, null, tmp, StringUtils.defaultString(alias, tmp.getSimpleName().replace(type.getSimpleName(), "")), url);
    }

    /**
     * 解析 SPI 配置行中的名称和类名
     *
     * @param line 配置行
     * @return 包含别名和类名的数组，解析失败返回 null
     */
    protected String[] parseSpiNameAndClassName(String line) {
        if (null == line || "".equals(line)) {
            return null;
        }

        line = sanitizeSpiLine(line);
        if (StringUtils.isBlank(line)) {
            return null;
        }
        String name = "";
        String className = line;
        int i = line.indexOf('=');
        if (i > 0) {
            name = line.substring(0, i).trim();
            className = line.substring(i + 1).trim();
        }
        if (className.length() == 0) {
            return null;
        }

        return new String[]{name, className};
    }

    /**
     * 清理 SPI 配置行中的 BOM 和不可见字符
     *
     * @param line 原始配置行
     * @return 清理后的配置行
     */
    private static String sanitizeSpiLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }

        var start = 0;
        while (start < line.length() && isInvisiblePrefixChar(line.charAt(start))) {
            start++;
        }

        var result = start > 0 ? line.substring(start) : line;
        return result.trim();
    }

    /**
     * 判断字符是否为不可见前缀字符
     *
     * @param ch 字符
     * @return true 表示为不可见前缀字符
     */
    private static boolean isInvisiblePrefixChar(char ch) {
        return switch (ch) {
            case BOM, '\u200B', '\u200C', '\u200D', '\u00A0' -> true;
            default -> false;
        };
    }

    @Override
    public List<ServiceDefinition> resolve(Class<?> type, ClassLoader classLoader) {
        if (ClassUtils.isJavaType(type)) {
            return Collections.emptyList();
        }
        return loadFromFile(PATH, type, classLoader);
    }
}
