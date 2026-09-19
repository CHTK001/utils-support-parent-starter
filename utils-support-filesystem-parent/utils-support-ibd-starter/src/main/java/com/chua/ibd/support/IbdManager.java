package com.chua.ibd.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IBD（智能业务数据）管理器
 *
 * <p>统一管理 IBD 资源的解压、脚本执行和数据处理。
 * 启动时自动从 JAR 中解压内置资源到工作目录。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   IbdManager ibd = IbdManager.create("ibd");
 *   ibd.init();  // 自动解压资源
 *
 *   // 执行 Python 脚本
 *   String result = ibd.executeScript("process.py", Map.of("input", data));
 *
 *   // 获取资源路径
 *   Path resource = ibd.getResource("config.json");
 * }</pre>= ibd.getResource("config.json");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class IbdManager {

    /**
     * 资源路径前缀（JAR 内）
     */
    private final String resourcePrefix;

    /**
     * 工作目录（解压目标）
     */
    private final Path workDir;

    /**
     * 已解压的资源缓存
     */
    private final Map<String, Path> extractedResources = new ConcurrentHashMap<>();

    /**
     * 脚本提供者
     */
    private ScriptExecutor scriptExecutor;

    /**
     * 创建 IBD 管理器
     *
     * @param resourcePrefix JAR 内资源路径前缀（如 "ibd"）
     */
    public IbdManager(String resourcePrefix) {
        this.resourcePrefix = resourcePrefix;
        this.workDir = Path.of(System.getProperty("java.io.tmpdir"), "ibd-" + resourcePrefix);
    }

    /**
     * 使用指定工作目录创建
     * @param resourcePrefix resource前缀
     * @param workDir workdir
     */
    public IbdManager(String resourcePrefix, Path workDir) {
        this.resourcePrefix = resourcePrefix;
        this.workDir = workDir;
    }

    /**
     * 创建 IBD 管理器（便捷方法）
     * @param resourcePrefix resource前缀
     * @return 创建的结果
     */
    public static IbdManager create(String resourcePrefix) {
        return new IbdManager(resourcePrefix);
    }

    /**
     * 初始化：解压 JAR 内资源到工作目录
     *
     * @throws IOException 解压失败时抛出
     */
    public void init() throws IOException {
        Files.createDirectories(workDir);
        extractResources();
    }

    /**
     * 获取资源文件路径
     *
     * @param resourceName 资源名称（如 "配置.json"、"script/处理.py"）
     * @return 资源文件路径
     */
    public Path getResource(String resourceName) {
        return workDir.resolve(resourceName);
    }

    /**
     * 获取所有已解压的资源
     * @return 获取extractedresources的结果
     */
    public Map<String, Path> getExtractedResources() {
        return extractedResources;
    }

    /**
     * 获取工作目录
     * @return 获取workdir的结果
     */
    public Path getWorkDir() {
        return workDir;
    }

    /**
     * 设置脚本执行器
     * @param executor 执行器
     */
    public void setScriptExecutor(ScriptExecutor executor) {
        this.scriptExecutor = executor;
    }

    /**
     * 执行脚本
     *
     * @param scriptName 脚本名称（如 "处理.py"）
     * @param context    上下文参数
     * @return 脚本执行结果
     */
    public String executeScript(String scriptName, Map<String, Object> context) {
        if (scriptExecutor == null) {
            throw new IllegalStateException("未配置脚本执行器，请调用 setScriptExecutor()");
        }
        Path scriptPath = getResource(scriptName);
        return scriptExecutor.execute(scriptPath, context);
    }

    /**
     * 从 JAR 内解压资源
     */
    private void extractResources() throws IOException {
        String classPath = getClass().getProtectionDomain()
                .getCodeSource().getLocation().getPath();

        // 尝试从 JAR 文件中扫描资源
        if (classPath.endsWith(".jar")) {
            extractFromJar(classPath);
        } else {
 // 开发模式：从 类路径 目录复制
            extractFromClasspath(classPath);
        }
    }

    /**
     * extract从jar
     *
     * @param jarPath jar路径
     */
    private void extractFromJar(String jarPath) throws IOException {
        Path jar = Path.of(jarPath);
        try (var fs = FileSystems.newFileSystem(jar)) {
            Path root = fs.getPath("/", resourcePrefix);
            if (!Files.exists(root)) {
                return;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                /** visit文件 */
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path target = workDir.resolve(root.relativize(file).toString());
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        extractedResources.put(root.relativize(file).toString(), target);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * extract从类路径
     *
     * @param classPath 类路径
     */
    private void extractFromClasspath(String classPath) {
 // 类路径 目录模式下，资源已在原位，无需解压
    }
}
