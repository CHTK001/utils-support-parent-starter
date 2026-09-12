package com.chua.common.support.file.resource;

import com.chua.common.support.utils.FileUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipFile;

import static com.chua.common.support.constant.CommonConstant.JAR_URL_SEPARATOR;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_ASTERISK;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SLASH;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_RIGHT_SLASH;
import static com.chua.common.support.constant.NameConstant.CLASSPATH_URL_ALL_PREFIX;
import static com.chua.common.support.constant.NameConstant.FILE_URL_PREFIX;

/**
* {@code classpath*:} 协议资源查找器。
*
* <p>从类加载器中<strong>所有</strong>匹配位置加载资源（含父类加载器链与 {@code java.class.path}）。
* 非通配路径通过 {@link ClassLoader#getResources(String)} 枚举全部命中 URL；
* 通配路径则在每个根资源下递归扫描（文件目录走 NIO，JAR/WAR/ZIP 走 {@link ZipFile} 条目遍历）。</p>
*
* <p>当路径为空时，会通过 {@link URLClassLoader#getURLs()} 与 {@code java.class.path}
* 扫描所有 JAR 根，用于全量类路径枚举。</p>
*
* @author CH
* @since 1.0.0
 */
@Slf4j
public class ClassPathAnyResourceFinder extends AbstractResourceFinder {

    /**
    * {@code java.class.path} 系统属性名。
     */
    private static final String JAVA_CLASS_PATH = "java.class.path";

    /**
    * 路径分隔符（OS 相关）。
     */
    private static final String PATH_SEPARATOR = System.getProperty("path.separator");

    /**
    * 使用指定配置构造查找器。
    *
    * @param configuration 查找配置
     */
    public ClassPathAnyResourceFinder(ResourceConfiguration configuration) {
        super(configuration);
    }

    @Override
    /** 查找 */
    public Set<Resource> find(String name) {
        return analysisAnyResources(CLASSPATH_URL_ALL_PREFIX + name);
    }

    /**
    * 统一入口：按是否含通配符分发到模式匹配或全量枚举。
    *
    * @param name 含 {@code classpath*:} 前缀的完整路径
    * @return 匹配资源集合
     */
    private Set<Resource> analysisAnyResources(String name) {
        String sub = name.substring(CLASSPATH_URL_ALL_PREFIX.length());
        if (isPattern(sub)) {
            return findPathMatchingResources(name);
        }
        return findAllClassPathResources(name);
    }

    /**
    * 按通配符模式在所有类路径根下查找匹配资源。
    *
    * @param name 含 {@code classpath*:} 前缀的完整模式
    * @return 匹配资源集合
     */
    private Set<Resource> findPathMatchingResources(String name) {
        Set<Resource> result = new LinkedHashSet<>();
        String classPathRoot = findPathRootPath(name);
        String rootPath = classPathRoot.substring(CLASSPATH_URL_ALL_PREFIX.length()).trim();
        if (rootPath.startsWith(SYMBOL_LEFT_SLASH)) {
            rootPath = rootPath.substring(1);
        }
        String subPath = name.substring(classPathRoot.length());

        Set<Resource> resources = analysisAnyResources(CLASSPATH_URL_ALL_PREFIX + rootPath);
        analysisResources(resources, name, subPath, result);
        return result;
    }

    /**
    * 遍历根资源集合，按 JAR/目录分别匹配通配子路径。
    *
    * @param resources 根资源集合
    * @param name      完整模式（用于日志）
    * @param subPath   通配子路径
    * @param result    匹配结果收集集合
     */
    private void analysisResources(Set<Resource> resources, String name, String subPath, Set<Resource> result) {
        if (resources.isEmpty()) {
            return;
        }
        long startTime = System.currentTimeMillis();
        int size = resources.size();

        (configuration.isParallel() ? resources.stream().parallel() : resources.stream())
                .filter(Objects::nonNull)
                .forEach(resource -> {
                    URL url;
                    try {
                        url = resource.getUrl();
                    } catch (Exception ignored) {
                        return;
                    }
                    if (url == null) {
                        return;
                    }
                    try {
                        if (isJarUrl(url)) {
                            doFindPathMatchingJarResources(url, subPath, result);
                        } else {
                            doFindPathMatchingResources(url, subPath, result);
                        }
                    } catch (IOException ignored) {
                    }
                });

        int matchSize = result.size();
        long time = System.currentTimeMillis() - startTime;
        log.debug("classPath*模式扫描完成: {}, [{}/{}] 命中, 耗时={}ms",
                new Object[]{name, matchSize, size, time});
    }

