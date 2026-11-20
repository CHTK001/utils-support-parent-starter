package com.chua.common.support.spi.resolver;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.definition.ServiceDefinition;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
import com.chua.common.support.utils.StringUtils;


/**
 * 同包 SPI 服务解析器工具类。
 * <p>
 * 该实现会扫描指定服务接口所在包及其子包下的所有类，查找该接口的实现类并构造对应的服务定义。
 * <p>
 * 扫描策略如下：
 * <ol>
 *   <li>获取服务接口所在包名；</li>
 *   <li>通过类加载器定位包路径下的 {@code .class} 文件；</li>
 *   <li>解析 class 文件头做保守预过滤：接口/抽象类/module-info 直接排除，
 *       「直接继承 Object 且未实现任何接口」的普通类也必然不可能 assignable，一并排除；</li>
 *   <li>候选类仅加载、不初始化（{@code initialize=false}），避免触发被扫描类的
 *       {@code <clinit>} 副作用（历史上正是死锁环成因），再以
 *       {@code isAssignableFrom} 精确校验；</li>
 *   <li>过滤出实现该接口且非抽象的具体类；</li>
 *   <li>排除内部类，避免误扫描。</li>
 * </ol>
 * <p>
 * 同一 (资源URL, 包目录) 的扫描结果（类名 + 头预判，与具体服务接口无关）会被缓存，
 * 同包下多个服务接口共享扫描结果，避免重复遍历 jar 中央目录与重复读取字节。
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class SamePackageServiceResolver implements ServiceResolver {


    /**
     * 扫描结果缓存：key = 资源URL + '!' + 包目录名。
     * <p>
     * 同一个 jar 的中央目录/同一个目录树会被同包下多个服务接口反复遍历；
     * 缓存内容是「类名 + class 文件头预判结果」（与具体服务接口无关），可跨接口复用。
     * 相比旧的按类名缓存 {@code Class} 对象（会把包树下所有类永久钉在内存中），
     * 这里只保留轻量摘要，且容量受 {@link #SCAN_CACHE_MAX} 约束。
     * </p>
     */
    private static final Map<String, List<ClassFileInfo>> SCAN_CACHE = new ConcurrentHashMap<>(64);

    /**
     * 扫描缓存条目上限，防止极端场景下无界增长
     */
    private static final int SCAN_CACHE_MAX = 256;

    /**
     * JAR URL 锁缓存：同一 URL 的打开-扫描-关闭必须串行，
     * 避免一个线程 关闭 后其他线程访问已关闭的 jar文件 报 压缩 文件 关闭。
     */
    private static final Map<String, Object> JAR_URL_LOCKS = new ConcurrentHashMap<>(64);

    /**
     * class 文件访问标志：接口
     */
    private static final int ACC_INTERFACE = 0x0200;

    /**
     * class 文件访问标志：抽象类
     */
    private static final int ACC_ABSTRACT = 0x0400;

    /**
     * class 文件访问标志：module-info
     */
    private static final int ACC_MODULE = 0x8000;

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

        List<ServiceDefinition> result = Wraps.list();
        List<Class<?>> subTypeByPackage = findSubTypeByPackage(packageName, service, classLoader);

        int foundCount = 0;
        int validCount = 0;

        for (Class<?> aClass : subTypeByPackage) {
            foundCount++;
            if (!service.isAssignableFrom(aClass) || aClass.isInterface() ||
                    Modifier.isAbstract(aClass.getModifiers())) {
                continue;
            }

            validCount++;
            List<ServiceDefinition> serviceDefinitions = buildDefinition(service, aClass,
                    SamePackageServiceResolver.class);
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
        if (StringUtils.isEmpty(packageName)) {
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

        List<URL> urlList = Wraps.list();
        while (resources.hasMoreElements()) {
            urlList.add(resources.nextElement());
        }

        if (log.isTraceEnabled()) {
            log.trace("[SPI] 找到 {} 个资源路径，包名：{}", urlList.size(), packageName);
        }

        // 顺序扫描：历史上 parallel + 类初始化(<clinit>)曾形成死锁环；现在扫描
        // 只加载不初始化，死锁源头已消除，但顺序扫描开销可控、行为最稳，故保留。
        urlList.forEach(url -> {
            doAnalysisUrl(result, url, packageName, packageDirName, service, classLoader);
        });

        return Wraps.list(result);
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
        // 命中扫描缓存时无需打开 jar：缓存的是类名 + 头预判，与 jar 文件句柄无关
        String key = cacheKey(url, packageDirName);
        List<ClassFileInfo> cached = SCAN_CACHE.get(key);
        if (null != cached) {
            loadClassesInBatch(cached, service, classLoader, result);
            return;
        }

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
            List<ClassFileInfo> classFiles = scanJar(jarFile, packageDirName);
            if (SCAN_CACHE.size() < SCAN_CACHE_MAX) {
                SCAN_CACHE.put(key, classFiles);
            }
            loadClassesInBatch(classFiles, service, classLoader, result);
        } finally {
            try {
                jarFile.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * 遍历 JAR 条目，读取 class 字节并解析头信息，产出扫描摘要列表。
     *
     * @param jarFile 已打开的 JAR
     * @param packageDirName 包目录名
     * @return 扫描摘要列表
     */
    private List<ClassFileInfo> scanJar(JarFile jarFile, String packageDirName) {
        List<ClassFileInfo> infos = Wraps.list();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry jarEntry = entries.nextElement();
            String entryName = jarEntry.getName();
            if (!entryName.endsWith(".class")
                    || !entryName.startsWith(packageDirName)
                    || entryName.indexOf('$') != -1) {
                continue;
            }
            String className = entryName.substring(0, entryName.length() - 6).replace('/', '.');

            byte[] bytes;
            try (InputStream in = jarFile.getInputStream(jarEntry)) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                if (log.isTraceEnabled()) {
                    log.trace("[SPI] 读取类字节失败：{}", entryName, e);
                }
                continue;
            }
            infos.add(parseClassFile(className, bytes));
        }
        return infos;
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
                List<ClassFileInfo> classFiles = paths.sequential()
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            return fileName.endsWith(".class") && fileName.indexOf('$') == -1;
                        })
                        .map(path -> toClassFileInfo(packagePath, path, packageName))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(Wraps::list));

                String key = cacheKey(url, packageDirName);
                List<ClassFileInfo> cached = SCAN_CACHE.get(key);
                if (null != cached) {
                    classFiles = cached;
                } else if (SCAN_CACHE.size() < SCAN_CACHE_MAX) {
                    SCAN_CACHE.put(key, classFiles);
                }

                loadClassesInBatch(classFiles, service, classLoader, result);
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
     * 将单个 class 文件转换为扫描摘要（类名 + 头预判）。
     *
     * @param packagePath 包根路径
     * @param classFile class 文件路径
     * @param packageName 包名
     * @return 扫描摘要；读取/解析失败时返回 {@code null}
     */
    private ClassFileInfo toClassFileInfo(Path packagePath, Path classFile, String packageName) {
        try {
            Path relativePath = packagePath.relativize(classFile);
            String relativeStr = relativePath.toString()
                    .replace(File.separatorChar, '.')
                    .replace('/', '.');

            int len = relativeStr.length();
            if (len > 6 && relativeStr.endsWith(".class")) {
                relativeStr = relativeStr.substring(0, len - 6);
            }

            String className = relativeStr.isEmpty()
                    ? packageName
                    : packageName + "." + relativeStr;

            byte[] bytes = Files.readAllBytes(classFile);
            return parseClassFile(className, bytes);
        } catch (Throwable e) {
            if (log.isTraceEnabled()) {
                log.trace("[SPI] 处理路径失败：{}", classFile, e);
            }
            return null;
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
        Deque<File> stack = Wraps.deque();
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
                Class<?> aClass = loadClassWithoutInit(fullClassName, classLoader);
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
     * 加载扫描摘要中的候选类并做精确校验，命中者加入结果集合。
     * <p>
     * 头预判只做「必然不可能」的排除；此处再以 {@code isAssignableFrom} 精确校验，
     * 两层过滤结果与旧的全量加载 + 单层校验完全一致。
     *
     * @param classFiles 扫描摘要列表
     * @param service 服务接口
     * @param classLoader 类加载器
     * @param result 结果集合
     */
    private void loadClassesInBatch(List<ClassFileInfo> classFiles,
                                    Class<?> service,
                                    ClassLoader classLoader,
                                    Collection<Class<?>> result) {
        if (classFiles.isEmpty()) {
            return;
        }

        for (ClassFileInfo classFile : classFiles) {
            if (!classFile.canImplement(service)) {
                continue;
            }
            try {
                Class<?> aClass = loadClassWithoutInit(classFile.className(), classLoader);
                if (aClass != null && isValidImplementation(aClass, service)) {
                    result.add(aClass);
                }
            } catch (Throwable e) {
                if (log.isTraceEnabled()) {
                    log.trace("[SPI] 加载类失败：{}", classFile.className(), e);
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
     * 仅加载类、不执行静态初始化（{@code initialize=false}）。
     * <p>
     * 扫描阶段只需要做 {@code isAssignableFrom} 类型判定；初始化既慢，又会触发
     * 被扫描类的 {@code <clinit>} 副作用（历史上正是死锁环成因）。JVM 类加载器
     * 自身会缓存已定义的类，因此无需再自建按类名缓存 {@code Class} 的强引用表
     * （旧 CLASS_CACHE 会把包树下所有类永久钉在内存中）。
     * </p>
     *
     * @param className 类名
     * @param classLoader 类加载器
     * @return 加载成功的类对象；若失败则返回 {@code null}
     */
    private Class<?> loadClassWithoutInit(String className, ClassLoader classLoader) {
        try {
            if (log.isTraceEnabled()) {
                log.trace("[SPI] 加载类：{}", className);
            }
            return Class.forName(className, false, classLoader); // [P3C 1.10 豁免] SPI 扫描基础设施（仅加载不初始化）
        } catch (Throwable e) {
            if (log.isTraceEnabled()) {
                log.trace("[SPI] 类加载失败：{}", className, e);
            }
            return null;
        }
    }

    /**
     * 构造扫描缓存 key。
     *
     * @param url 资源 URL
     * @param packageDirName 包目录名
     * @return 缓存 key
     */
    private static String cacheKey(URL url, String packageDirName) {
        return url.toString() + '!' + packageDirName;
    }

    /**
     * class 文件扫描摘要：仅保留「能否成为实现类」判定所需的信息，
     * 使扫描阶段无需把候选类全部加载进 JVM。
     *
     * @param className 类全名
     * @param concrete 是否为具体类（非接口/抽象类/module-info）
     * @param trivialHierarchy 是否「直接继承 Object 且未实现任何接口」
     */
    private record ClassFileInfo(String className, boolean concrete, boolean trivialHierarchy) {

        /**
         * 基于 class 文件头的保守预判：只排除「必然不可能实现服务」的类，
         * 其余一律放行，交由加载后的 {@code isAssignableFrom} 精确校验兜底。
         *
         * @param service 服务类型
         * @return 通过预判返回 {@code true}
         */
        boolean canImplement(Class<?> service) {
            if (!concrete) {
                // 实现类必须是非抽象具体类（接口/抽象类/module-info 一律排除）
                return false;
            }
            // 直接继承 Object 且未实现任何接口的类，除 Object 自身外不可能被 assignable
            return !(trivialHierarchy && service != Object.class);
        }
    }

    /**
     * 解析 class 文件头（magic/常量池/access_flags/super_class/interfaces 计数），
     * 产出 {@link ClassFileInfo}。任何解析异常都保守放行（视为具体类、非平凡层级），
     * 由后续加载 + {@code isAssignableFrom} 精确判定，保证行为与全量加载一致。
     *
     * @param className 类全名
     * @param b class 文件字节
     * @return 扫描摘要
     */
    private static ClassFileInfo parseClassFile(String className, byte[] b) {
        try {
            if (b.length < 10 || readInt(b, 0) != 0xCAFEBABE) {
                return new ClassFileInfo(className, true, false);
            }
            int cpCount = readUnsignedShort(b, 8);
            byte[] tags = new byte[cpCount];
            int[] payload = new int[cpCount];
            int p = 10;
            for (int i = 1; i < cpCount; i++) {
                int tag = b[p] & 0xFF;
                tags[i] = (byte) tag;
                p++;
                switch (tag) {
                    case 1: // Utf8: u2 length + bytes
                        payload[i] = p;
                        p += 2 + readUnsignedShort(b, p);
                        break;
                    case 3: // Integer
                    case 4: // Float
                        p += 4;
                        break;
                    case 5: // Long（占两个常量池槽位）
                    case 6: // Double
                        p += 8;
                        i++;
                        break;
                    case 7: // Class
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        payload[i] = p;
                        p += 2;
                        break;
                    case 9: // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        payload[i] = p;
                        p += 4;
                        break;
                    case 15: // MethodHandle: u1 + u2
                        p += 3;
                        break;
                    default: // 未知标签：保守放行
                        return new ClassFileInfo(className, true, false);
                }
            }

            int accessFlags = readUnsignedShort(b, p);
            int superClassIndex = readUnsignedShort(b, p + 4);
            int interfacesCount = readUnsignedShort(b, p + 6);

            boolean concrete = (accessFlags & (ACC_INTERFACE | ACC_ABSTRACT | ACC_MODULE)) == 0;

            boolean superIsObject = false;
            if (superClassIndex > 0 && superClassIndex < cpCount && tags[superClassIndex] == 7) {
                int nameIndex = readUnsignedShort(b, payload[superClassIndex]);
                if (nameIndex > 0 && nameIndex < cpCount && tags[nameIndex] == 1) {
                    superIsObject = "java/lang/Object".equals(readUtf8(b, payload[nameIndex]));
                }
            }

            return new ClassFileInfo(className, concrete, superIsObject && interfacesCount == 0);
        } catch (Throwable e) {
            // 头信息解析失败：保守放行，交由加载后的 isAssignableFrom 精确判定
            return new ClassFileInfo(className, true, false);
        }
    }

    /**
     * 读取大端 u4。
     *
     * @param b 字节数组
     * @param off 偏移
     * @return 无符号 32 位值
     */
    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /**
     * 读取大端 u2。
     *
     * @param b 字节数组
     * @param off 偏移
     * @return 无符号 16 位值
     */
    private static int readUnsignedShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /**
     * 读取常量池 Utf8 条目。类/字段名均为 ASCII，常规 UTF-8 解码即可满足比较用途；
     * 即使出现极少数 modified-UTF-8 差异，也只是预判退化为「放行」，不影响正确性。
     *
     * @param b 字节数组
     * @param pos Utf8 条目负载起始位置（u2 长度字段处）
     * @return 解码字符串
     */
    private static String readUtf8(byte[] b, int pos) {
        int len = readUnsignedShort(b, pos);
        return new String(b, pos + 2, len, StandardCharsets.UTF_8);
    }
}
