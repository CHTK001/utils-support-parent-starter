package com.chua.utils.support.appimage;






import com.chua.utils.support.appimage.exception.AppImageException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * AppImage 安装管理器
 * <p>
 * 支持从多种格式安装软件到本地系统：
 * - 直接安装 .AppImage 文件
 * - 解压安装 .tar.gz、.zip、.tar 压缩包
 * - 复制可执行文件
 *
 * @author CH
 */
@Slf4j
public class AppImageInstaller {

    private final AppImageProperties properties;

    public AppImageInstaller(AppImageProperties properties) {
        this.properties = properties;
    }

    /**
     * 安装软件包到指定目录
     *
     * @param softwareFile 软件包文件，支持 AppImage、tar.gz、zip、tar 格式
     * @param installDir   安装目标目录
     * @return 安装后主执行文件路径
     */
    public String installSoftware(File softwareFile, String installDir) throws IOException {
        if (softwareFile == null || !softwareFile.exists()) {
            throw new AppImageException("软件包文件不存在: " + softwareFile);
        }

        Path installPath = Paths.get(installDir);
        if (!Files.exists(installPath)) {
            Files.createDirectories(installPath);
        }

        String fileName = softwareFile.getName().toLowerCase();

        if (fileName.endsWith(".AppImage") || fileName.endsWith(".appimage")) {
            return installAppImage(softwareFile, installDir);
        } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            return installTarGz(softwareFile, installDir);
        } else if (fileName.endsWith(".zip")) {
            return installZip(softwareFile, installDir);
        } else if (fileName.endsWith(".tar")) {
            return installTar(softwareFile, installDir);
        } else if (isExecutable(softwareFile)) {
            return installExecutable(softwareFile, installDir);
        } else {
            throw new AppImageException("不支持的软件包格式: " + fileName);
        }
    }

    /**
     * 安装 AppImage 文件
     */
    private String installAppImage(File appImageFile, String installDir) throws IOException {
        log.info("安装 AppImage: {}", appImageFile.getName());

        Path targetPath = Paths.get(installDir, appImageFile.getName());

        Files.copy(appImageFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        setExecutablePermission(targetPath);

        log.info("AppImage 安装完成: {}", targetPath);
        return targetPath.toString();
    }

    /**
     * 解压安装 tar.gz 压缩包
     */
    private String installTarGz(File tarGzFile, String installDir) throws IOException {
        log.info("解压 tar.gz: {}", tarGzFile.getName());

        try (InputStream fi = new FileInputStream(tarGzFile);
             InputStream gzi = new GZIPInputStream(fi);
             ArchiveInputStream ai = new TarArchiveInputStream(gzi)) {

            return extractArchive(ai, installDir);
        }
    }

    /**
     * 解压安装 zip 压缩包
     */
    private String installZip(File zipFile, String installDir) throws IOException {
        log.info("解压 zip: {}", zipFile.getName());

        try (InputStream fi = new FileInputStream(zipFile);
             ArchiveInputStream ai = new ZipArchiveInputStream(fi)) {

            return extractArchive(ai, installDir);
        }
    }

    /**
     * 解压安装 tar 压缩包
     */
    private String installTar(File tarFile, String installDir) throws IOException {
        log.info("解压 tar: {}", tarFile.getName());

        try (InputStream fi = new FileInputStream(tarFile);
             ArchiveInputStream ai = new TarArchiveInputStream(fi)) {

            return extractArchive(ai, installDir);
        }
    }

    /**
     * 安装可执行文件
     */
    private String installExecutable(File executableFile, String installDir) throws IOException {
        log.info("安装可执行文件: {}", executableFile.getName());

        Path targetPath = Paths.get(installDir, executableFile.getName());

        Files.copy(executableFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        setExecutablePermission(targetPath);

        log.info("可执行文件安装完成: {}", targetPath);
        return targetPath.toString();
    }

    /**
     * 从归档流中解压文件
     */
    private String extractArchive(ArchiveInputStream ai, String installDir) throws IOException {
        ArchiveEntry entry;
        String mainExecutable = null;

        while ((entry = ai.getNextEntry()) != null) {
            if (!ai.canReadEntryData(entry)) {
                continue;
            }

            Path targetPath = Paths.get(installDir, entry.getName());

            if (entry.isDirectory()) {
                Files.createDirectories(targetPath);
            } else {
                Path parent = targetPath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }

                try (OutputStream out = Files.newOutputStream(targetPath)) {
                    IOUtils.copy(ai, out);
                }

                if (isExecutable(targetPath.toFile())) {
                    setExecutablePermission(targetPath);
                    if (mainExecutable == null) {
                        mainExecutable = targetPath.toString();
                    }
                }
            }
        }

        if (mainExecutable == null) {
            mainExecutable = findMainExecutable(installDir);
        }

        log.info("归档解压完成，主可执行文件: {}", mainExecutable);
        return mainExecutable;
    }

    /**
     * 在安装目录中查找可执行文件
     */
    private String findMainExecutable(String installDir) {
        try {
            Files.walk(Paths.get(installDir))
                  .filter(Files::isRegularFile)
                  .filter(p -> isExecutable(p.toFile()))
                  .findFirst()
                  .ifPresent(p -> log.info("发现可执行文件: {}", p));
        } catch (IOException e) {
            log.warn("查找可执行文件失败", e);
        }
        return null;
    }

    /**
     * 判断文件是否为可执行类型
     */
    private boolean isExecutable(File file) {
        if (!file.exists()) {
            return false;
        }

        String name = file.getName().toLowerCase();
        if (name.endsWith(".sh") || name.endsWith(".bin") || name.endsWith(".run") ||
            name.endsWith(".AppImage") || name.endsWith(".appimage")) {
            return true;
        }

        if (file.canExecute()) {
            return true;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith("#!")) {
                return true;
            }
        } catch (IOException e) {
            // 忽略读取异常
        }

        return false;
    }

    /**
     * 设置文件可执行权限
     */
    private void setExecutablePermission(Path path) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);

            Files.setPosixFilePermissions(path, perms);
        } catch (IOException e) {
            log.warn("设置 POSIX 权限失败: {}", path, e);
            try {
                ProcessBuilder pb = new ProcessBuilder("chmod", "+x", path.toString());
                pb.start();
            } catch (IOException ex) {
                log.warn("chmod 执行失败", ex);
            }
        }
    }

    /**
     * 获取 appimagetool 路径
     */
    public String getAppimagetoolPath() {
        String cacheDir = properties.getCacheDir();
        if (cacheDir != null && !cacheDir.isBlank()) {
            Path cached = Paths.get(cacheDir, "appimagetool");
            if (Files.exists(cached)) {
                setExecutablePermission(cached);
                return cached.toAbsolutePath().toString();
            }
        }
        return "appimagetool";
    }

    /**
     * 获取 JRE 目录
     */
    public File getJreDirectory() {
        String jrePath = properties.getJrePath();
        if (jrePath == null || jrePath.isBlank()) {
            jrePath = properties.getDefaultConfig() == null ? null : properties.getDefaultConfig().getJrePath();
        }
        if (jrePath == null || jrePath.isBlank()) {
            return null;
        }
        File file = new File(jrePath);
        return file.isDirectory() ? file : null;
    }

    /**
     * 获取图标文件
     */
    public File getIconFile() {
        String iconPath = properties.getIconPath();
        if (iconPath == null || iconPath.isBlank()) {
            iconPath = properties.getDefaultConfig() == null ? null : properties.getDefaultConfig().getIconPath();
        }
        if (iconPath == null || iconPath.isBlank()) {
            return null;
        }
        File file = new File(iconPath);
        return file.isFile() ? file : null;
    }

    /**
     * 获取已安装的软件列表
     */
    public List<String> getInstalledSoftware(String installDir) {
        List<String> softwareList = new ArrayList<>();
        Path dir = Paths.get(installDir);

        if (!Files.exists(dir)) {
            return softwareList;
        }

        try {
            Files.walk(dir, 1)
                 .filter(Files::isRegularFile)
                 .filter(p -> isExecutable(p.toFile()) || p.toString().endsWith(".AppImage"))
                 .forEach(p -> softwareList.add(p.getFileName().toString()));
        } catch (IOException e) {
            log.error("获取已安装软件列表失败", e);
        }

        return softwareList;
    }

    /**
     * 卸载软件
     *
     * @param softwareName 软件名称
     * @param installDir   安装目录
     */
    public void uninstallSoftware(String softwareName, String installDir) throws IOException {
        log.info("卸载软件: {}", softwareName);

        Path softwarePath = Paths.get(installDir, softwareName);
        if (!Files.exists(softwarePath)) {
            throw new AppImageException("软件未安装: " + softwareName);
        }

        if (Files.isDirectory(softwarePath)) {
            deleteDirectory(softwarePath);
        } else {
            Files.deleteIfExists(softwarePath);
        }

        log.info("软件已卸载: {}", softwareName);
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        Files.walk(dir)
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try {
                     Files.deleteIfExists(p);
                 } catch (IOException e) {
                     log.warn("删除文件失败: {}", p, e);
                 }
             });
    }
}