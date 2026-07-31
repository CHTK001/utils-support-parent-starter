package com.chua.utils.support.appimage;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AppImage 打包器
 * <p>
 * 将 Spring Boot fat jar 与 JRE 打包为可执行的 AppImage 文件，
 * 参考 SmolvmPackager 的实现思路
 *
 * @author CH
 */
public class AppImagePackager {

    private static final Logger log = LoggerFactory.getLogger(AppImagePackager.class);

    private final AppImageProperties properties;
    private final AppImageInstaller installer;

    public AppImagePackager(AppImageProperties properties, AppImageInstaller installer) {
        this.properties = properties;
        this.installer = installer;
    }

    /**
     * 将 Spring Boot fat jar 打包为 AppImage
     *
     * @param fatJar Spring Boot 打包后的 jar 文件
     * @return 生成的 .AppImage 文件
     */
    public File packageAppImage(File fatJar) throws IOException, InterruptedException {
        if (!fatJar.exists() || !fatJar.isFile()) {
            throw new IOException("fat jar 文件不存在: " + fatJar.getAbsolutePath());
        }

        String appName = properties.getAppName();
        if (appName == null || appName.isBlank()) {
            appName = fatJar.getName().replace(".jar", "");
            properties.setAppName(appName);
        }
        log.info("开始打包 AppImage，fatJar: {}，应用名称: {}", fatJar.getName(), appName);

        Path appDir = createAppDir(appName);

        try {
            Path jarDest = appDir.resolve("usr/lib/app.jar");
            Files.copy(fatJar.toPath(), jarDest, StandardCopyOption.REPLACE_EXISTING);

            Path jreDest = appDir.resolve("usr/lib/jre");
            copyOrGenerateJre(jreDest);

            generateAppRun(appDir, appName);

            copyIcon(appDir);

            generateDesktopFile(appDir, appName);

            File outputFile = runAppimagetool(appDir, appName);

            log.info("AppImage 打包完成: {}", outputFile.getAbsolutePath());
            return outputFile;
        }
        finally {
            cleanupAppDir(appDir);
        }
    }

    /**
     * 创建临时 AppDir 目录
     */
    private Path createAppDir(String appName) throws IOException {
        Path appDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "appimage-" + appName + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(appDir);
        log.debug("创建临时 AppDir: {}", appDir);
        return appDir;
    }

