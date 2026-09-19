package com.chua.filestorage.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JDK 默认的图片全局滤镜设置实现。
 *
 * <p>支持从系统属性或配置文件读取滤镜链配置。
 * 配置格式示例：
 * <pre>{@code
 * filestorage.filter.chain=resize,grayscale
 * filestorage.filter.exclude.paths=**\/avatar.*,**\/logo.*
 * filestorage.filter.exclude.extensions=svg
 * }</pre>svg
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jdk")
public class JdkFileStorageFilterSetting implements FileStorageFilterSetting {

    /**
     * 前缀
    */
    private static final String PREFIX = "filestorage.filter.";

    /**
     * 过滤器chain
    */
    private final List<FileStorageFilterSetting.ImageFilterConfig> filterChain;
    /**
     * Exclude路径模式
    */
    private final List<Pattern> excludePathPatterns;
    /**
     * Excludeextensions
    */
    private final Set<String> excludeExtensions;

    /**
     * 创建 jdk文件storage过滤器setting 实例
    */
    public JdkFileStorageFilterSetting() {
        this.filterChain = buildFilterChain();
        this.excludePathPatterns = buildExcludePathPatterns();
        this.excludeExtensions = buildExcludeExtensions();
    }

    @Override
    /**
     * Capabilities
    */
    public List<String> capabilities() {
        return List.of(
                "size", "resize",
                "grayscale", "gray",
                "blur", "gaussianBlur",
                "sharpen", "usm",
                "rotate", "flip",
                "watermark", "textWatermark", "imageWatermark",
                "bright", "contrast",
                "negative", "sepia",
                "pixel", "mosaic", "tile",
                "autoOrient"
        );
    }

    @Override
    /**
     * 获取过滤Chain
    */
    public List<FileStorageFilterSetting.ImageFilterConfig> getFilterChain() {
        return filterChain;
    }

    @Override
    /**
     * 是否Excluded
    */
    public boolean isExcluded(String path, String extension) {
        if (extension != null && excludeExtensions.contains(extension.toLowerCase())) {
            return true;
        }
        if (path != null) {
            for (Pattern p : excludePathPatterns) {
                if (p.matcher(path).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 构建过滤Chain
     *
     * @return 构建过滤器chain的结果
     */
    private List<FileStorageFilterSetting.ImageFilterConfig> buildFilterChain() {
        String chainStr = System.getProperty(PREFIX + "chain", "");
        if (chainStr.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(chainStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(id -> FileStorageFilterSetting.ImageFilterConfig.of(id))
                .toList();
    }

    /**
     * 构建exclude路径模式
     *
     * @return 构建exclude路径模式的结果
     */
    private List<Pattern> buildExcludePathPatterns() {
        String paths = System.getProperty(PREFIX + "exclude.paths", "");
        if (paths.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(paths.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(p -> Pattern.compile(antToRegex(p)))
                .toList();
    }

    /**
     * 构建exclude延伸
     *
     * @return 构建exclude延伸的结果
     */
    private Set<String> buildExcludeExtensions() {
        String exts = System.getProperty(PREFIX + "exclude.extensions", "svg");
        return java.util.Arrays.stream(exts.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * ant转为regex
     *
     * @param ant Ant
     * @return ant转为regex的结果
     */
    private String antToRegex(String ant) {
        return ant
                .replace("**", "<<<DOUBLESTAR>>>")
                .replace("*", "[^/]*")
                .replace("<<<DOUBLESTAR>>>", ".*");
    }
}
