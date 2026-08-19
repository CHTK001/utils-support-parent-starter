package com.chua.pandoc.support.converter;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.PackageManager;
import com.chua.common.support.lang.cmd.LineCallback;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Pandoc 环境检测与自动安装工具
 *
 * <p>检测当前系统中是否安装了 Pandoc，若未安装则根据操作系统自动下载并安装。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PandocEnvironment {

    /**
     * Pandoc 下载版本
     */
    private static final String PANDOC_VERSION = "3.6.4";

    /**
     * Pandoc GitHub 发布页面
     */
    private static final String GITHUB_RELEASE = "https://github.com/jgm/pandoc/releases/download/" + PANDOC_VERSION;

    /**
     * 检测是否安装 Pandoc 的缓存结果
     */
    private static Boolean pandocAvailable;

    /**
     * 缓存的 Pandoc 可执行文件路径
     */
    private static String pandocPath;

    /**
     * 获取 Pandoc 可执行文件路径
     *
     * <p>检测当前系统中是否存在 pandoc，如果不存在则尝试自动安装。</p>
     *
     * @return pandoc 可执行文件的路径
     */
    public static String getPandocPath() {
        if (pandocAvailable != null && pandocAvailable) {
            return pandocPath;
        }

        String found = findPandoc();
        if (found != null) {
            pandocAvailable = true;
            pandocPath = found;
            return found;
        }

        log.info("未检测到 Pandoc，尝试自动安装...");
        String installed = installPandoc();
        if (installed != null) {
            pandocAvailable = true;
            pandocPath = installed;
            return installed;
        }

        throw new IllegalStateException("Pandoc 未安装且自动安装失败，请查看上方日志中的手动安装指引");
    }

    /**
     * 在系统 PATH 中查找 pandoc
     *
     * @return pandoc 路径，未找到返回 null
     */
    private static String findPandoc() {
        String osName = System.getProperty("os.name").toLowerCase();
        String cmd = osName.contains("win") ? "where pandoc" : "which pandoc";

        try {
            CmdResult result = CmdExecutors.execute(cmd, 5, TimeUnit.SECONDS);
            if (result.isSuccess()) {
                String path = result.getStdout().trim().split("\\n")[0].trim();
                log.info("检测到 Pandoc: {}", path);
                return path;
            }
        } catch (Exception e) {
            log.debug("查找 pandoc 失败", e);
        }

        String homeDir = System.getProperty("user.home");
        String[] commonPaths;
        if (osName.contains("win")) {
            commonPaths = new String[]{
                homeDir + "\\AppData\\Local\\Pandoc\\pandoc.exe",
                "C:\\Program Files\\Pandoc\\pandoc.exe",
                "C:\\Program Files (x86)\\Pandoc\\pandoc.exe"
            };
        } else if (osName.contains("mac")) {
            commonPaths = new String[]{
                "/usr/local/bin/pandoc",
                "/opt/homebrew/bin/pandoc"
            };
        } else {
            commonPaths = new String[]{
                "/usr/local/bin/pandoc",
                "/usr/bin/pandoc"
            };
        }

        for (String path : commonPaths) {
            if (new File(path).exists()) {
                log.info("在常见路径检测到 Pandoc: {}", path);
                return path;
            }
        }

        return null;
    }

    /**
     * 自动安装 Pandoc
     *
     * <p>先通过包管理器安装，失败后回退到直接下载。</p>
     *
     * @return 安装后的 pandoc 路径，失败返回 null
     */
    private static String installPandoc() {
        try {
            log.info("检测系统包管理器...");
            java.util.List<PackageManager.Type> pms = CmdExecutors.detectPackageManagers();
            if (pms.isEmpty()) {
                log.warn("未检测到任何包管理器，尝试直接下载安装");
                return installFromDirectDownload();
            }
            PackageManager.Type pm = pms.get(0);
            log.info("使用 {} 安装 Pandoc...", pm.getCommand());
            CmdResult result = CmdExecutors.installPackageWith(pm, "pandoc");
            if (result.isSuccess()) {
                String path = findPandoc();
                if (path != null) {
                    log.info("Pandoc 安装成功: {}", path);
                    return path;
                }
            } else {
                log.warn("包管理器安装失败, exit={}", result.getExitCode());
                return installFromDirectDownload();
            }
        } catch (Exception e) {
            log.error("Pandoc 安装失败", e);
        }
        return null;
    }

    /**
     * 通过直接下载方式安装 Pandoc
     */
    private static String installFromDirectDownload() {
        String osName = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        try {
            if (osName.contains("win")) {
                return installWindows(arch);
            } else if (osName.contains("mac")) {
                return installMacOs();
            } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
                return installLinux(arch);
            }
        } catch (Exception e) {
            log.error("直接下载安装 Pandoc 失败", e);
        }
        return null;
    }

    /**
     * 在 Windows 上安装 Pandoc
     */
    private static String installWindows(String arch) throws Exception {
        String fileName = "pandoc-" + PANDOC_VERSION + "-windows-x86_64.msi";
        String downloadUrl = GITHUB_RELEASE + "/" + fileName;
        Path msiPath = Files.createTempFile("pandoc_", ".msi");

        try {
            log.info("下载 Pandoc MSI 安装包: {}", downloadUrl);
            downloadFile(downloadUrl, msiPath);

            log.info("执行 Pandoc 安装...");
            CmdResult result = CmdExecutors.execute(
                "msiexec /i \"" + msiPath.toAbsolutePath() + "\" /qn /norestart",
                120, TimeUnit.SECONDS
            );

            if (!result.isSuccess()) {
                log.error("Pandoc 安装失败, exit={}, error={}", result.getExitCode(), result.getStderr());
                printWindowsManualGuide(downloadUrl);
                return null;
            }

            String pandocExe = "C:\\Program Files\\Pandoc\\pandoc.exe";
            if (new File(pandocExe).exists()) {
                return pandocExe;
            }
            pandocExe = System.getProperty("user.home") + "\\AppData\\Local\\Pandoc\\pandoc.exe";
            if (new File(pandocExe).exists()) {
                return pandocExe;
            }
            return "pandoc";
        } catch (Exception e) {
            log.error("下载或安装 Pandoc 失败: {}", e.getMessage());
            printWindowsManualGuide(downloadUrl);
            throw e;
        } finally {
            Files.deleteIfExists(msiPath);
        }
    }

    /**
     * 打印 Windows 手动安装指引
     */
    private static void printWindowsManualGuide(String downloadUrl) {
        String homeDir = System.getProperty("user.home");
        String targetPath = homeDir + "\\AppData\\Local\\Pandoc";
        log.warn("========== Pandoc 手动安装指引 ==========");
        log.warn("下载地址: {}", downloadUrl);
        log.warn("方式1 (推荐): 运行 MSI 安装包，安装完成后 pandoc.exe 会在以下路径之一:");
        log.warn("  - C:\\Program Files\\Pandoc\\pandoc.exe");
        log.warn("  - {}\\AppData\\Local\\Pandoc\\pandoc.exe", homeDir);
        log.warn("方式2 (免安装): 解压 MSI (可用 7-Zip) 或下载 zip 版，将 pandoc.exe 放到任意目录后加入 PATH");
        log.warn("zip 下载: {}", GITHUB_RELEASE + "/pandoc-" + PANDOC_VERSION + "-windows-x86_64.zip");
        log.warn("==========================================");
    }

    /**
     * 在 macOS 上安装 Pandoc
     */
    private static String installMacOs() throws Exception {
        String fileName = "pandoc-" + PANDOC_VERSION + "-macOS.dmg";
        String downloadUrl = GITHUB_RELEASE + "/" + fileName;
        Path dmgPath = Files.createTempFile("pandoc_", ".dmg");

        try {
            log.info("下载 Pandoc DMG 安装包: {}", downloadUrl);
            downloadFile(downloadUrl, dmgPath);

            Path mountPoint = Files.createTempDirectory("pandoc_mount_");
            try {
                CmdExecutors.execute("hdiutil attach -mountpoint \"" + mountPoint.toAbsolutePath()
                    + "\" \"" + dmgPath.toAbsolutePath() + "\"", 30, TimeUnit.SECONDS);
                CmdExecutors.execute("cp -R \"" + mountPoint.toAbsolutePath() + "/*.pkg\" /tmp/pandoc.pkg", 30, TimeUnit.SECONDS);
                CmdExecutors.execute("installer -pkg /tmp/pandoc.pkg -target /", 120, TimeUnit.SECONDS);
                CmdExecutors.execute("hdiutil detach \"" + mountPoint.toAbsolutePath() + "\"", 30, TimeUnit.SECONDS);
            } finally {
                try { Files.deleteIfExists(mountPoint); } catch (Exception ignored) {}
            }

            String pandocBin = "/usr/local/bin/pandoc";
            if (new File(pandocBin).exists()) {
                return pandocBin;
            }
            return "pandoc";
        } catch (Exception e) {
            log.error("下载或安装 Pandoc 失败: {}", e.getMessage());
            printMacManualGuide(downloadUrl);
            throw e;
        } finally {
            Files.deleteIfExists(dmgPath);
        }
    }

    /**
     * 打印 macOS 手动安装指引
     */
    private static void printMacManualGuide(String downloadUrl) {
        log.warn("========== Pandoc 手动安装指引 ==========");
        log.warn("下载地址: {}", downloadUrl);
        log.warn("方式1 (推荐): 双击 DMG 安装包，按提示安装");
        log.warn("方式2 (命令行): brew install pandoc");
        log.warn("安装后 pandoc 可执行文件路径: /usr/local/bin/pandoc");
        log.warn("==========================================");
    }

    /**
     * 在 Linux 上安装 Pandoc
     */
    private static String installLinux(String arch) throws Exception {
        String archSuffix = arch.contains("64") ? "amd64" : "arm64";
        String fileName = "pandoc-" + PANDOC_VERSION + "-linux-" + archSuffix + ".tar.gz";
        String downloadUrl = GITHUB_RELEASE + "/" + fileName;
        Path tarPath = Files.createTempFile("pandoc_", ".tar.gz");

        try {
            log.info("下载 Pandoc: {}", downloadUrl);
            downloadFile(downloadUrl, tarPath);

            Path extractDir = Files.createTempDirectory("pandoc_extract_");
            try {
                try (InputStream fis = Files.newInputStream(tarPath);
                     GZIPInputStream gzis = new GZIPInputStream(fis)) {
                    Path tempTar = extractDir.resolve("pandoc.tar");
                    Files.copy(gzis, tempTar, StandardCopyOption.REPLACE_EXISTING);
                    CmdExecutors.execute("tar xf \"" + tempTar.toAbsolutePath() + "\" -C \""
                        + extractDir.toAbsolutePath() + "\"", 30, TimeUnit.SECONDS);
                }

                Path pandocBin = Files.find(extractDir, 5, (p, a) -> p.endsWith("bin/pandoc"))
                    .findFirst().orElse(null);

                if (pandocBin != null) {
                    Path targetPath = Path.of("/usr/local/bin/pandoc");
                    if (new File("/usr/local/bin").canWrite()) {
                        Files.copy(pandocBin, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        targetPath.toFile().setExecutable(true);
                        log.info("Pandoc 已安装到: {}", targetPath);
                        return targetPath.toString();
                    }
                    return pandocBin.toAbsolutePath().toString();
                }
            } finally {
                try { deleteDirectory(extractDir); } catch (Exception ignored) {}
            }

            return "pandoc";
        } catch (Exception e) {
            log.error("下载或安装 Pandoc 失败: {}", e.getMessage());
            printLinuxManualGuide(downloadUrl, archSuffix);
            throw e;
        } finally {
            Files.deleteIfExists(tarPath);
        }
    }

    /**
     * 打印 Linux 手动安装指引
     */
    private static void printLinuxManualGuide(String downloadUrl, String archSuffix) {
        log.warn("========== Pandoc 手动安装指引 ==========");
        log.warn("下载地址: {}", downloadUrl);
        log.warn("手动安装步骤:");
        log.warn("  1. 下载 tar.gz 文件");
        log.warn("  2. 解压: tar xf pandoc-{}-linux-{}.tar.gz", PANDOC_VERSION, archSuffix);
        log.warn("  3. 复制可执行文件: cp pandoc-*/bin/pandoc /usr/local/bin/pandoc");
        log.warn("  4. 添加执行权限: chmod +x /usr/local/bin/pandoc");
        log.warn("验证: pandoc --version");
        log.warn("==========================================");
    }

    /**
     * 打印通用手动安装指引 (不支持的操作系统)
     */
    private static void logManualInstallGuide(String osName, String arch) {
        log.warn("========== Pandoc 手动安装指引 ==========");
        log.warn("不支持的操作系统: {} ({})", osName, arch);
        log.warn("请访问官方下载页面: https://github.com/jgm/pandoc/releases/tag/{}", PANDOC_VERSION);
        log.warn("安装后确保 pandoc 在系统 PATH 中，或放在以下常见路径:");
        if (osName.contains("win")) {
            log.warn("  - C:\\Program Files\\Pandoc\\pandoc.exe");
            log.warn("  - {}\\AppData\\Local\\Pandoc\\pandoc.exe", System.getProperty("user.home"));
        } else {
            log.warn("  - /usr/local/bin/pandoc");
            log.warn("  - /usr/bin/pandoc");
        }
        log.warn("==========================================");
    }

    /**
     * 递归删除目录
     *
     * @param path 目录路径
     */
    private static void deleteDirectory(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var walk = Files.walk(path)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
            }
        }
    }

    /**
     * 从 URL 下载文件到本地，跳过 SSL 证书验证
     *
     * @param downloadUrl 下载地址
     * @param targetPath  目标文件路径
     */
    private static void downloadFile(String downloadUrl, Path targetPath) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

        URL url = new URL(downloadUrl);
        try (InputStream in = url.openStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
