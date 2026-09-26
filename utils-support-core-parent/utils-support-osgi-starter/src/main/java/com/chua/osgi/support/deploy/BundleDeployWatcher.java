package com.chua.osgi.support.deploy;

import com.chua.common.support.osgi.OsgiBundle;
import com.chua.common.support.osgi.OsgiLauncher;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Bundle 目录热部署监听器。
 * <p>
 * 周期扫描部署目录，按 jar 清单文件做增量处置：
 * </p>
 * <ul>
 *   <li>目录中出现的 jar：解析其 MANIFEST 符号名，若框架内未安装则安装并启动</li>
 *   <li>已安装但符号名相同、且内容有变化：执行框架原生 update 完成原地升级</li>
 *   <li>已安装但目录中已不存在：按配置卸载</li>
 * </ul>
 * <p>
 * 相比"卸载 + 重装"，原生 update 保留 bundle 标识与已解析的依赖关系；
 * 相比整目录重扫，本类用「符号名 + 文件最后修改时间 + 文件大小」指纹比对，
 * 避免每轮扫描都对全部 jar 做哈希运算。
 * </p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class BundleDeployWatcher {

    /**
     * MANIFEST 中符号名属性键。
     */
    private static final String MANIFEST_SYMBOLIC_NAME = "Bundle-SymbolicName";

    /**
     * 默认扫描间隔（毫秒）。
     */
    private static final long DEFAULT_POLLING_INTERVAL = 10000L;

    /**
     * osgi 启动器。
     */
    private final OsgiLauncher launcher;

    /**
     * 部署目录。
     */
    private final Path directory;

    /**
     * 扫描间隔（毫秒）。
     */
    private final long pollingInterval;

    /**
     * jar 移除后是否卸载。
     */
    private final boolean uninstallOnRemoved;

    /**
     * jar 内容变化时是否原地升级。
     */
    private final boolean updateOnChanged;

    /**
     * 上一轮扫描得到的「符号名 → jar 指纹」快照。
     */
    private final Map<String, String> fingerprints = new LinkedHashMap<>();

    /**
     * 调度线程池。
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 构造目录热部署监听器。
     *
     * @param launcher            osgi 启动器
     * @param directory           部署目录
     * @param pollingInterval     扫描间隔（毫秒），非正数时取默认值
     * @param uninstallOnRemoved  jar 移除后是否卸载
     * @param updateOnChanged     jar 内容变化时是否原地升级
     */
    public BundleDeployWatcher(OsgiLauncher launcher, String directory, long pollingInterval,
                               boolean uninstallOnRemoved, boolean updateOnChanged) {
        this.launcher = launcher;
        this.directory = Paths.get(directory == null || directory.isBlank() ? "./osgi-deploy" : directory);
        this.pollingInterval = pollingInterval > 0 ? pollingInterval : DEFAULT_POLLING_INTERVAL;
        this.uninstallOnRemoved = uninstallOnRemoved;
        this.updateOnChanged = updateOnChanged;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "osgi-deploy-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动周期扫描。
     */
    public void start() {
        if (!Files.isDirectory(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                log.error("[osgi-deploy] 创建部署目录失败: {}", directory, e);
                return;
            }
        }
        scheduler.scheduleWithFixedDelay(this::scanSafely, 0L, pollingInterval, TimeUnit.MILLISECONDS);
        log.info("[osgi-deploy] 目录热部署已启动: {}（扫描间隔 {}ms，移除即卸载 {}，变化即升级 {}）",
                directory.toAbsolutePath(), pollingInterval, uninstallOnRemoved, updateOnChanged);
    }

    /**
     * 停止周期扫描。
     */
    public void stop() {
        scheduler.shutdownNow();
        fingerprints.clear();
        log.info("[osgi-deploy] 目录热部署已停止");
    }

    /**
     * 扫描异常兜底，避免任务因异常被取消。
     */
    private void scanSafely() {
        try {
            scan();
        } catch (Exception e) {
            log.warn("[osgi-deploy] 扫描异常: {}", e.getMessage());
        }
    }

    /**
     * 执行一轮扫描与处置。
     */
    public synchronized void scan() {
        if (!launcher.isActive() || !Files.isDirectory(directory)) {
            return;
        }
        Map<String, String> current = new LinkedHashMap<>();
        File[] jars = directory.toFile().listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                String symbolicName = readSymbolicName(jar);
                if (symbolicName == null) {
                    log.debug("[osgi-deploy] 跳过无法解析符号名的 jar: {}", jar.getName());
                    continue;
                }
                current.put(symbolicName, fingerprint(jar));
            }
        }
        for (Map.Entry<String, String> entry : current.entrySet()) {
            String symbolicName = entry.getKey();
            OsgiBundle bundle = launcher.getBundle(symbolicName);
            if (bundle == null) {
                install(symbolicName);
            } else if (updateOnChanged && !Objects.equals(fingerprints.get(symbolicName), entry.getValue())) {
                update(symbolicName);
            }
        }
        if (uninstallOnRemoved) {
            for (String installed : new LinkedHashMap<>(fingerprints).keySet()) {
                if (!current.containsKey(installed)) {
                    uninstall(installed);
                }
            }
        }
        fingerprints.clear();
        fingerprints.putAll(current);
    }

    /**
     * 安装目录中的 bundle。
     *
     * <p>目录中的文件名未必等于符号名，因此统一按符号名回扫目录定位真实文件。</p>
     *
     * @param symbolicName bundle 符号名
     */
    private void install(String symbolicName) {
        String location = locateJar(symbolicName);
        if (location == null) {
            log.warn("[osgi-deploy] 目录中未找到符号名为 {} 的 jar", symbolicName);
            return;
        }
        try {
            OsgiBundle installed = launcher.installBundle(location);
            log.info("[osgi-deploy] 已安装: {} -> version={}", symbolicName, installed.getVersion());
        } catch (Exception e) {
            log.warn("[osgi-deploy] 安装失败 [{}]: {}", symbolicName, e.getMessage());
        }
    }

    /**
     * 原地升级 bundle。
     *
     * @param symbolicName bundle 符号名
     */
    private void update(String symbolicName) {
        try {
            OsgiBundle updated = launcher.updateBundle(symbolicName, locateJar(symbolicName));
            log.info("[osgi-deploy] 已升级: {} -> version={}", symbolicName, updated.getVersion());
        } catch (Exception e) {
            log.warn("[osgi-deploy] 升级失败 [{}]: {}", symbolicName, e.getMessage());
        }
    }

    /**
     * 卸载 bundle。
     *
     * @param symbolicName bundle 符号名
     */
    private void uninstall(String symbolicName) {
        try {
            if (launcher.uninstallBundle(symbolicName)) {
                log.info("[osgi-deploy] 已卸载: {}（目录中已不存在）", symbolicName);
            }
        } catch (Exception e) {
            log.warn("[osgi-deploy] 卸载失败 [{}]: {}", symbolicName, e.getMessage());
        }
    }

    /**
     * 定位符号名对应的 jar 文件 URL。
     *
     * @param symbolicName bundle 符号名
     * @return jar 的 URL，定位失败时返回 {@code null}
     */
    private String locateJar(String symbolicName) {
        Optional<Path> direct = resolveDirect(symbolicName);
        if (direct.isPresent()) {
            return direct.get().toUri().toString();
        }
        File[] jars = directory.toFile().listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) {
            return null;
        }
        for (File jar : jars) {
            if (symbolicName.equals(readSymbolicName(jar))) {
                return jar.toURI().toString();
            }
        }
        return null;
    }

    /**
     * 尝试以「符号名.jar」直接定位。
     *
     * @param symbolicName bundle 符号名
     * @return 命中的文件，不存在时返回空
     */
    private Optional<Path> resolveDirect(String symbolicName) {
        Path candidate = directory.resolve(symbolicName + ".jar");
        return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /**
     * 计算 jar 内容指纹（最后修改时间 + 文件大小），用于快速判定内容是否变化。
     *
     * @param jar jar 文件
     * @return 指纹字符串
     */
    private String fingerprint(File jar) {
        return jar.lastModified() + ":" + jar.length();
    }

    /**
     * 读取 jar 清单中的符号名。
     *
     * @param jar jar 文件
     * @return 符号名，清单缺失或未声明时返回 {@code null}
     */
    private String readSymbolicName(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return null;
            }
            Attributes attributes = manifest.getMainAttributes();
            String symbolicName = attributes.getValue(MANIFEST_SYMBOLIC_NAME);
            if (symbolicName == null) {
                return null;
            }
            symbolicName = symbolicName.trim();
            // Bundle-SymbolicName 允许形如 "name;singleton:=true" 的指令后缀，需剥离
            int directiveIndex = symbolicName.indexOf(';');
            if (directiveIndex >= 0) {
                symbolicName = symbolicName.substring(0, directiveIndex);
            }
            return symbolicName.isEmpty() ? null : symbolicName;
        } catch (IOException e) {
            log.debug("[osgi-deploy] 读取清单失败 {}: {}", jar.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 获取部署目录。
     *
     * @return 部署目录
     */
    public Path getDirectory() {
        return directory;
    }
}
