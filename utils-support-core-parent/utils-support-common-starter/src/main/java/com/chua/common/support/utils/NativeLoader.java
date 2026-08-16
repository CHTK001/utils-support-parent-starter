package com.chua.common.support.utils;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 支持链式、任务ID、MD5校验、固定目录的原生库加载器。
 * <p>
 * 从 classpath:/native/{platformDir}/ 抽取原生库到目标目录。
 * 默认抽取后 System.load；可通过 {@link #extractOnly(boolean)} 仅抽取（如 FaceEngine 自行加载）。
 * </p>
 * <p>
 * 也支持通过 {@link #basePath(String)} 指定任意 classpath 路径，
 * 用于抽取模型目录等非原生库资源（如 models/minimind/ 下的 model.onnx + tokenizer.json）。
 * </p>
 * <p>
 * 用法：
 * <pre>
 * // 抽取并加载原生库
 * NativeLoader.of("rust-module")
 *     .toTarget(modelPath + "/rust")
 *     .glob("libxxx*")
 *     .load();
 *
 * // 仅抽取原生库（ArcSoft FaceEngine 等）
 * NativeLoader.of("arcsoft")
 *     .toTarget(modelPath + "/arcsoft")
 *     .glob("libarcsoft_face*.dll")
 *     .extractOnly(true)
 *     .load();
 *
 * // 抽取模型目录资源（tokenizer.json 等附加文件）
 * NativeLoader.of("minimind-resources")
 *     .basePath("models/minimind/")
 *     .toTarget(modelRoot)
 *     .glob("*")
 *     .extractOnly(true)
 *     .load();
 * </pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NativeLoader {

    private static final Map<String, Boolean> LOADED = new ConcurrentHashMap<>();

    private final String taskId;
    private ClassLoader classLoader;
    private Path targetDir;
    private String glob;
    private boolean md5;
    private boolean extractOnly;
    /**
     * 自定义 classpath 基础路径，覆盖默认的 native/{platformDir}/。
     * 设置后从指定路径抽取任意资源文件（如模型目录 models/minimind/）。
     */
    private String basePath;

    private NativeLoader(String taskId) {
        this.taskId = taskId;
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.glob = "*.dll";
        this.md5 = true;
        this.extractOnly = false;
    }

    /**
     * 创建加载器
     */
    public static NativeLoader of(String taskId) {
        return new NativeLoader(taskId);
    }

    /**
     * 设置加载源 classloader，默认当前线程上下文 classloader
     */
    public NativeLoader from(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : this.classLoader;
        return this;
    }

    /**
     * 设置目标目录（必填）。所有匹配的原生库将拷贝到该目录。
     */
    public NativeLoader toTarget(String targetDir) {
        this.targetDir = Path.of(targetDir);
        return this;
    }

    /**
     * 设置目标目录（必填）。
     */
    public NativeLoader toTarget(Path targetDir) {
        this.targetDir = targetDir;
        return this;
    }

    /**
     * 设置加载通配符，如 libarcsoft_face*.dll，默认 *.dll
     */
    public NativeLoader glob(String glob) {
        this.glob = glob;
        return this;
    }

    /**
     * 是否启用 MD5 校验（默认 true）。启用时只有内容变化才重新拷贝。
     */
    public NativeLoader withMd5(boolean md5) {
        this.md5 = md5;
        return this;
    }

    /**
     * 仅抽取到目标目录，不调用 System.load。
     * FaceEngine 等 SDK 自行加载 DLL 时使用。
     */
    public NativeLoader extractOnly(boolean extractOnly) {
        this.extractOnly = extractOnly;
        return this;
    }

    /**
     * 设置自定义 classpath 基础路径，覆盖默认的 native/{platformDir}/。
     * <p>
     * 默认从 classpath:/native/{platformDir}/ 抽取原生库；
     * 设置后从指定路径抽取任意资源文件，适用于模型目录等非原生库场景。
     * </p>
     *
     * @param basePath classpath 路径，如 "models/minimind/"
     * @return this
     */
    public NativeLoader basePath(String basePath) {
        this.basePath = basePath;
        return this;
    }

    /**
     * 执行：按 glob 匹配 classpath:/native/{platformDir}/ 下的资源，
     * 拷贝到 targetDir；非 extractOnly 时再按文件名排序 System.load。
     * <p>
     * 同一 taskId 只会执行一次，后续直接返回。
     */
    public void load() {
        if (LOADED.containsKey(taskId)) {
            return;
        }
        synchronized (NativeLoader.class) {
            if (LOADED.containsKey(taskId)) {
                return;
            }
            doLoad();
            LOADED.put(taskId, Boolean.TRUE);
        }
    }

    /**
     * 返回目标目录（绝对路径字符串），便于 FaceEngine(libPath) 使用。
     */
    public String getTargetDir() {
        if (targetDir == null) {
            return null;
        }
        return targetDir.toAbsolutePath().toString();
    }

    private void doLoad() {
        if (targetDir == null) {
            throw new IllegalStateException("targetDir 未设置，请先调用 toTarget()");
        }
        try {
            Files.createDirectories(targetDir);
            // basePath 优先：支持任意 classpath 路径（如模型目录）；否则使用默认的 native/{platformDir}/
            String resourceBase = basePath != null
                    ? basePath
                    : ("native/" + NativeUtils.getPlatformDir() + "/");
            List<ResourceItem> matched = listClasspathResources(resourceBase);
            if (matched.isEmpty()) {
                throw new UnsatisfiedLinkError("未在 classpath 找到匹配 " + glob + " 的文件: " + resourceBase);
            }

            String md5Hex = computeResourcesMd5(matched);
            Path marker = targetDir.resolve(".md5_" + md5Hex);
            boolean skipCopy = md5 && Files.exists(marker);
            if (!skipCopy) {
                for (ResourceItem item : matched) {
                    Path dest = targetDir.resolve(item.name);
                    if (Files.notExists(dest) || Files.size(dest) != item.size) {
                        try (InputStream in = item.open()) {
                            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                Files.writeString(marker, md5Hex);
            }

            if (!extractOnly) {
                java.io.File[] toLoad = targetDir.toFile().listFiles((d, name) -> match(name, glob));
                if (toLoad != null && toLoad.length > 0) {
                    Arrays.sort(toLoad, (a, b) -> a.getName().compareTo(b.getName()));
                    for (java.io.File f : toLoad) {
                        NativeUtils.loadFromPathInternal(null, f.getAbsolutePath());
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("NativeLoader 加载失败: taskId=" + taskId, e);
        }
    }

    private List<ResourceItem> listClasspathResources(String resourceBase) throws Exception {
        List<ResourceItem> result = new ArrayList<>();
        Enumeration<URL> urls = classLoader.getResources(resourceBase);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                java.io.File dirFile = new java.io.File(url.toURI());
                java.io.File[] files = dirFile.listFiles((d, name) -> match(name, glob));
                if (files != null) {
                    for (java.io.File f : files) {
                        result.add(new ResourceItem(f.getName(), f.length(), () -> Files.newInputStream(f.toPath())));
                    }
                }
            } else if ("jar".equals(protocol)) {
                JarURLConnection conn = (JarURLConnection) url.openConnection();
                try (JarFile jarFile = conn.getJarFile()) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith(resourceBase)) {
                            continue;
                        }
                        String simpleName = name.substring(resourceBase.length());
                        if (simpleName.contains("/")) {
                            continue;
                        }
                        if (!match(simpleName, glob)) {
                            continue;
                        }
                        long size = entry.getSize();
                        result.add(new ResourceItem(simpleName, size < 0 ? 0 : size, () -> classLoader.getResourceAsStream(name)));
                    }
                }
            } else {
                // other protocols not supported
            }
        }
        return result;
    }

    private static boolean match(String name, String glob) {
        if (name == null) {
            return false;
        }
        String regex = glob.replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return name.matches(regex);
    }

    private static String computeResourcesMd5(List<ResourceItem> items) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        List<ResourceItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> a.name.compareTo(b.name));
        for (ResourceItem item : sorted) {
            md.update(item.name.getBytes());
            md.update(String.valueOf(item.size).getBytes());
        }
        return bytesToHex(md.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface StreamSupplier {
        InputStream open() throws Exception;
    }

    private static final class ResourceItem {
        private final String name;
        private final long size;
        private final StreamSupplier supplier;

        private ResourceItem(String name, long size, StreamSupplier supplier) {
            this.name = name;
            this.size = size;
            this.supplier = supplier;
        }

        private InputStream open() throws Exception {
            InputStream in = supplier.open();
            if (in == null) {
                throw new UnsatisfiedLinkError("无法打开资源: " + name);
            }
            return in;
        }
    }
}
