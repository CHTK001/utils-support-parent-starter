package com.chua.common.support.objects.scanner;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.register.BeanDefinitionRegistry;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.chua.common.support.utils.StringUtils;

/**
 * 对象上下文 包扫描器，用于扫描指定包路径下的类并自动注册到容器。
 * <p>
 * 支持从文件系统目录和 JAR 包中扫描类文件，并过滤掉无法实例化的类（如接口、枚举、注解等）。
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public final class ObjectContextScanner {

    /**
     * 私有构造函数，防止外部实例化该类。
     */
    private ObjectContextScanner() {
    }

    /**
     * 扫描指定包路径下的所有类并注册到 对象上下文。
     *
     * @param context     对象上下文 实例，用于注册扫描到的 Bean。
     * @param basePackage 基包路径，例如 "com.example.服务"。
     */
    public static void scan(ObjectContext context, String basePackage) {
        if (context == null || StringUtils.isEmpty(basePackage)) {
            return;
        }
        BeanDefinitionRegistry registry = context.getRegistry();
        List<Class<?>> classes = findClasses(basePackage);
        for (Class<?> clazz : classes) {
            try {
                registry.registerBeanFromClass(clazz);
            } catch (Exception e) {
                log.debug("跳过无法注册的类：{}", clazz.getName(), e);
            }
        }
    }

    /**
     * 扫描多个包路径下的所有类。
     *
     * @param context      对象上下文 实例，用于注册扫描到的 Bean。
     * @param basePackages 基包路径列表，包含多个需要扫描的包名。
     */
    public static void scan(ObjectContext context, List<String> basePackages) {
        if (basePackages == null) {
            return;
        }
        for (String pkg : basePackages) {
            scan(context, pkg);
        }
    }

    /**
     * 查找指定包路径下的所有符合条件的类。
     *
     * @param basePackage 基包路径。
     * @return 找到的类列表。
     */
    private static List<Class<?>> findClasses(String basePackage) {
        String path = basePackage.replace('.', '/');
        List<Class<?>> result = Wraps.list();

        try {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(path);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    File dir = new File(resource.toURI());
                    if (dir.exists() && dir.isDirectory()) {
                        scanDirectory(dir, basePackage, result);
                    }
                } else if ("jar".equals(resource.getProtocol())) {
                    scanJar(resource, path, basePackage, result);
                }
            }
        } catch (Exception e) {
            log.warn("扫描包失败：{}", basePackage, e);
        }

        return result;
    }

    /**
     * 递归扫描文件系统目录下的类文件。
     *
     * @param dir         当前扫描的目录。
     * @param packageName 当前对应的包名。
     * @param result      结果列表，用于存储找到的类。
     */
    private static void scanDirectory(File dir, String packageName, List<Class<?>> result) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), result);
            } else if (file.getName().endsWith(".class")) {
                String fileName = file.getName();
                String className = packageName + "." + fileName.substring(0, fileName.length() - ".class".length());
                loadClass(className, result);
            }
        }
    }

    /**
     * 扫描 JAR 包中的类文件。
     *
     * @param resource     JAR 包的 URL 资源。
     * @param path         JAR 包内的相对路径。
     * @param basePackage  基础包名。
     * @param result       结果列表，用于存储找到的类。
     */
    private static void scanJar(URL resource, String path, String basePackage, List<Class<?>> result) {
        String url = resource.toExternalForm();
        try (FileSystem fs = FileSystems.newFileSystem(URI.create(url), Map.of())) {
            Path jarRoot = fs.getPath(path);
            if (Files.exists(jarRoot)) {
                try (Stream<Path> walk = Files.walk(jarRoot)) {
                    walk.filter(p -> p.toString().endsWith(".class"))
                            .forEach(p -> {
                                String relativePath = p.toString().replace('\\', '/');
                                String className = relativePath.replace('/', '.');
                                className = className.substring(0, className.length() - ".class".length());
                                loadClass(className, result);
                            });
                }
            }
        } catch (Exception e) {
            log.warn("扫描 JAR 失败：{}", url, e);
        }
    }

    /**
     * 加载类并验证其是否可被添加到结果列表中。
     * <p>
     * 仅添加非接口、非枚举、非注解、非记录、非抽象且具备公开无参构造的公开类。
     * 校验口径与 {@code ReflectUtils.instantiate} 一致（公开类 + 公开无参构造），
     * 但不再真实实例化——原实现会为每个候选类执行一遍构造函数（可能带副作用）并丢弃结果。
     *
     * @param className 类的全限定名。
     * @param result    结果列表。
     */
    private static void loadClass(String className, List<Class<?>> result) {
        try {
            Class<?> clazz = ReflectUtils.forName(className);
            if (clazz == null) {
                return;
            }
            if (!clazz.isInterface() && !clazz.isEnum() && !clazz.isAnnotation() && !clazz.isRecord()
                    && !Modifier.isAbstract(clazz.getModifiers())
                    && Modifier.isPublic(clazz.getModifiers())
                    && hasPublicNoArgConstructor(clazz)) {
                result.add(clazz);
            }
        } catch (Exception | LinkageError e) {
            log.debug("[scanner] 类加载或构造校验失败，跳过该类: {}", e.getMessage());
        }
    }

    /**
     * 判断类是否具备公开的无参构造（与 {@code MethodHandles.Lookup#findConstructor} 的可见性口径一致）。
     *
     * @param clazz 目标类
     * @return 存在公开无参构造时返回 true
     */
    private static boolean hasPublicNoArgConstructor(Class<?> clazz) {
        try {
            return Modifier.isPublic(clazz.getDeclaredConstructor().getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
