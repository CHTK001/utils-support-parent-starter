package com.chua.runtime.plugin.loader;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 插件类加载器 — 每个插件独立 类加载，支持 lib 目录下的传递依赖。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PluginClassLoader extends URLClassLoader {


    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(PluginClassLoader.class.getName());
    /**
     * 插件名称
     */
    private final String pluginName;

    /**
     * 插件根目录
     */
    private final Path pluginDir;

    /**
     * 已加载的类名集合
     */
    private final Set<String> loadedClasses;

    /**
     * 父类加载器
     */
    private final ClassLoader parent;

    /**
     * 创建插件类加载器。
     *
     * @param pluginName 插件名称
     * @param pluginDir  插件根目录
     * @param parent     父类加载器
     * @throws IOException 目录不存在
     */
    public PluginClassLoader(String pluginName, Path pluginDir, ClassLoader parent) throws IOException {
        super(toUrls(pluginDir), parent);
        this.pluginName = pluginName;
        this.pluginDir = pluginDir;
        this.parent = parent;
        this.loadedClasses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // child-first：先查本插件目录，再委派父加载器；注意不能再重写 findClass，
        // 否则 ClassLoader.loadClass 回调 findClass 会与本类形成无限互调
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isBootstrapClass(name)) {
                    loaded = getParent().loadClass(name);
                } else {
                    try {
                        loaded = super.findClass(name);
                    } catch (ClassNotFoundException e) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            loadedClasses.add(name);
            return loaded;
        }
    }

    /**
     * 是否是需要 Bootstrap 类加载 加载的类。
     *
     * @param name 类名
     * @return 是返回 true
     */
    private boolean isBootstrapClass(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("sun.")
                || name.startsWith("jdk.")
                || name.startsWith("com.sun.")
                || name.startsWith("com.oracle.");
    }

    /**
     * 扫描目录下所有 JAR 文件，包括 lib 子目录。
     *
     * @param pluginDir 插件根目录
     * @return JAR URL 列表
     */
    private static URL[] toUrls(Path pluginDir) {
        List<URL> urls = new ArrayList<>();
        try {
            if (Files.exists(pluginDir)) {
                urls.add(pluginDir.toUri().toURL());
                Path libDir = pluginDir.resolve("lib");
                if (Files.exists(libDir)) {
                    try (var stream = Files.list(libDir)) {
                        stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                                .forEach(p -> {
                                    try {
                                        urls.add(p.toUri().toURL());
                                    } catch (Exception e) {
                                        LOG.log(Level.WARNING, "添加 lib 目录 JAR 失败: " + p, e);
                                    }
                                });
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "构建插件 URL 失败: " + pluginDir, e);
        }
        return urls.toArray(new URL[0]);
    }

    /**
     * 加载插件实现类。
     *
     * @param className 插件实现类全名
     * @return Plugin 实例
     * @throws Exception 加载或创建失败
     */
    public Plugin loadPlugin(String className) throws Exception {
        Class<?> clazz = loadClass(className);
        return (Plugin) ReflectUtils.instantiate(clazz);
    }

    /**
     * 扫描插件目录下的 SPI 配置。
     *
     * @return Plugin 实现类名列表
     */
    public List<String> scanPlugins() {
        List<String> plugins = new ArrayList<>();
        Path spiPath = pluginDir.resolve("META-INF/services/com.chua.runtime.plugin.Plugin");
        if (Files.exists(spiPath)) {
            try {
                for (String line : Files.readAllLines(spiPath)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    plugins.add(trimmed);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "扫描 SPI 配置失败: " + spiPath, e);
            }
        }
        return plugins;
    }

    /**
     * 获取plugin名称
     *
     * @return 获取plugin名称的结果
     */
    public String getPluginName() {
        return pluginName;
    }

    /**
     * 获取plugindir
     *
     * @return 获取plugindir的结果
     */
    public Path getPluginDir() {
        return pluginDir;
    }

    /**
     * 获取加载类
     *
     * @return 获取加载类的结果
     */
    public Set<String> getLoadedClasses() {
        return Collections.unmodifiableSet(loadedClasses);
    }
}