    /**
    * 在 JAR 归档中匹配通配子路径。
    *
    * @param url     JAR URL
    * @param subPath 通配子路径
    * @param result  匹配结果收集集合
    * @throws IOException 打开 JAR 失败时抛出
     */
    private void doFindPathMatchingJarResources(URL url, String subPath, Set<Resource> result) throws IOException {
        ZipFile jarFile;
        try {
            URLConnection urlConnection = url.openConnection();
            if (urlConnection instanceof JarURLConnection jarConn) {
                urlConnection.setUseCaches(false);
                jarFile = jarConn.getJarFile();
            } else {
                return;
            }
        } catch (Throwable ignore) {
            return;
        }
        if (jarFile == null) {
            return;
        }

        String externalForm = url.toExternalForm();
        int index = externalForm.indexOf(JAR_URL_SEPARATOR);
        String jarExternalForm = externalForm.substring(0, index) + JAR_URL_SEPARATOR;
        String prefix = externalForm.substring(index + 2);
        int wildcardIndex = externalForm.indexOf(SYMBOL_ASTERISK);
        if (wildcardIndex > -1) {
            prefix = externalForm.substring(0, wildcardIndex);
        }
        final String newPrefix = prefix;
        final int length = newPrefix.length();

        try (ZipFile closeJarFile = jarFile) {
            closeJarFile.stream().forEach(jarEntry -> {
                String jarEntryName = jarEntry.getName();
                if (!jarEntryName.startsWith(newPrefix)) {
                    return;
                }
                String relativeName = jarEntryName.substring(length);
                String resourceUrl = jarExternalForm + jarEntryName;
                if (isExclude(relativeName)) {
                    return;
                }
                if (SYMBOL_ASTERISK.equals(relativeName) || matcher.match(subPath, relativeName)) {
                    Resource resource = Resource.create(resourceUrl);
                    consumer.accept(resource);
                    result.add(resource);
                }
            });
        }
    }

