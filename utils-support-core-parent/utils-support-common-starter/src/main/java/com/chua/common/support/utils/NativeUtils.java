package com.chua.common.support.utils;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地库工具类。
 *
 * @author CH
 * @since 4.0.0.42
*/
public class NativeUtils {

    /** 操作系统名称（小写） */
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    /** 原始架构名称（小写） */
    private static final String RAW_ARCH = System.getProperty("os.arch").toLowerCase();
    /** 归一化后的架构名称 */
    private static final String OS_ARCH = normalizeArch(RAW_ARCH);
    /** 操作系统前缀（窗口/Linux/darwin 等） */
    private static final String OS_PREFIX = getOsPrefix();
    /** 架构回退候选列表 */
    private static final String[] FALLBACK_ARCHES = buildFallbackArches();

    /** 已加载库的缓存（键: 图书馆名称|basedir） */
    private static final ConcurrentHashMap<String, Boolean> LOADED = new ConcurrentHashMap<>();
    /** 已加载库的临时文件路径集合 */
    private static final Set<String> LOADED_PATHS = new LinkedHashSet<>();

    /** Native 库在临时目录下的统一根目录名 */
    public static final String NATIVE_TMP_ROOT = "chua-native";

    /** 创建 NAT工具 实例 */
    private NativeUtils() {}

