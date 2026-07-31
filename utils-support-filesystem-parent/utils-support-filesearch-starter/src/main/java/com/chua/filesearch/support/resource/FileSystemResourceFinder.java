package com.chua.filesearch.support.resource;

import com.chua.common.support.file.resource.AbstractResourceFinder;
import com.chua.common.support.file.resource.Resource;
import com.chua.common.support.file.resource.ResourceConfiguration;
import com.chua.common.support.utils.StringUtils;
import com.chua.filesearch.support.bridge.RustFileSearchBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code filesystem:} / {@code filesystem*:} 协议资源查找器。
 *
 * <p>直接调用 Rust 原生库进行文件系统遍历与路径模式过滤。
 * 支持 Ant 风格通配符：{@code *}（单层任意）、{@code **}（多层任意）、{@code ?}（单字符）。</p>
 *
 * @author CH
 * @since 4.0.0
 */
public class FileSystemResourceFinder extends AbstractResourceFinder {

    private static final Logger log = LoggerFactory.getLogger(FileSystemResourceFinder.class);

    /**
     * 文件系统协议前缀 {@value}。
     */
    private static final String FILESYSTEM_URL_PREFIX = "filesystem:";
    /**
     * 文件系统全量协议前缀 {@value}。
     */
    private static final String FILESYSTEM_URL_ALL_PREFIX = "filesystem*:";

    /**
     * 使用指定配置构造查找器。
     *
     * @param configuration 查找配置
     */
    public FileSystemResourceFinder(ResourceConfiguration configuration) {
        super(configuration);
    }

    @Override
    public Set<Resource> find(String name) {
        String fullName = FILESYSTEM_URL_PREFIX + name;
        if (name.startsWith(FILESYSTEM_URL_ALL_PREFIX.replace(":", ""))) {
            fullName = FILESYSTEM_URL_ALL_PREFIX + name.substring(FILESYSTEM_URL_ALL_PREFIX.length() - 1);
        }
        return findPathMatchingResources(fullName);
    }

    /**
     * 按路径模式在文件系统中查找匹配资源。
     *
     * @param fullName 含 {@code filesystem:} 或 {@code filesystem*:} 前缀的完整模式
     * @return 匹配资源集合
     */
    private Set<Resource> findPathMatchingResources(String fullName) {
        Set<Resource> result = ConcurrentHashMap.newKeySet();

        String protocol = fullName.contains(FILESYSTEM_URL_ALL_PREFIX) ? FILESYSTEM_URL_ALL_PREFIX : FILESYSTEM_URL_PREFIX;
        String pattern = fullName.substring(protocol.length());

        int slashIndex = pattern.indexOf('/');
        if (slashIndex == -1) {
            slashIndex = pattern.indexOf('\\');
        }

        String rootDir;
        String pathPattern;
        if (slashIndex > -1) {
            rootDir = pattern.substring(0, slashIndex);
            pathPattern = pattern.substring(slashIndex + 1);
        } else {
            rootDir = pattern;
            pathPattern = "*";
        }

        if (StringUtils.isEmpty(rootDir)) {
            rootDir = System.getProperty("user.dir");
        }

        if (!new File(rootDir).exists()) {
            return result;
        }

        int maxResults = 1000;
        Collection<Resource> resources = configuration.isParallel()
                ? ConcurrentHashMap.newKeySet()
                : new HashSet<>();

    RustFileSearchBridge.searchByPath(rootDir, pathPattern, maxResults,
            data -> {
                Resource resource = Resource.create(new File(data.path()));
                if (consumer != null) {
                    consumer.accept(resource);
                }
                resources.add(resource);
            }
    );

        result.addAll(resources);
        return result;
    }
}
