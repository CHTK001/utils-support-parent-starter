package com.chua.runtime.apm.storage;

import lombok.extern.java.Log;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * 非 SQLite 存储的隔离加载器。
 *
 * <p>背景：agent jar 通过 {@code -Xbootclasspath/a} 进入 bootstrap classloader，
 * 而 bootstrap classloader <b>无法解析 java.sql</b>（该模块由 platform classloader 定义），
 * 因此直接在 agent 侧实例化 {@link SqliteStorage} 会抛
 * {@code NoClassDefFoundError: java/sql/SQLException}。</p>
 *
 * <p>本类在需要时构建一个 parent 为 {@link ClassLoader#getPlatformClassLoader()}
 * 的子 ClassLoader：</p>
 * <ul>
 *   <li>它能解析 java.sql / java.sql.rowset（inherits from platform）；</li>
 *   <li>{@code com.chua.runtime.*} 一律桥接回 agent ClassLoader，保证类型一致；</li>
 *   <li>它能加载 {@code apm.storage.jdbc.libs} 指向目录下的 JDBC 驱动与依赖（如 guava）；</li>
 *   <li>驱动由 {@link ApmSqliteDriver}（桥接驱动）实例化，连接由 {@link #driver()} 直接建立，
 *       不依赖 {@code DriverManager} 的调用方视角（agent 场景下该视角不可用）。</li>
 * </ul>
 *
 * <p>拿不到驱动时不构建隔离加载器，直接沿用 agent ClassLoader（应用 classpath 场景无额外成本）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Log
final class ApmStorageClassLoader {

    /**
     * 配置键：存放 JDBC 驱动及其依赖的目录（可用 {@link File#pathSeparator} 分隔多个）
     */
    private static final String KEY_JDBC_LIBS = "apm.storage.jdbc.libs";

    /**
     * 系统属性兜底键（宿主/agent 未传配置时可从启动参数给）
     */
    private static final String SYS_JDBC_LIBS = "apm.storage.jdbc.libs";

    /**
     * 桥接驱动类名：必须在隔离 ClassLoader 内定义（见 {@link #build(List)}）。
     */
    private static final String BRIDGE_DRIVER_PREFIX = "com.chua.runtime.apm.storage.ApmSqliteDriver";

    /**
     * 驱动类名候选：桥接驱动优先，官方驱动兜底。
     */
    private static final String[] DRIVER_CLASSES = {
            "com.chua.runtime.apm.storage.ApmSqliteDriver",
            "org.sqlite.JDBC"
    };

    /**
     * 已构建的隔离加载器（单例，驱动与类元数据复用）
     */
    private static volatile ClassLoader isolated;

    /**
     * 隔离 ClassLoader 中实例化的 JDBC 驱动（供存储实现直接建连）
     */
    private static volatile java.sql.Driver driver;

    /**
     * 隔离 ClassLoader 中实例化的 JDBC 驱动。
     *
     * @return 驱动；未启用隔离加载器时为 null
     */
    static java.sql.Driver driver() {
        return driver;
    }

    /**
     * 构建结果标记：true 表示已尝试过但无驱动可用，避免重复扫描
     */
    private static volatile boolean unavailable;

    /**
     * 工具类构造器。
     */
    private ApmStorageClassLoader() {
    }

    /**
     * 返回用于实例化存储实现的 ClassLoader。
     *
     * <p>无 JDBC 驱动可用时返回 {@code null}，调用方沿用默认（agent）ClassLoader。</p>
     *
     * @param config 存储配置
     * @return 隔离 ClassLoader，或 null
     */
    static ClassLoader resolve(Map<String, String> config) {
        ClassLoader ready = isolated;
        if (ready != null) {
            return ready;
        }
        if (unavailable) {
            return null;
        }
        synchronized (ApmStorageClassLoader.class) {
            if (isolated != null) {
                return isolated;
            }
            if (unavailable) {
                return null;
            }
            try {
                List<URL> urls = collectUrls(config);
                if (urls.isEmpty()) {
                    log.info("无可用的 JDBC 驱动 jar，沿用默认 ClassLoader");
                    unavailable = true;
                    return null;
                }
                ClassLoader built = build(urls);
                if (built == null) {
                    return null;
                }
                // 驱动必须能被隔离 ClassLoader 实例化，否则 getConnection 仍会失败，
                // 与其半残运行不如沿用调用方 ClassLoader 并交给上层降级。
                // 优先用桥接驱动 ApmSqliteDriver（不依赖官方模板初始化，agent 场景稳定）；
                // 它缺失时退回官方 org.sqlite.JDBC。
                java.sql.Driver instantiated = null;
                for (String candidate : DRIVER_CLASSES) {
                    try {
                        Class<?> driverType = Class.forName(candidate, true, built);
                        instantiated = (java.sql.Driver) driverType.getDeclaredConstructor().newInstance();
                        break;
                    } catch (Throwable e) {
                        log.warning("驱动 " + candidate + " 不可用: " + e);
                    }
                }
                if (instantiated == null) {
                    log.warning("隔离 ClassLoader 中无可用的 SQLite 驱动，沿用默认 ClassLoader");
                    unavailable = true;
                    return null;
                }
                driver = instantiated;
                isolated = built;
                log.info("存储隔离 ClassLoader 就绪: " + urls.size() + " 个 jar, 驱动 "
                        + instantiated.getClass().getName());
                return built;
            } catch (Throwable e) {
                log.warning("构建存储隔离 ClassLoader 失败，沿用默认 ClassLoader: " + e);
                unavailable = true;
                return null;
            }
        }
    }

    /**
     * 收集隔离加载器需要的 jar：显式配置的驱动目录 + agent 自身 jar。
     *
     * @param config 存储配置
     * @return jar 列表（去重、排序，保证可复现）
     */
    private static List<URL> collectUrls(Map<String, String> config) {
        List<URL> urls = new ArrayList<>();
        List<File> files = new ArrayList<>();

        String libs = config == null ? null : config.get(KEY_JDBC_LIBS);
        if (libs == null || libs.isBlank()) {
            libs = System.getProperty(SYS_JDBC_LIBS, "");
        }
        for (String part : libs.split(File.pathSeparator)) {
            if (part == null || part.isBlank()) {
                continue;
            }
            File dir = new File(part.trim());
            if (dir.isDirectory()) {
                File[] jars = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
                if (jars != null) {
                    files.addAll(java.util.Arrays.asList(jars));
                }
            } else if (dir.isFile()) {
                files.add(dir);
            }
        }

        File selfJar = selfJar();
        if (selfJar != null) {
            files.add(selfJar);
        }

        Map<String, File> unique = new TreeMap<>();
        for (File f : files) {
            unique.putIfAbsent(f.getAbsolutePath(), f);
        }
        for (File f : unique.values()) {
            try {
                urls.add(f.toURI().toURL());
            } catch (Exception e) {
                log.warning("忽略不可用的 jar: " + f + " (" + e + ")");
            }
        }
        return urls;
    }

    /**
     * 定位 agent 自身 jar（{@code -Xbootclasspath/a} 追加的那一个）。
     *
     * @return agent jar；以目录形式运行时返回 null
     */
    private static File selfJar() {
        try {
            URL location = ApmStorageClassLoader.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            File file = new File(location.toURI());
            return file.isFile() ? file : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 构建隔离 ClassLoader。
     *
     * @param urls jar 列表
     * @return ClassLoader；无 jar 时返回 null
     */
    private static ClassLoader build(List<URL> urls) {
        if (urls.isEmpty()) {
            return null;
        }
        final ClassLoader parent = ClassLoader.getPlatformClassLoader();
        final ClassLoader fallback = ApmStorageClassLoader.class.getClassLoader();
        URLClassLoader raw = new URLClassLoader(urls.toArray(new URL[0]), parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(BRIDGE_DRIVER_PREFIX)) {
                    // 桥接驱动必须在本加载器内定义，它的 ClassLoader 决定驱动依赖可见性：
                    // 若桥接到 agent 父加载器，驱动就看不到本加载器里的 sqlite-jdbc。
                    return super.loadClass(name, resolve);
                }
                if (name.startsWith("com.chua.runtime.spy.") || name.startsWith("com.chua.runtime.protocol.")
                        || name.startsWith("com.chua.runtime.apm.")) {
                    // spy/protocol/apm 必须与调用方同一份类型，否则插桩回调会 ClassCastException
                    if (fallback == null) {
                        return super.loadClass(name, resolve);
                    }
                    return fallback.loadClass(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        return raw;
    }

    /**
     * 列出当前可见的存储 SPI 实现名（诊断用）。
     *
     * @param cl ClassLoader
     * @return 实现名列表
     */
    static List<String> listStorageNames(ClassLoader cl) {
        List<String> names = new ArrayList<>();
        if (cl == null) {
            return names;
        }
        try {
            for (Object s : ServiceLoader.load(ApmStorage.class, cl)) {
                names.add(((ApmStorage) s).name());
            }
        } catch (Throwable e) {
            log.fine("SPI 列举失败: " + e);
        }
        return names;
    }
}