    /**
     * 复制或生成 JRE 到 AppDir
     */
    private void copyOrGenerateJre(Path jreDest) throws IOException, InterruptedException {
        File jreDir = installer.getJreDirectory();
        if (jreDir != null) {
            log.info("复制已有 JRE: {}", jreDir.getAbsolutePath());
            copyDirectory(jreDir.toPath(), jreDest);
            return;
        }

        log.info("使用 jlink 裁剪 JRE");
        String javaHome = System.getProperty("java.home");
        Path jlinkPath = Paths.get(javaHome, "bin", isWindows() ? "jlink.exe" : "jlink");

        if (!Files.exists(jlinkPath)) {
            throw new IOException("jlink 工具未找到，请确保 JDK 环境正确: " +
                    jlinkPath);
        }

        String modules = String.join(",", properties.getJlinkModules());
        ProcessBuilder pb = new ProcessBuilder(jlinkPath.toString(),
                "--module-path", Paths.get(javaHome, "jmods").toString(),
                "--add-modules", modules,
                "--no-header-files",
                "--no-man-pages",
                "--compress=2",
                "--strip-debug",
                "--output", jreDest.toString());
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("jlink 执行失败，退出码: " + exitCode);
        }
        log.info("jlink JRE 已生成: {}", jreDest);
    }

    /**
     * 生成 AppRun 启动脚本
     */
    private void generateAppRun(Path appDir, String appName) throws IOException {
        Path appRunPath = appDir.resolve("AppRun");

        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("APPDIR=\"$(dirname \"$(readlink -f \"$0\")\")\"\n");
        sb.append("cd \"$APPDIR\"\n");

        sb.append("\n");
        sb.append("# 设置环境变量\n");
        sb.append("export PATH=\"$APPDIR/usr/lib/jre/bin:$PATH\"\n");
        sb.append("\n");

        if (properties.getEnvironment() != null) {
            for (var entry : properties.getEnvironment().entrySet()) {
                sb.append("export ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"\n");
            }
            sb.append("\n");
        }

        sb.append("exec $APPDIR/usr/lib/jre/bin/java \\\n");
        if (properties.getJvmArgs() != null && !properties.getJvmArgs().isEmpty()) {
            for (String arg : properties.getJvmArgs()) {
                sb.append("  ").append(arg).append(" \\\n");
            }
        }
        sb.append("  -jar $APPDIR/usr/lib/app.jar");
        if (properties.getAppArgs() != null && !properties.getAppArgs().isEmpty()) {
            sb.append(" \\\n");
            for (String arg : properties.getAppArgs()) {
                sb.append("  ").append(arg).append(" \\\n");
            }
        }
        sb.append("\n");

        Files.writeString(appRunPath, sb.toString(), StandardCharsets.UTF_8);
        makeExecutable(appRunPath.toFile());
        log.debug("AppRun 脚本已生成: {}", appRunPath);
    }

    /**
     * 复制图标到 AppDir
     */
    private void copyIcon(Path appDir) throws IOException {
        File iconFile = installer.getIconFile();
        if (iconFile != null) {
            String iconName = iconFile.getName();
            Path dest = appDir.resolve(iconName);
            Files.copy(iconFile.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            log.debug("图标已复制: {}", dest);
        }
    }

    /**
     * 生成 .desktop 快捷方式文件
     */
    private void generateDesktopFile(Path appDir, String appName) throws IOException {
        String displayName = properties.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = appName;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Desktop Entry]\n");
        sb.append("Type=Application\n");
        sb.append("Name=").append(displayName).append("\n");
        sb.append("Exec=AppRun\n");
        sb.append("Terminal=false\n");
        sb.append("Categories=Utility;\n");
        sb.append("\n");

        Path desktopFile = appDir.resolve(appName + ".desktop");
        Files.writeString(desktopFile, sb.toString(), StandardCharsets.UTF_8);
        log.debug("desktop 文件已生成: {}", desktopFile);
    }

    /**
     * 使用 appimagetool 打包
     */
    private File runAppimagetool(Path appDir, String appName) throws IOException, InterruptedException {
        String appimagetoolPath = installer.getAppimagetoolPath();

        File outputDir = new File(properties.getOutputDir());
        if (!outputDir.exists()) {
            Files.createDirectories(outputDir.toPath());
        }

        File outputFile = new File(outputDir, appName + "-" + getVersion() + ".AppImage");

        if (outputFile.exists()) {
            if (properties.getOverwrite() != null && properties.getOverwrite()) {
                Files.delete(outputFile.toPath());
            } else {
                log.warn("输出文件已存在，跳过打包: {}", outputFile.getAbsolutePath());
                return outputFile;
            }
        }

        ProcessBuilder pb = new ProcessBuilder(appimagetoolPath,
                appDir.toString(),
                outputFile.getAbsolutePath());
        pb.inheritIO();

        pb.environment().putIfAbsent("ARCH", "x86_64");

        log.info("正在执行 appimagetool 打包");
        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("appimagetool 执行失败，退出码: " + exitCode);
        }

        if (!outputFile.exists()) {
            throw new IOException("输出文件未生成: " + outputFile.getAbsolutePath());
        }

        makeExecutable(outputFile);
        return outputFile;
    }

    /**
     * 递归复制目录
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(src -> {
            try {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    if (!Files.exists(dest)) {
                        Files.createDirectories(dest);
                    }
                } else {
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * 清理临时 AppDir
     */
    private void cleanupAppDir(Path appDir) {
        try {
            deleteRecursively(appDir);
            log.debug("临时目录已清理: {}", appDir);
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", appDir, e);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.collect(Collectors.toList())) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private void makeExecutable(File file) throws IOException {
        if (!file.setExecutable(true, false)) {
            log.warn("设置可执行权限失败: {}", file.getAbsolutePath());
        }
        try {
            Set<PosixFilePermission> perms = Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows 平台不支持 POSIX 权限
        }
    }

    private String getVersion() {
        return "1.0.0";
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}