    /**
    * 获取 NAT 库抽取统一临时根目录（{@code java.io.tmpdir/chua-native}）。
    *
    * @return 统一临时根目录 路径
    */
    public static Path tempRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), NATIVE_TMP_ROOT);
    }

    /**
    * 获取 JVM 临时目录（{@code java.io.tmpdir}）。
    *
    * @return 临时目录 路径
    */
    public static Path tmpDir() {
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    // ==================== 架构归一化 ====================

    /**
    * normalizeArch Linux Linux
    *
    * @param arch Arch Linux Linux
    * @return normalizeArch Linux的结果
    */
    private static String normalizeArch(String arch) {
        return switch (arch) {
            case "amd64", "x86_64", "x64", "em64t", "k8" -> "x86_64";
            case "i386", "i486", "i586", "i686", "x86", "ia32", "win32" -> "x86";
            case "aarch64", "arm64", "armv8", "armv8l" -> "aarch64";
            case "arm", "armv5", "armv5t", "armv5te", "armv5tej",
                 "armv6", "armv6j", "armv6k", "armv6l", "armv6z", "armv6zk",
                 "armv7", "armv7l", "armv7m", "armv7r" -> "arm";
            case "riscv64" -> "riscv64";
            case "s390x", "s390" -> "s390x";
            case "ppc64", "ppc64le", "powerpc64", "powerpc64le" -> "ppc64le";
            case "ppc", "powerpc", "powerpc32" -> "ppc";
            case "mips", "mipsel", "mips64", "mips64el" -> "mips";
            case "sparc", "sparcv9" -> "sparc";
            case "ia64", "itanium" -> "ia64";
            default -> arch;
        };
    }

    /**
    * 获取os前缀
    *
    * @return 获取os前缀的结果
    */
    private static String getOsPrefix() {
        if (OS_NAME.contains("linux")) {
            return "linux";
        }
        if (OS_NAME.contains("windows")) {
            return "windows";
        }
        if (OS_NAME.contains("mac")) {
            return "darwin";
        }
        if (OS_NAME.contains("freebsd")) {
            return "freebsd";
        }
        if (OS_NAME.contains("openbsd")) {
            return "openbsd";
        }
        if (OS_NAME.contains("sunos") || OS_NAME.contains("solaris")) {
            return "solaris";
        }
        if (OS_NAME.contains("aix")) {
            return "aix";
        }
        throw new UnsupportedOperationException("不支持的操作系统: " + OS_NAME);
    }

    /**
    * 构建降级Arch Linux
    *
    * @return 构建降级Arch Linux的结果
    */
    private static String[] buildFallbackArches() {
        Set<String> arches = new LinkedHashSet<>();
        arches.add(OS_ARCH);
        switch (OS_ARCH) {
            case "x86_64" -> { arches.add("x86"); }
            case "x86" -> { arches.add("x86_64"); }
            case "aarch64" -> { arches.add("x86_64"); arches.add("arm"); }
            case "arm" -> { arches.add("x86_64"); }
            case "ppc64le" -> { arches.add("ppc"); }
            case "mips" -> { arches.add("x86_64"); }
        }
        return arches.toArray(new String[0]);
    }

    // ==================== 公共 API ====================

    /**
    * 获取os前缀名称
    *
    * @return 获取os前缀名称的结果
    */
    public static String getOsPrefixName() {
        return OS_PREFIX;
    }

    /**
    * 获取Arch Linux名称
    *
    * @return 获取Arch Linux名称的结果
    */
    public static String getArchName() {
        return OS_ARCH;
    }

    /**
    * 获取当前平台的目录名（如 窗口-x86_64、Linux-aarch64、darwin-aarch64）
    * @return 获取platformdir的结果
    */
    public static String getPlatformDir() {
        return OS_PREFIX + "-" + OS_ARCH;
    }

    /**
    * 获取当前平台的所有候选目录名（按优先级排序）
    * @return 获取platformdircandidates的结果
    */
    public static List<String> getPlatformDirCandidates() {
        List<String> result = new ArrayList<>();
        for (String arch : FALLBACK_ARCHES) {
            result.add(OS_PREFIX + "-" + arch);
        }
        result.add(OS_PREFIX);
        return result;
    }

    /**
    * 获取当前平台的动态库文件名（带 lib 前缀）
    * @param libraryName 图书馆名称
    * @return 获取图书馆文件名称的结果
    */
    public static String getLibraryFileName(String libraryName) {
        return switch (OS_PREFIX) {
            case "linux", "freebsd", "openbsd", "solaris", "aix" -> "lib" + libraryName + ".so";
            case "windows" -> libraryName + ".dll";
            case "darwin" -> "lib" + libraryName + ".dylib";
            default -> libraryName;
        };
    }

    /**
    * 获取当前平台的动态库文件名（可选 lib 前缀）
    * @param libraryName 图书馆名称
    * @param withPrefix with前缀
    * @return 获取图书馆文件名称的结果
    */
    public static String getLibraryFileName(String libraryName, boolean withPrefix) {
        if (withPrefix) {
            return getLibraryFileName(libraryName);
        }
        return switch (OS_PREFIX) {
            case "linux", "freebsd", "openbsd", "solaris", "aix" -> libraryName + ".so";
            case "windows" -> libraryName + ".dll";
            case "darwin" -> libraryName + ".dylib";
            default -> libraryName;
        };
    }

    /**
    * 通用加载方法，按以下顺序尝试：
    * <ol>
    *   <li>System.loadLibrary (java.library.path)</li>
    *   <li>classpath 资源 /native/{platformDir}/{lib}（含架构回退）</li>
    *   <li>classpath 资源 /native/{lib}</li>
    *   <li>指定 baseDir 下的目录（含架构回退）</li>
    *   <li>系统属性 native.library.path（含架构回退）</li>
    *   <li>用户目录 ~/.native/{platformDir}/{lib}（含架构回退）</li>
    * </ol>
    *
    * @param libraryName 库名（不含前缀和后缀）
    * @param baseDir     备用搜索目录（可空）
    * @return true 表示加载成功（或已加载过）
    * @throws UnsatisfiedLinkError 所有方式均失败
    */
    public static synchronized boolean load(String libraryName, String baseDir) {
        String key = libraryName + "|" + (baseDir != null ? baseDir : "");
        if (LOADED.containsKey(key)) {
            return true;
        }

        String libFileName = getLibraryFileName(libraryName);
        UnsatisfiedLinkError lastError;

 // 1. 系统.加载图书馆
        try {
            System.loadLibrary(libraryName);
            markLoaded(key, libraryName, "System.loadLibrary");
            return true;
        } catch (UnsatisfiedLinkError ignored) {}

 // 2. 类路径 /NAT/{platformdir}/{lib}（含架构回退）
        for (String dir : getPlatformDirCandidates()) {
            try {
                loadFromClasspathInternal(libraryName, dir, libFileName);
                markLoaded(key, libraryName, "classpath:/native/" + dir + "/" + libFileName);
                return true;
            } catch (UnsatisfiedLinkError ignored) {}
        }

 // 3. 类路径 /NAT/{lib}
        try {
            loadFromClasspathInternal(libraryName, null, libFileName);
            markLoaded(key, libraryName, "classpath:/native/" + libFileName);
            return true;
        } catch (UnsatisfiedLinkError ignored) {}

 // 4. basedir 下的目录
        if (baseDir != null) {
            for (String dir : getPlatformDirCandidates()) {
                try {
                    String path = baseDir + "/" + dir + "/" + libFileName;
                    loadFromPathInternal(libraryName, path);
                    markLoaded(key, libraryName, path);
                    return true;
                } catch (UnsatisfiedLinkError ignored) {}
            }
            try {
                String path = baseDir + "/" + libFileName;
                loadFromPathInternal(libraryName, path);
                markLoaded(key, libraryName, path);
                return true;
            } catch (UnsatisfiedLinkError ignored) {}
        }

 // 5. NAT.图书馆.路径 系统属性
        String nativeLibPath = System.getProperty("native.library.path");
        if (nativeLibPath != null) {
            for (String d : nativeLibPath.split(File.pathSeparator)) {
                if (d.isEmpty()) {
                    continue;
                }
                for (String dir : getPlatformDirCandidates()) {
                    try {
                        String path = d + "/" + dir + "/" + libFileName;
                        loadFromPathInternal(libraryName, path);
                        markLoaded(key, libraryName, path);
                        return true;
                    } catch (UnsatisfiedLinkError ignored) {}
                }
                try {
                    String path = d + "/" + libFileName;
                    loadFromPathInternal(libraryName, path);
                    markLoaded(key, libraryName, path);
                    return true;
                } catch (UnsatisfiedLinkError ignored) {}
            }
        }

 // 6. 用户.Home/.NAT/{platformdir}/{lib}
        String userHome = System.getProperty("user.home");
        for (String dir : getPlatformDirCandidates()) {
            try {
                String path = userHome + "/.native/" + dir + "/" + libFileName;
                loadFromPathInternal(libraryName, path);
                markLoaded(key, libraryName, path);
                return true;
            } catch (UnsatisfiedLinkError ignored) {}
        }

        StringBuilder tried = new StringBuilder();
        tried.append("System.loadLibrary");
        for (String dir : getPlatformDirCandidates()) {
            tried.append(", classpath:/native/").append(dir).append("/").append(libFileName);
        }
        tried.append(", classpath:/native/").append(libFileName);
        if (baseDir != null) {
            for (String dir : getPlatformDirCandidates()) {
                tried.append(", ").append(baseDir).append("/").append(dir).append("/").append(libFileName);
            }
            tried.append(", ").append(baseDir).append("/").append(libFileName);
        }
        tried.append(", native.library.path, ~/.native/").append(getPlatformDir()).append("/").append(libFileName);

        throw new UnsatisfiedLinkError("无法加载原生库: " + libraryName + "\n" +
                "平台: " + OS_PREFIX + "-" + OS_ARCH + " (raw: " + RAW_ARCH + ")\n" +
                "已尝试: " + tried);
    }

    /**
    * 从 类路径 加载（兼容旧调用）
    * @param libraryName 图书馆名称
    */
    public static void loadFromClasspath(String libraryName) {
        load(libraryName, null);
    }

    /**
    * 从指定路径加载（兼容旧调用）
    * @param libraryName 图书馆名称
    * @param baseDir basedir
    */
    public static void loadFromPath(String libraryName, String baseDir) {
        String libFileName = getLibraryFileName(libraryName);
        String libPath = baseDir + "/" + getPlatformDir() + "/" + libFileName;
        loadFromPathInternal(libraryName, libPath);
    }

    // ==================== 内部方法 ====================

    /**
    * 加载从类路径内部
    *
    * @param libraryName 图书馆名称
    * @param subDir subdir
    * @param libFileName lib文件名称
    */
    private static void loadFromClasspathInternal(String libraryName, String subDir, String libFileName) {
        String classpathLib = subDir != null ? "/native/" + subDir + "/" + libFileName : "/native/" + libFileName;
        InputStream is = NativeUtils.class.getResourceAsStream(classpathLib);
        if (is == null) {
            throw new UnsatisfiedLinkError("未在 classpath 找到: " + classpathLib);
        }
        try {
            Path temp = Files.createTempFile(libraryName + "_", "_" + libFileName);
            Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
            is.close();
            temp.toFile().deleteOnExit();
            System.load(temp.toAbsolutePath().toString());
            LOADED_PATHS.add(temp.toAbsolutePath().toString());
        } catch (Exception e) {
            throw new UnsatisfiedLinkError("从 classpath 加载失败: " + libraryName + " - " + e.getMessage());
        }
    }

    /**
    * 加载从路径内部
    *
    * @param libraryName 图书馆名称
    * @param libPath lib路径
    */
    static void loadFromPathInternal(String libraryName, String libPath) {
        File libFile = new File(libPath);
        if (!libFile.exists()) {
            throw new UnsatisfiedLinkError("文件不存在: " + libPath);
        }
        try {
            System.load(libFile.getAbsolutePath());
            LOADED_PATHS.add(libFile.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            throw e;
        } catch (Exception e) {
            throw new UnsatisfiedLinkError("加载失败: " + libPath + " - " + e.getMessage());
        }
    }

    /**
    * 标记加载
    *
    * @param key 键
    * @param libraryName 图书馆名称
    * @param source 源
    */
    private static void markLoaded(String key, String libraryName, String source) {
        LOADED.put(key, true);
    }

    /**
    * 是否已加载指定库
    * @param libraryName 图书馆名称
    * @return 是否加载的结果
    */
    public static boolean isLoaded(String libraryName) {
        return LOADED.containsKey(libraryName + "|") ||
               LOADED.containsKey(libraryName + "|" + "");

    }

    /**
    * 获取已加载的库路径列表
    * @return 获取加载路径的结果
    */
    public static Set<String> getLoadedPaths() {
        return new LinkedHashSet<>(LOADED_PATHS);
    }

    /**
    * 清理临时文件
    */
    public static void cleanTempFiles() {
        for (String path : LOADED_PATHS) {
            File f = new File(path);
            if (f.exists() && f.getName().contains("_")) {
                f.delete();
            }
        }
    }
}
