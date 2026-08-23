package com.chua.common.support.file.resource;

import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static com.chua.common.support.constant.CommonConstant.JAR_URL_SEPARATOR;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_ASTERISK;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SLASH;
import static com.chua.common.support.constant.NameConstant.CLASSPATH_URL_PREFIX;

/**
 * {@code classpath:} 协议资源查找器。
 *
 * <p>仅从类加载器中<strong>首个</strong>匹配位置加载资源。当路径含 Ant 通配符时，
 * 在首个匹配的根目录下递归扫描（文件目录使用 NIO {@link Files#walkFileTree}，
 * JAR/WAR/ZIP 归档使用 {@link ZipFile} 遍历条目）。</p>
 *
 * <p>支持 Ant 风格通配符：{@code *}（单层任意）、{@code **}（多层任意）、{@code ?}（单字符）。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@Slf4j
public class ClassPathResourceFinder extends AbstractResourceFinder {


    /**
     * 文件树遍历默认最大深度。
     */
    private static final int DEFAULT_MAX_DEPTH = 128;

    /**
     * 使用指定配置构造查找器。
     *
     * @param configuration 查找配置
     */
    public ClassPathResourceFinder(ResourceConfiguration configuration) {
        super(configuration);
    }

    @Override
    /** 查找 */
    public Set<Resource> find(String name) {
        String fullName = CLASSPATH_URL_PREFIX + name;
        if (isPattern(name)) {
            return findPathMatchingResources(fullName);
        }
        return findAllClassPathResources(fullName);
    }

    /**
     * 按通配符模式在首个类路径根下查找匹配资源。
     *
     * @param name 含 {@code classpath:} 前缀的完整模式
     * @return 匹配资源集合
     */
    private Set<Resource> findPathMatchingResources(String name) {
        Set<Resource> result = ConcurrentHashMap.newKeySet();

        // 1. 提取确定性的根路径与通配子路径
        String classPathRoot = findPathRootPath(name);
        String rootPath = classPathRoot.substring(CLASSPATH_URL_PREFIX.length()).trim();
        if (rootPath.startsWith(SYMBOL_LEFT_SLASH)) {
            rootPath = rootPath.substring(1);
        }
        String subPath = name.substring(classPathRoot.length());

        // 2. 定位根路径对应的根资源
        Set<Resource> rootResources = findAllClassPathResources(CLASSPATH_URL_PREFIX + rootPath);

        // 3. 在根资源下递归匹配
        analysisResources(rootResources, name, subPath, result);
        return result;
    }

    /**
     * 遍历根资源集合，按 JAR/目录分别匹配通配子路径。
     *
     * @param resources    根资源集合
     * @param name         完整模式（用于日志）
     * @param subPath      通配子路径
     * @param result       匹配结果收集集合
     */
    private void analysisResources(Set<Resource> resources, String name, String subPath, Set<Resource> result) {
        long startTime = System.currentTimeMillis();
        AtomicLong scannedCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);

        Stream<Resource> stream = configuration.isParallel()
                ? resources.parallelStream()
                : resources.stream();

        stream.forEach(resource -> {
            if (resource == null) {
                return;
            }
            URL url;
            try {
                url = resource.getUrl();
            } catch (Exception e) {
                errorCount.incrementAndGet();
                return;
            }
            if (url == null) {
                return;
            }
            try {
                if (isJarUrl(url)) {
                    doFindPathMatchingJarResources(url, subPath, result, scannedCount);
                } else {
                    doFindPathMatchingResources(subPath, new File(url.getFile()), result, scannedCount);
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("资源匹配失败: url={}, 原因: {}", url, e.getMessage());
                }
                errorCount.incrementAndGet();
            }
        });

        long elapsed = System.currentTimeMillis() - startTime;
        log.debug("classPath模式扫描完成: pattern={}, 命中={}, 扫描={}, 错误={}, 耗时={}ms",
                new Object[]{name, result.size(), scannedCount.get(), errorCount.get(), elapsed});
    }

    /**
     * 在 JAR 归档中匹配通配子路径。
     *
     * @param url          JAR URL
     * @param subPath      通配子路径
     * @param result       匹配结果收集集合
     * @param scannedCount 已扫描计数器
     */
    private void doFindPathMatchingJarResources(URL url, String subPath, Set<Resource> result,
                                                AtomicLong scannedCount) {
        ZipFile jarFile;
        try {
            URLConnection urlConnection = url.openConnection();
            if (urlConnection instanceof JarURLConnection jarConn) {
                jarConn.setUseCaches(false);
                jarFile = jarConn.getJarFile();
            } else {
                return;
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("打开 JAR 失败: url={}, 原因: {}", url, e.getMessage());
            }
            return;
        }

        String externalForm = url.toExternalForm();
        int separatorIndex = externalForm.indexOf(JAR_URL_SEPARATOR);
        if (separatorIndex == -1) {
            return;
        }
        String jarExternalForm = externalForm.substring(0, separatorIndex) + JAR_URL_SEPARATOR;
        String prefix = externalForm.substring(separatorIndex + 2);

        int wildcardIndex = externalForm.indexOf(SYMBOL_ASTERISK);
        if (wildcardIndex > -1 && wildcardIndex > separatorIndex) {
            prefix = externalForm.substring(separatorIndex + 2, wildcardIndex);
        }

        final String finalPrefix = prefix;
        final int prefixLength = finalPrefix.length();

        try (ZipFile closeJarFile = jarFile) {
            Stream<? extends java.util.zip.ZipEntry> entryStream = configuration.isParallel()
                    ? closeJarFile.stream().parallel()
                    : closeJarFile.stream();
            entryStream.forEach(jarEntry -> {
                scannedCount.incrementAndGet();
                String entryName = jarEntry.getName();
                if (!entryName.startsWith(finalPrefix)) {
                    return;
                }
                String relativeName = entryName.substring(prefixLength);
                String resourceUrl = jarExternalForm + entryName;
                if (isExclude(relativeName)) {
                    return;
                }
                if (SYMBOL_ASTERISK.equals(relativeName) || matcher.match(subPath, relativeName)) {
                    Resource resource = Resource.create(resourceUrl);
                    consumer.accept(resource);
                    result.add(resource);
                }
            });
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("JAR 条目扫描失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 在文件目录下使用 NIO 文件树遍历匹配通配子路径。
     *
     * @param matcherPath  通配子路径
     * @param rootDir      根目录
     * @param result       匹配结果收集集合
     * @param scannedCount 已扫描计数器
     */
    private void doFindPathMatchingResources(String matcherPath, File rootDir, Set<Resource> result,
                                             AtomicLong scannedCount) {
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            return;
        }
        Path startPath = rootDir.toPath();
        String fullPattern = StringUtils.replace(rootDir.getAbsolutePath() + "/" + matcherPath,
                File.separator, "/");
        int maxDepth = calculateMaxDepth(matcherPath);
        try {
            Files.walkFileTree(startPath, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new ClassPathFileVisitor(fullPattern, result, scannedCount));
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("文件树遍历失败: {}, 原因: {}", rootDir, e.getMessage());
            }
        }
    }

    /**
     * 根据通配模式计算最大遍历深度。
     *
     * @param matchPath 通配模式
     * @return 最大深度
     */
    private int calculateMaxDepth(String matchPath) {
        if (StringUtils.isEmpty(matchPath)) {
            return 1;
        }
        if (matchPath.contains("**")) {
            return DEFAULT_MAX_DEPTH;
        }
        return (int) matchPath.chars().filter(c -> c == '/').count() + 1;
    }

    /**
     * 定位单个类路径资源（无通配符场景）。
     *
     * @param name 含 {@code classpath:} 前缀的完整路径
     * @return 单元素集合，未找到时为空集合
     */
    private Set<Resource> findAllClassPathResources(String name) {
        String path = name.substring(CLASSPATH_URL_PREFIX.length()).trim();
        URL resource = classLoader.getResource(path);
        if (resource == null) {
            if (log.isDebugEnabled()) {
                log.debug("类路径资源未找到: {}", path);
            }
            return Collections.emptySet();
        }
        return Collections.singleton(Resource.create(resource));
    }

    /**
     * NIO 文件树访问器，按完整模式匹配文件并收集命中结果。
     */
    private class ClassPathFileVisitor extends SimpleFileVisitor<Path> {

        /** 完整匹配模式 */
        private final String fullPattern;
        /**
         * 结果
         */
        private final Set<Resource> result;
        /** 已扫描计数 */
        private final AtomicLong scannedCount;

        ClassPathFileVisitor(String fullPattern, Set<Resource> result, AtomicLong scannedCount) {
            this.fullPattern = fullPattern;
            this.result = result;
            this.scannedCount = scannedCount;
        }

        @Override
        /** PreVisitDirectory */
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            String dirPath = StringUtils.replace(dir.toString(), File.separator, "/");
            if (!matcher.matchStart(fullPattern, dirPath + "/")) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (isExclude(dirPath)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        /** VisitFile */
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            scannedCount.incrementAndGet();
            String filePath = StringUtils.replace(file.toString(), File.separator, "/");
            if (isExclude(filePath)) {
                return FileVisitResult.CONTINUE;
            }
            if (matcher.match(fullPattern, filePath)) {
                Resource resource = Resource.create(file.toFile());
                consumer.accept(resource);
                result.add(resource);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        /** VisitFileFailed */
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }

        @Override
        /** PostVisitDirectory */
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }
}
