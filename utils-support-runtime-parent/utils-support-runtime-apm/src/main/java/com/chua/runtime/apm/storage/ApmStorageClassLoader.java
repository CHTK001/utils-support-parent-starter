package com.chua.runtime.apm.storage;

import lombok.extern.java.Log;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支持 JDBC 的存储实现的隔离加载器。
 *
 * <p><b>背景（已实测，JDK 17/21/25 表现一致）</b>：{@code RuntimeAgent} 会把 agent jar
 * 追加到 bootstrap 搜索路径（{@code appendToBootstrapClassLoaderSearch}），使插桩回调
 * 能被 JDK 内部类（如 {@code java.net.Socket}）解析。代价是：之后凡是
 * <b>bootstrap 能看见的类</b>（{@code com.chua.runtime.*}）都由 bootstrap 定义，
 * 而 bootstrap <b>解析不到 {@code java.sql} / {@code javax.sql}</b>。</p>
 *
 * <p>由此产生三条硬约束，任何改动都必须遵守：</p>
 * <ol>
 *   <li><b>本类自身不得出现任何 {@code java.sql} 类型</b>（字段/参数/返回值/局部变量/方法引用）。
 *       否则加载本类即抛 {@code NoClassDefFoundError: java/sql/Driver}。</li>
 *   <li>SQLite 驱动、存储实现都必须由<b>本类创建的隔离加载器</b>定义，
 *       且该加载器对 {@code com.chua.runtime.apm.storage.*}、{@code org.sqlite.*}
 *       采用 <b>child-first</b>，否则会委托给 bootstrap 而失败。</li>
 *   <li>真正的 JDBC 动作只能在隔离加载器内定义的类里做（见 {@link SqliteSession}）：
 *       由 bootstrap 定义的调用方哪怕用反射触达 JDBC，也会在 sqlite 内部
 *       触发 {@code NoClassDefFoundError: java/sql/Statement}。</li>
 * </ol>
 *
 * <p>本类对外只提供 {@link Object}/{@code String}/{@code long}/{@code boolean} 级别的接口，
 * 调用方（{@link SqliteStorage}，本身也在隔离加载器内）拿到 {@link Object} 后不必解析
 * {@code java.sql}。</p>
 *
 * <p><b>为什么不需要反射桥接</b>：{@code SqliteStorage} 在隔离加载器内加载时，其 {@code implements}
 * 的 {@link ApmStorage} 接口经 {@code isChildFirst} 判定命中 {@link #STORAGE_API} 父优先名单，
 * 由父加载器（bootstrap）定义 —— 与 {@code StorageManager} 侧拿到的是同一份接口类型。
 * 已实测（bootstrap 追加 + 隔离加载器场景）{@code instanceof ApmStorage} 为 true，
 * 直接 cast 即可调用，历史上「ClassCastException 必须反射桥接」的结论不再成立。
 * 旧的 {@code IsolatedStorageBridge} 及其 Map 中转方法已移除。</p>
 *
 * <p>宿主进程（存储类由 system 定义、{@code java.sql} 可见）不构建隔离加载器，
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
     * 存储类型名：sqlite（依赖 JDBC，必须在隔离加载器内定义）
     */
    private static final String SQLITE = "sqlite";

    /**
     * 存储 API 与数据模型的类名（<b>必须由父加载器定义</b>，即父优先）。
     *
     * <p>{@code ApmStorage} / {@code StorageConfig} / 各类记录 / {@code Query} 以及
     * protocol 模型（{@code DependencyEdge}、{@code Endpoint} 等）由 bootstrap 定义，
     * 两侧共用同一份类型，桥接才能直接传递这些对象。</p>
     *
     * <p>{@code NoopStorage} / {@code InMemoryStorage} <b>不在</b>本名单：它们是普通实现，
     * 由隔离加载器定义也无妨；若与 {@code SqliteStorage} 落在不同加载器，则各自实现
     * 各自那份 {@code ApmStorage}，互不影响。</p>
     *
     * <p>注意：判定顺序与 {@link #isChildFirst(String)} 一致 —— 本名单优先于包前缀规则，
     * 否则会被 {@link #STORAGE_PACKAGE} 的包前缀规则抢先命中。</p>
     */
    private static final java.util.Set<String> STORAGE_API = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.chua.runtime.apm.storage.ApmStorage",
            "com.chua.runtime.apm.storage.StorageConfig",
            "com.chua.runtime.apm.storage.Query",
            "com.chua.runtime.apm.storage.TransmissionEvent",
            "com.chua.runtime.apm.storage.LeakRecord",
            "com.chua.runtime.apm.storage.LogRecord",
            "com.chua.runtime.protocol.DependencyEdge",
            "com.chua.runtime.protocol.Endpoint",
            "com.chua.runtime.protocol.EndpointKind",
            "com.chua.runtime.protocol.Protocol",
            "com.chua.runtime.protocol.Software",
            "com.chua.runtime.protocol.StatusCode"
    ));

    /**
     * 通用工具包（JSON 序列化等）。
     *
     * <p>同样必须由隔离加载器定义：{@code com.chua.common.support.lang.json} 依赖
     * 第三方 Jackson，而 bootstrap 看不到第三方依赖，实测会抛
     * {@code NoClassDefFoundError: Could not initialize class ...JacksonJsonProvider}。</p>
     */
    private static final String COMMON_PACKAGE = "com.chua.common.";

    /**
     * 存储实现与会话所在的包（必须由隔离加载器自己定义）
     */
    private static final String STORAGE_PACKAGE = "com.chua.runtime.apm.storage.";

    /**
     * 会话实现类（隔离加载器内定义，承载全部 JDBC 逻辑）
     */
    private static final String SESSION_CLASS = "com.chua.runtime.apm.storage.SqliteSession";

    /**
     * 驱动类名候选：只需要官方驱动（只依赖 {@code java.sql}）。
     *
     * <p>不要再引入「桥接驱动」：agent 场景下桥接类会随本类落到 bootstrap，
     * 它一实现 {@link java.sql.Driver} 就链接失败（实测 {@code NoClassDefFoundError: java/sql/Driver}）。</p>
     */
    private static final String[] DRIVER_CLASSES = {
            "org.sqlite.JDBC"
    };

    /**
     * 已构建的隔离加载器（单例，驱动与会话类元数据复用）
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
                if (!probeDriver(built)) {
                    log.warning("隔离 ClassLoader 中无可用的 SQLite 驱动，沿用默认 ClassLoader");
                    return null;
                }
                log.info("存储隔离 ClassLoader 就绪: " + urls.size() + " 个 jar @ " + built);
                isolated = built;
                return built;
            } catch (Throwable e) {
                log.warning("构建存储隔离 ClassLoader 失败，沿用默认 ClassLoader: " + e);
                return null;
            }
        }
    }

    /**
     * 创建会话（隔离加载器内定义的 {@code SqliteSession}）。
     *
     * <p>返回 {@link Object}：本类可能由 bootstrap 定义，不能声明会话类型
     * （会话类不在 bootstrap 可见范围内）。</p>
     *
     * @return 会话实例；隔离加载器不可用返回 null
     */
    public static Object newSession() {
        ClassLoader cl = isolated;
        if (cl == null) {
            return null;
        }
        try {
            return Class.forName(SESSION_CLASS, true, cl).getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            log.warning("创建 SQLite 会话失败: " + e);
            return null;
        }
    }

    /**
     * 是否有可用的隔离加载器（即 agent 场景 + 驱动就绪）。
     *
     * @return 可用返回 true
     */
    public static boolean isolated() {
        return isolated != null;
    }

    /**
     * 返回指定存储类型需要隔离加载的实现类名。
     *
     * <p>只对依赖 JDBC 的实现返回类名：这些类经过 bootstrap 追加后无法链接
     * {@code java.sql}，必须交给隔离加载器定义。其余实现（内存/noop）留在原
     * 命名空间，避免类型分裂。</p>
     *
     * @param type 存储类型名
     * @return 实现类名；无需隔离返回 null
     */
    public static String isolatedClassName(String type) {
        if (SQLITE.equals(type)) {
            return "com.chua.runtime.apm.storage.SqliteStorage";
        }
        return null;
    }

    /**
     * 校验隔离加载器里确实存在可实例化的驱动。
     *
     * <p>只做 {@code Class.forName + newInstance}，不调用任何 JDBC 方法：
     * 驱动实例的真实使用由 {@link SqliteSession} 在隔离加载器内完成。</p>
     *
     * @param loader 隔离加载器
     * @return 可用返回 true
     */
    private static boolean probeDriver(ClassLoader loader) {
        for (String candidate : DRIVER_CLASSES) {
            try {
                Class.forName(candidate, true, loader).getDeclaredConstructor().newInstance();
                log.fine("SQLite 驱动就绪: " + candidate);
                return true;
            } catch (Throwable e) {
                log.fine("驱动 " + candidate + " 不可用: " + e);
            }
        }
        return false;
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
     * <p>parent 为 platform：它能解析 {@code java.sql}/{@code javax.sql}。
     * 存储实现、会话与 SQLite 驱动一律 child-first，避免委托给 bootstrap。</p>
     *
     * @param urls jar 列表
     * @return ClassLoader；无 jar 时返回 null
     */
    private static ClassLoader build(List<URL> urls) {
        if (urls.isEmpty()) {
            return null;
        }
        final ClassLoader parent = ClassLoader.getPlatformClassLoader();
        return new URLClassLoader(urls.toArray(new URL[0]), parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (isChildFirst(name)) {
                    // 存储实现/会话/驱动必须在本加载器内定义：交给父加载器（bootstrap）
                    // 就落在 java.sql 不可见的命名空间里，链接必失败。
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded != null) {
                            return loaded;
                        }
                        return findClass(name);
                    }
                }
                return parent.loadClass(name);
            }
        };
    }

    /**
     * 是否需要 child-first。
     *
     * <p>两类类必须由本加载器自己定义，否则链接必失败：</p>
     * <ul>
     *   <li>{@code com.chua.runtime.apm.storage.*}：存储实现与会话本身。agent 场景下它们
     *       由 bootstrap 定义，bootstrap 解析不到 {@code java.sql}；实测若交给父加载器，
     *       会解析成 bootstrap 定义的那一份（同一份类被定义两次），进而在
     *       {@code Class.forName("org.sqlite.JDBC")} 处抛 {@code NoClassDefFoundError: java/sql/Driver}。</li>
     *   <li>{@code org.sqlite.*} 与驱动依赖（guava / failureaccess）：同理，若由 bootstrap 定义，
     *       其内部 {@code java.sql} 引用无法解析。</li>
     * </ul>
     *
     * <p>注意：只在 {@link #build(List)} 里使用；宿主进程（{@link #needsIsolation()} 为 false）
     * 不会创建本加载器，因此不会出现 {@code com.chua.runtime.*} 的类型分裂。</p>
     *
     * <p>{@code com.fasterxml.*}（Jackson）也在名单内：它被 {@code com.chua.common} 的
     * JSON 提供者使用，一旦落到 bootstrap 就会因缺少同级依赖而链接失败（实测）。</p>
     *
     * @param name 类名
     * @return child-first 返回 true
     */
    private static boolean isChildFirst(String name) {
        if (STORAGE_API.contains(name)) {
            return false;
        }
        return name.startsWith(STORAGE_PACKAGE)
                || name.startsWith(COMMON_PACKAGE)
                || name.startsWith("org.sqlite.")
                || name.startsWith("com.google.")
                || name.startsWith("com.fasterxml.")
                || name.startsWith("org.objectweb.asm.");
    }
}
