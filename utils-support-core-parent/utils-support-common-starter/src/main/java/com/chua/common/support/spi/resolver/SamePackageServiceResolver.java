package com.chua.common.support.spi.resolver;

import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.chua.common.support.spi.definition.ServiceDefinitionUtils.buildDefinition;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 同包 SPI 服务解析器工具类。
 * <p>
 * 该实现会扫描指定服务接口所在包及其子包下的所有类，查找该接口的实现类并构造对应的服务定义。
 * <p>
 * 扫描策略如下：
 * <ol>
 *   <li>获取服务接口所在包名；</li>
 *   <li>通过类加载器定位包路径下的 {@code .class} 文件；</li>
 *   <li>过滤出实现该接口且非抽象的具体类；</li>
 *   <li>排除内部类，避免误扫描。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class SamePackageServiceResolver implements ServiceResolver {


    /**
     * 类缓存，用于加速重复加载
     */
    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>(512);
    
    /**
     * 类锁缓存，用于并发安全加载
     */
    private static final Map<String, Object> CLASS_LOCKS = new ConcurrentHashMap<>(512);

    /**
     * JAR URL 锁缓存：同一 URL 的打开-扫描-关闭必须串行，
      * 避免一个线程 关闭 后其他线程访问已关闭的 jar文件 报 压缩 文件 关闭。
     */
    private static final Map<String, Object> JAR_URL_LOCKS = new ConcurrentHashMap<>(64);

    /**
     * 批量加载大小
     */
    private static final int BATCH_SIZE = 100;

    /**
     * 解析指定服务接口在同包路径下的所有实现类，并转换为服务定义列表。
     *
     * @param service 服务接口类型
     * @param classLoader 用于扫描和加载实现类的类加载器
     * @return 解析得到的服务定义列表
     */
    @Override
    public List<ServiceDefinition> resolve(Class<?> service, ClassLoader classLoader) {
        if (log.isTraceEnabled()) {
            log.trace("[SPI] 开始解析接口：{}", service.getName());
        }

        Package aPackage = service.getPackage();
        String packageName = null != aPackage ? aPackage.getName() : null;
        if (null == packageName) {
            packageName = extractPackageName(service.getTypeName());
        }

        if (log.isTraceEnabled()) {
            log.trace("[SPI] 包名：{}", packageName);
        }

        List<ServiceDefinition> result = new ArrayList<>();
        List<Class<?>> subTypeByPackage = findSubTypeByPackage(packageName, service, classLoader);

        int foundCount = 0;
        int validCount = 0;

        for (Class<?> aClass : subTypeByPackage) {
            foundCount++;
            if (!service.isAssignableFrom(aClass) || aClass.isInterface() || Modifier.isAbstract(aClass.getModifiers())) {
                continue;
            }

            validCount++;
            List<ServiceDefinition> serviceDefinitions = buildDefinition(service, aClass, SamePackageServiceResolver.class);
            if (!serviceDefinitions.isEmpty()) {
                result.addAll(serviceDefinitions);
                continue;
            }

            String simpleName = aClass.getSimpleName().replace(service.getSimpleName(), "");
            result.addAll(buildDefinition(service, SamePackageServiceResolver.class, null, aClass, simpleName, null));
        }

        if (log.isTraceEnabled()) {
            log.trace("[SPI] 接口：{}，找到：{}，有效：{}，最终：{}", 
                    service.getName(), foundCount, validCount, result.size());
        }

        return result;
    }

    /**
     * 从类型全名中提取包名。
     *
     * @param typeName 类型全名
     * @return 包名，若不存在包名则返回空字符串
     */
    private String extractPackageName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot > 0 ? typeName.substring(0, lastDot) : "";
    }

    /**
     * 查找指定包及其子包下的实现类列表。
     * <p>
     * 通过扫描包路径下的 {@code .class} 文件，发现符合条件的服务实现类。
     *
     * @param packageName 包名
     * @param service 服务接口
     * @param classLoader 类加载器
     * @return 子类型列表
     */
    public List<Class<?>> findSubTypeByPackage(String packageName, Class<?> service, ClassLoader classLoader) {
        if (packageName == null || packageName.isEmpty()) {
            if (log.isTraceEnabled()) {
                log.trace("[SPI] 包名为空，跳过扫描");
            }
            return Collections.emptyList();
        }

        String packageDirName = packageName.replace('.', '/');

        Enumeration<URL> resources = null;
        try {
            resources = classLoader.getResources(packageDirName);
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("[SPI] 获取包资源失败：{}", packageName, e);
            }
            return Collections.emptyList();
        }

        if (resources == null || !resources.hasMoreElements()) {
            if (log.isTraceEnabled()) {
                log.trace("[SPI] 未找到包资源：{}", packageName);
            }
            return Collections.emptyList();
        }

        Collection<Class<?>> result = new ConcurrentLinkedQueue<>();

        List<URL> urlList = new ArrayList<>();
        while (resources.hasMoreElements()) {
            urlList.add(resources.nextElement());
        }

        if (log.isTraceEnabled()) {
            log.trace("[SPI] 找到 {} 个资源路径，包名：{}", urlList.size(), packageName);
        }

        urlList.parallelStream().forEachOrdered(url -> {
            doAnalysisUrl(result, url, packageName, packageDirName, service, classLoader);
        });

        return new ArrayList<>(result);
    }

    /**
     * 根据资源 URL 的协议类型，分发到文件系统或 JAR 包扫描逻辑。
     *
     * @param result 结果集合
     * @param url 资源 URL
     * @param packageName 包名
     * @param packageDirName 包目录名
     * @param service 服务接口
     * @param classLoader 类加载器
     */
    private void doAnalysisUrl(Collection<Class<?>> result,
                               URL url,
                               String packageName,
                               String packageDirName,
                               Class<?> service,
                               ClassLoader classLoader) {
        String protocol = url.getProtocol();
        if ("file".equals(protocol)) {
            doAnalysisFileUrl(result, url, packageName, packageDirName, service, classLoader);
        } else if ("jar".equals(protocol)) {
            doAnalysisJarUrl(result, url, packageDirName, service, classLoader);
        } else {
            if (log.isTraceEnabled()) {
                log.trace("[SPI] 不支持的协议：{}, URL: {}", protocol, url);
            }
        }
    }

    /**
     * 扫描 JAR 包中的 {@code .class} 文件，并加载符合条件的实现类。
     *
     * @param result 结果集合
     * @param url 资源 URL
     * @param packageDirName 包目录名
     * @param service 服务接口
     * @param classLoader 类加载器
     */
    private void doAnalysisJarUrl(Collection<Class<?>> result,
                                  URL url,
                                  String packageDirName,
                                  Class<?> service,
                                  ClassLoader classLoader) {
 // 同一 URL 串行打开-扫描-关闭,避免并发 关闭 导致 压缩 文件 关闭
        Object lock = JAR_URL_LOCKS.computeIfAbsent(url.toString(), k -> new Object());
        synchronized (lock) {
            doAnalysisJarUrlInner(result, url, packageDirName, service, classLoader);
        }
    }

    /**
      * 执行analysisjarurl内部
     * @param result 结果
     * @param url url
     * @param packageDirName 包dir名称
     * @param service 服务
     * @param classLoader 类加载
     */
    private void doAnalysisJarUrlInner(Collection<Class<?>> result,
                                       URL url,
                                       String packageDirName,
                                       Class<?> service,
                                       ClassLoader classLoader) {
        JarFile jarFile = null;
        try {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            jarFile = connection.getJarFile();
        } catch (IOException e) {
            if (log.isTraceEnabled()) {
                log.trace("[SPI] 打开 JAR 失败：{}", url, e);
            }
            return;
        }

        try {
            List<String> classNames = jarFile.stream().parallel()
                    .filter(jarEntry -> {
                        String entryName = jarEntry.getName();
                        return entryName.endsWith(".class") 
                                && entryName.startsWith(packageDirName)
                                && entryName.indexOf('$') == -1;
                    })
                    .map(jarEntry -> {
                        String entryName = jarEntry.getName();
                        int len = entryName.length();
                        return entryName.substring(0, len - 6).replace('/', '.');
                    })
                    .collect(Collectors.toList());

            loadClassesInBatch(classNames, service, classLoader, result);
        } finally {
            try {
                jarFile.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * 扫描文件系统目录中的 {@code .class} 文件，使用 {@link Files#walk} 遍历以兼容较新的 Java 版本。
     *
     * @param result 结果集合
     * @param url 资源 URL
     * @param packageName 包名
     * @param packageDirName 包目录名
     * @param service 服务接口
     * @param classLoader 类加载器
     */
    private void doAnalysisFileUrl(Collection<Class<?>> result,
                                  URL url,
                                  String packageName,
                                  String packageDirName,
                                  Class<?> service,
                                  ClassLoader classLoader) {
        try {
            Path packagePath = Paths.get(url.toURI());
            if (!Files.exists(packagePath) || !Files.isDirectory(packagePath)) {
                if (log.isTraceEnabled()) {
                    log.trace("[SPI] 包路径不存在或不是目录：{}", packagePath);
                }
                return;
            }

            try (Stream<Path> paths = Files.walk(packagePath)) {
                List<String> classNames = paths.parallel()
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            return fileName.endsWith(".class") && fileName.indexOf('$') == -1;
                        })
                        .map(path -> {
                            try {
                                Path relativePath = packagePath.relativize(path);
                                String relativeStr = relativePath.toString()
                                        .replace(File.separatorChar, '.')
                                        .replace('/', '.');
                                
                                int len = relativeStr.length();
                                if (len > 6 && relativeStr.endsWith(".class")) {
                                    relativeStr = relativeStr.substring(0, len - 6);
                                }

                                return relativeStr.isEmpty() 
                                        ? packageName 
                                        : packageName + "." + relativeStr;
                            } catch (Throwable e) {
                                if (log.isTraceEnabled()) {
                                    log.trace("[SPI] 处理路径失败：{}", path, e);
                                }
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                loadClassesInBatch(classNames, service, classLoader, result);
            }
        } catch (Exception e) {
            if (log.isTraceEnabled()) {
                log.trace("[SPI] Files.walk 遍历失败：{}", url, e);
            }
            try {
                File packageDir = new File(url.getFile());
                if (packageDir.exists()) {
                    doAnalysisFileUrlLegacy(result, packageDir, packageName, service, classLoader);
                }
            } catch (Exception ex) {
                if (log.isTraceEnabled()) {
                    log.trace("[SPI] 回退扫描失败：{}", url, ex);
                }
            }
        }
    }

    /**
     * 文件系统扫描的兼容回退逻辑，用于处理 {@link Files#walk} 方式不兼容或失败的场景。
     *
     * @param result 结果集合
     * @param packageDir 包目录
     * @param packageName 包名
     * @param service 服务接口
     * @param classLoader 类加载器
     */
    private void doAnalysisFileUrlLegacy(Collection<Class<?>> result,
                                         File packageDir,
                                         String packageName,
                                         Class<?> service,
                                         ClassLoader classLoader) {
        if (!packageDir.exists()) {
            return;
        }

        final int baseLen = packageDir.getAbsolutePath().length();
        Deque<File> stack = new ArrayDeque<>();
        stack.push(packageDir);

        while (!stack.isEmpty()) {
            File file = stack.pop();
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null) {
                    Collections.addAll(stack, files);
                }
                continue;
            }

            String fileName = file.getName();
            if (!fileName.endsWith(".class") || fileName.indexOf('$') != -1) {
                continue;
            }

            String absolutePath = file.getAbsolutePath();
            int pathLen = absolutePath.length();
            if (pathLen <= baseLen + 1) {
                continue;
            }

            String relative = absolutePath.substring(baseLen + 1, pathLen - 6);
            String subPackage = relative.replace(File.separatorChar, '.');
            String fullClassName = subPackage.isEmpty() 
                    ? packageName 
                    : packageName + "." + subPackage;

            try {
                Class<?> aClass = loadClassWithCache(fullClassName, classLoader);
                if (aClass != null && service.isAssignableFrom(aClass)
                        && !aClass.isInterface()
                        && !Modifier.isAbstract(aClass.getModifiers())) {
                    result.add(aClass);
                }
            } catch (Throwable e) {
                if (log.isTraceEnabled()) {
                    log.trace("[SPI] 加载类失败：{}", fullClassName, e);
                }
            }
        }
    }

    /**
     * 按批次加载多个类名对应的类对象。
     *
     * @param classNames 类名列表
     * @param service 服务接口
     * @param classLoader 类加载器
     * @param result 结果集合
     */
    private void loadClassesInBatch(List<String> classNames, 
                                    Class<?> service, 
                                    ClassLoader classLoader,
                                    Collection<Class<?>> result) {
        if (classNames.isEmpty()) {
            return;
        }

        for (int i = 0; i < classNames.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, classNames.size());
            List<String> batch = classNames.subList(i, end);
            
            for (String className : batch) {
                try {
                    Class<?> aClass = loadClassWithCache(className, classLoader);
                    if (aClass != null && isValidImplementation(aClass, service)) {
                        result.add(aClass);
                    }
                } catch (Throwable e) {
                    if (log.isTraceEnabled()) {
                        log.trace("[SPI] 加载类失败：{}", className, e);
                    }
                }
            }
        }
    }

    /**
     * 判断给定类是否为指定服务接口的有效实现类。
     *
     * @param aClass 待检查类
     * @param service 服务接口
     * @return 如果是有效实现类则返回 {@code true}
     */
    private boolean isValidImplementation(Class<?> aClass, Class<?> service) {
        return service.isAssignableFrom(aClass)
                && !aClass.isInterface()
                && !Modifier.isAbstract(aClass.getModifiers());
    }

    /**
     * 使用缓存和双重检查锁定机制加载类，避免重复扫描与重复加载。
     * <p>
     * 加载失败的类也会被缓存为 {@code null}，后续可直接跳过，减少重复尝试。
     *
     * @param className 类名
     * @param classLoader 类加载器
     * @return 加载成功的类对象；若失败则返回 {@code null}
     */
    private Class<?> loadClassWithCache(String className, ClassLoader classLoader) {
        Class<?> cached = CLASS_CACHE.get(className);
        if (cached != null) {
            return cached;
        }
        
        Object lock = CLASS_LOCKS.get(className);
        if (lock == null) {
            synchronized (CLASS_LOCKS) {
                lock = CLASS_LOCKS.get(className);
                if (lock == null) {
                    lock = new Object();
                    CLASS_LOCKS.put(className, lock);
                }
            }
        }
        
        synchronized (lock) {
            cached = CLASS_CACHE.get(className);
            if (cached != null) {
                return cached;
            }
            
            try {
                if (log.isTraceEnabled()) {
                    log.trace("[SPI] 加载类：{}", className);
                }
                Class<?> loaded = ClassUtils.forName(className, classLoader);
                CLASS_CACHE.put(className, loaded);
                if (log.isTraceEnabled()) {
                    log.trace("[SPI] 类加载成功：{}", className);
                }
                return loaded;
            } catch (Throwable e) {
                if (log.isTraceEnabled()) {
                    log.trace("[SPI] 类加载失败：{}", className, e);
                }
                CLASS_CACHE.put(className, null);
                return null;
            }
        }
    }
}
