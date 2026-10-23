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
 * 支持 JDBC 的存储实现的隔离加载器。
 *
 * <p>背景（已实测）：agent jar 会被 {@code RuntimeAgent} 追加到
 * bootstrap 搜索路径（{@code appendToBootstrapClassLoaderSearch}），
 * 之后 <b>bootstrap 能看见的类</b>（{@code com.chua.runtime.*}）
 * 一律由 bootstrap classloader 定义，而 bootstrap classloader
 * <b>无法解析 java.sql</b>（该模块由 platform classloader 定义，且
 * bootstrap 不会向 platform 委托，因为 bootstrap 无可用的父加载器兜底）。
 * 因此只要 {@link SqliteStorage} / JDBC 驱动由 bootstrap 定义，就必然抛
 * {@code NoClassDefFoundError: java/sql/Driver}（或 {@code java/sql/SQLException}）。</p>
 *
 * <p>本类因此构建 parent 为 {@link ClassLoader#getSystemClassLoader()} 的
 * child-first ClassLoader：</p>
 * <ul>
 *   <li>system 能解析 java.sql / javax.sql（由 platform 提供），所以本加载器内
 *       定义的存储实现可以正常链接 JDBC 类型；</li>
 *   <li>{@code com.chua.runtime.*} 中除存储实现外一律桥接回调用方加载器，
 *       保证 SPI 接口、事件模型、协议对象与调用方是同一份类型；</li>
 *   <li>存储实现（{@link ApmStorage} 的实现与其驱动）由本加载器自己定义，
 *       从而脱离 bootstrap 的 java.sql 盲区；</li>
 *   <li>它能加载 {@code apm.storage.jdbc.libs} 指向目录下的 JDBC 驱动与依赖（如 guava）。</li>
 * </ul>
 *
 * <p>宿主进程（存储类本来就由 system 定义、java.sql 可见）不构建隔离加载器，
 * 直接沿用默认加载器，避免无谓的类型分裂。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Log
public final class ApmStorageClassLoader {

    /**
     * 配置键：存放 JDBC 驱动及其依赖的目录（可用 {@link File#pathSeparator} 分隔多个）
     */
    private static final String KEY_JDBC_LIBS = "apm.storage.jdbc.libs";

    /**
     * 系统属性兜底键（宿主/agent 未传配置时可从启动参数给）
     */
    private static final String SYS_JDBC_LIBS = "apm.storage.jdbc.libs";

    /**
     * 需要由隔离加载器自己定义的类（会链接 java.sql，绝不能由 bootstrap 定义）。
     *
     * <p>不含 {@code NoopStorage} / {@code InMemoryStorage}：它们不依赖 JDBC，
     * 交给调用方定义可保证 {@code StorageManager.get()} 的类型与调用方一致。</p>
     */
    private static final String STORAGE_PACKAGE = "com.chua.runtime.apm.storage.";

    /**
     * 必须隔离定义的具体类（存储实现 + 桥接驱动）。
     */
    private static final String[] ISOLATED_CLASSES = {
            "com.chua.runtime.apm.storage.SqliteStorage",
            "com.chua.runtime.apm.storage.ApmSqliteBridgeDriver"
    };

    /**
     * 驱动类名候选：桥接驱动优先，官方驱动兜底。
     */
    private static final String[] DRIVER_CLASSES = {
            "com.chua.runtime.apm.storage.ApmSqliteBridgeDriver",
            "org.sqlite.JDBC"
    };

    /**
     * 已实例化的驱动（键为类名，值为隔离加载器内构造的实例）。
     *
     * <p>必须用 {@link Object} 而非 {@code java.sql.Driver}：本类可能由 bootstrap
     * 定义，而 bootstrap 解析不到 {@code java.sql}。</p>
     */
    private static final Map<String, Object> DRIVERS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 已构建的隔离加载器（单例，驱动与类元数据复用）
     */
    private static volatile ClassLoader isolated;

    /**
     * 是否已尝试构建（避免重复扫描）
     */
    private static volatile boolean resolved;

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
     * <p><b>注意</b>：本类由 bootstrap 定义时不能出现任何 {@code java.sql} 类型的成员
     * （字段/参数/返回值/局部变量），否则 bootstrap 链接本类时会直接抛
     * {@code NoClassDefFoundError: java/sql/Driver}。驱动因此只以 {@link Object}
     * 形式存在于隔离加载器内，由存储实现自己取用。</p>
     *
     * @param config 存储配置
     * @return 隔离 ClassLoader，或 null
     */
    static ClassLoader resolve(Map<String, String> config) {
        ClassLoader ready = isolated;
        if (ready != null) {
            return ready;
        }
        if (resolved) {
            return null;
        }
        synchronized (ApmStorageClassLoader.class) {
            if (isolated != null) {
                return isolated;
            }
            if (resolved) {
                return null;
            }
            resolved = true;
            if (!needsIsolation()) {
                // 存储类本身就在 java.sql 可见的命名空间（宿主进程 / 应用 classpath），
                // 无需隔离，直接沿用默认加载器，避免与调用方类型分裂。
                log.info("存储类由 " + SqliteStorage.class.getClassLoader()
                        + " 定义且 java.sql 可见，无需隔离加载器");
                return null;
            }
            log.info("检测到存储类由 bootstrap 定义且 java.sql 不可见，构建隔离加载器");
            try {
                List<URL> urls = collectUrls(config);
                if (urls.isEmpty()) {
                    log.info("无可用的 JDBC 驱动 jar，沿用默认 ClassLoader");
                    return null;
                }
                ClassLoader built = build(urls);
                if (built == null) {
                    return null;
                }
                // 驱动必须能被隔离 ClassLoader 实例化，否则 getConnection 仍会失败。
                // 优先用桥接驱动 ApmSqliteBridgeDriver（不依赖官方模板初始化，agent 场景稳定）；
                // 它缺失时退回官方 org.sqlite.JDBC。
                boolean available = false;
                for (String candidate : DRIVER_CLASSES) {
                    try {
                        Class<?> driverType = Class.forName(candidate, true, built);
                        Object instance = driverType.getDeclaredConstructor().newInstance();
                        DRIVERS.put(candidate, instance);
                        available = true;
                        log.info("存储隔离 ClassLoader 就绪: " + urls.size() + " 个 jar, 驱动 "
                                + candidate + " @ " + built);
                        break;
                    } catch (Throwable e) {
                        log.warning("驱动 " + candidate + " 不可用: " + e);
                    }
                }
                if (!available) {
                    log.warning("隔离 ClassLoader 中无可用的 SQLite 驱动，沿用默认 ClassLoader");
                    return null;
                }
                isolated = built;
                return built;
            } catch (Throwable e) {
                log.warning("构建存储隔离 ClassLoader 失败，沿用默认 ClassLoader: " + e);
                return null;
            }
        }
    }

    /**
     * 隔离加载器内实例化的驱动。
     *
     * <p>用 {@link Object} 保存：本类由 bootstrap 定义，声明 {@code java.sql.Driver}
     * 字段会导致本类链接失败。</p>
     *
     * @param className 驱动类名
     * @return 驱动实例；未实例化返回 null
     */
    /**
     * 取隔离加载器内实例化的驱动（交给隔离加载器内的存储实现使用）。
     *
     * <p>本方法只做 {@link Map} 读取、不引用 {@code java.sql}：本类由 bootstrap 定义，
     * 一旦引用 {@code java.sql} 就会在链接期抛 {@code NoClassDefFoundError: java/sql/Driver}。
     * 因此这里故意把返回类型写成 {@link Object}，由调用方（隔离加载器内）负责类型收窄。</p>
     *
     * @param className 驱动类名
     * @return 驱动实例；未实例化返回 null
     */
    public static Object driverInstance(String className) {
        return DRIVERS.get(className);
    }

    /**
     * 是否需要隔离加载器。
     *
     * <p>判据：存储实现类当前由 bootstrap 定义（agent 场景），且 bootstrap 看不到
     * {@code java.sql} —— 此时任何 JDBC 存储都必须在隔离加载器内实例化。</p>
     *
     * <p>反例（宿主进程）：存储类由 system 定义，java.sql 正常可见，返回 false，
     * 保持 {@code StorageManager.get()} 与调用方同一份类型。</p>
     *
     * @return 需要隔离返回 true
     */
    private static boolean needsIsolation() {
        try {
            ClassLoader storageLoader = SqliteStorage.class.getClassLoader();
            if (storageLoader != null) {
                return false;
            }
            // 存储类确实由 bootstrap 定义；再确认 bootstrap 解析不了 java.sql
            Class.forName("java.sql.Driver", false, null);
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        } catch (Throwable e) {
            return true;
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
     * 定位 agent 自身 jar（被追加到 bootstrap 搜索路径的那一个）。
     *
     * <p>不能用 {@code getProtectionDomain().getCodeSource()}：agent 场景下本类由
     * bootstrap 定义，{@code CodeSource} 为 {@code null}（实测）。改用类资源定位，
     * 它返回 {@code jar:file:/...!/com/chua/.../X.class}。</p>
     *
     * @return agent jar；以目录形式运行时返回 null
     */
    private static File selfJar() {
        try {
            URL resource = ApmStorageClassLoader.class
                    .getResource("ApmStorageClassLoader.class");
            if (resource == null) {
                return null;
            }
            String spec = resource.toString();
            if (!spec.startsWith("jar:")) {
                return null;
            }
            int end = spec.indexOf("!/");
            if (end < 0) {
                return null;
            }
            URL jarUrl = new URL(spec.substring("jar:".length(), end));
            File file = new File(jarUrl.toURI());
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
        return new URLClassLoader(urls.toArray(new URL[0]), parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (isIsolatedClass(name)) {
                    // 存储实现与 JDBC 驱动必须在本加载器内定义：
                    // 若交给 bootstrap，它们就落在 java.sql 不可见的命名空间里，链接必失败。
                    // 注意：这里<b>不能</b>调用 resolveClass —— 它会以委托方式重新解析
                    // （等价 Class.forName），触发 java.sql.Driver 的再加载而失败（实测）。
                    // 返回未链接的 Class 即可，链接在首次使用该类时于本加载器内完成。
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            try {
                                loaded = findClass(name);
                            } catch (ClassNotFoundException e) {
                                // 本加载器没有该类（例如与 jar 无关的扩展点），交回父加载器
                                return parent.loadClass(name);
                            }
                        }
                        return loaded;
                    }
                }
                if (name.startsWith("com.chua.runtime.spy.") || name.startsWith("com.chua.runtime.protocol.")
                        || name.startsWith("com.chua.runtime.apm.")) {
                    // spy/protocol/apm 必须与调用方同一份类型，否则插桩回调会 ClassCastException
                    if (fallback == null) {
                        return parent.loadClass(name);
                    }
                    return fallback.loadClass(name);
                }
                // 用 parent.loadClass 而不是 super.loadClass：后者在匿名类里会被编译成
                // invokespecial ClassLoader.loadClass，触发 java.sql.Driver 的反射再解析，
                // 而本加载器所在命名空间解析不到它（NoClassDefFoundError）。
                return parent.loadClass(name);
            }
        };
    }

    /**
     * 是否由隔离加载器自己定义该类。
     *
     * @param name 类名
     * @return 需要隔离定义返回 true
     */
    private static boolean isIsolatedClass(String name) {
        if (!name.startsWith(STORAGE_PACKAGE)) {
            return false;
        }
        for (String isolated : ISOLATED_CLASSES) {
            if (isolated.equals(name)) {
                return true;
            }
        }
        return false;
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

    /**
     * 存储类型对应的隔离实现类名。
     *
     * <p>只有需要 JDBC 的实现（{@code sqlite}）需要在隔离加载器内定义。</p>
     *
     * @param type 存储类型（{@link ApmStorage#name()}）
     * @return 类名；该类型不需要隔离时返回 null
     */
    public static String isolatedClassName(String type) {
        if ("sqlite".equalsIgnoreCase(type)) {
            return "com.chua.runtime.apm.storage.SqliteStorage";
        }
        return null;
    }
}