    /**
    * 在文件目录下匹配通配子路径。
    *
    * @param url       file URL
    * @param subPath   通配子路径
    * @param result    匹配结果收集集合
     */
    private void doFindPathMatchingResources(URL url, String subPath, Set<Resource> result) {
        File file = new File(url.getFile());
        if (file.isFile()) {
            return;
        }
        String root = file.getAbsolutePath();
        Path start = Paths.get(file.getAbsolutePath());
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                /** VisitFile */
                public FileVisitResult visitFile(Path visited, BasicFileAttributes attrs) {
                    if (visited.toFile().isFile()) {
                        String absolutePath = visited.toString();
                        int rootLen = root.length();
                        if (absolutePath.length() < rootLen + 1) {
                            return FileVisitResult.CONTINUE;
                        }
                        String embeddedSubPath = absolutePath.substring(rootLen + 1);
                        embeddedSubPath = embeddedSubPath.replace(SYMBOL_RIGHT_SLASH, SYMBOL_LEFT_SLASH);
                        if (isExclude(embeddedSubPath)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (SYMBOL_ASTERISK.equals(subPath) || matcher.match(subPath, embeddedSubPath)) {
                            Resource resource = Resource.create(visited.toFile());
                            consumer.accept(resource);
                            result.add(resource);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
    * 枚举所有类路径下匹配的 URL（非通配场景）。
    *
    * <p>当路径为空时，额外扫描所有 JAR 根以支持全量枚举。</p>
    *
    * @param name 含 {@code classpath*:} 前缀的完整路径
    * @return 资源集合
     */
    private Set<Resource> findAllClassPathResources(String name) {
        Set<Resource> result = new LinkedHashSet<>();
        Set<String> additionalCollections = new HashSet<>();
        String path = name.substring(CLASSPATH_URL_ALL_PREFIX.length()).trim();
        if (path.startsWith(SYMBOL_LEFT_SLASH)) {
            path = path.substring(1);
        }

        Enumeration<URL> enumeration = null;
        try {
            enumeration = classLoader.getResources(path);
        } catch (IOException e) {
            log.error("枚举类路径资源失败", e);
        }
        if (null != enumeration) {
            while (enumeration.hasMoreElements()) {
                URL url = enumeration.nextElement();
                if (ignoreJar(getUrlName(url.toExternalForm()))) {
                    continue;
                }
                additionalCollections.add(url.toExternalForm());
                result.add(Resource.create(url));
            }
        }

        if (path.isEmpty()) {
            addAllClassLoaderJarRoots(classLoader, result, additionalCollections);
        }
        return result;
    }

    /**
    * 递归扫描类加载器及其父链中所有 JAR 根。
    *
    * @param classLoader            类加载器
    * @param result                 资源结果集合
    * @param additionalCollections  已收集的 JAR 外部形式集合（去重用）
     */
    private void addAllClassLoaderJarRoots(ClassLoader classLoader, Set<Resource> result,
                                           Set<String> additionalCollections) {
        if (classLoader instanceof URLClassLoader urlClassLoader) {
            URL[] urls = urlClassLoader.getURLs();
            Arrays.stream(urls).forEach(url -> {
                if (ignoreJar(getUrlName(url.toExternalForm()))) {
                    return;
                }
                Resource resource;
                if (JAR_PROTOCOL.equals(url.getProtocol())) {
                    resource = Resource.create(url);
                } else {
                    resource = Resource.create(JAR_URL_PREFIX + url + JAR_URL_SEPARATOR);
                }
                String externalForm = resource.getUrl().toExternalForm();
                if (additionalCollections.contains(externalForm)) {
                    return;
                }
                additionalCollections.add(externalForm);
                result.add(resource);
            });
        }
        if (classLoader == ClassLoader.getSystemClassLoader()) {
            addClassPathManifestEntries(result, additionalCollections);
        }
        if (classLoader != null) {
            try {
                addAllClassLoaderJarRoots(classLoader.getParent(), result, additionalCollections);
            } catch (Exception ex) {
                if (log.isDebugEnabled()) {
                    log.debug("无法 introspect 父类加载器 JAR: {}", ex.getMessage());
                }
            }
        }
    }

    /**
    * 从 {@code java.class.path} 系统属性中扫描 JAR 根。
    *
    * @param result                资源结果集合
    * @param additionalCollections 已收集的 JAR 外部形式集合（去重用）
     */
    private void addClassPathManifestEntries(Set<Resource> result, Set<String> additionalCollections) {
        try {
            String javaClassPathProperty = System.getProperty(JAVA_CLASS_PATH);
            if (StringUtils.isEmpty(javaClassPathProperty)) {
                return;
            }
            for (String path : javaClassPathProperty.split(PATH_SEPARATOR)) {
                String filePath = new File(path).getAbsolutePath();
                if (ignoreJar(FileUtils.getName(filePath))) {
                    continue;
                }
                if (!isJarPath(filePath)) {
                    continue;
                }
                Resource resource = Resource.create(JAR_URL_PREFIX + FILE_URL_PREFIX + SYMBOL_LEFT_SLASH
                        + filePath.replace("\\", SYMBOL_LEFT_SLASH) + JAR_URL_SEPARATOR);
                String externalForm = resource.getUrl().toExternalForm();
                if (additionalCollections.contains(externalForm)) {
                    continue;
                }
                additionalCollections.add(externalForm);
                result.add(resource);
            }
        } catch (Exception ex) {
            if (log.isDebugEnabled()) {
                log.debug("解析 java.class.path 失败: {}", ex.getMessage());
            }
        }
    }

    /**
    * 判断指定名称的 JAR 是否应被忽略。
    *
    * @param name JAR 文件名
    * @return 应忽略返回 true
     */
    private boolean ignoreJar(String name) {
        if (excludes == null || excludes.isEmpty() || StringUtils.isEmpty(name)) {
            return false;
        }
        for (String exclude : excludes) {
            if (matcher.match(exclude, name)) {
                return true;
            }
        }
        return false;
    }
}
