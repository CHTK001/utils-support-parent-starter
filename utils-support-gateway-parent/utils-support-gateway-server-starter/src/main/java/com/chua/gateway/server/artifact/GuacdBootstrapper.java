package com.chua.gateway.server.artifact;

import com.chua.gateway.server.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * guacd 一键引导器（核心：用户首次启动时自动获取 + 启动 guacd 子进程）。
 *
 * <p>查找顺序：</p>
 * <ol>
 *   <li>local-override 已存在 guacd → 直接使用</li>
 *   <li>从 classpath 加载 zip（utils-support-guacd-resource-starter.jar 内嵌）→ 解压</li>
 *   <li>Linux 包管理器尝试（apt / yum / dnf）→ 系统命令安装</li>
 *   <li>全部失败 → 警告，但不阻塞 gateway 启动（纯 SSH / WS 协议仍可用）</li>
 * </ol>
 *
 * <p>找到 guacd 后调用 {@link #start()} 启动子进程，
 * 注册 JVM shutdown hook 关闭 guacd。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class GuacdBootstrapper {

    /**
     * guacd 默认版本（与 zip 内部版本一致）
     */
    public static final String DEFAULT_VERSION = "1.5.5";

    /**
     * macOS / Linux / Windows 平台可执行文件名
     */
    private static final String EXE_LINUX = "guacd";
    private static final String EXE_MAC = "guacd";
    private static final String EXE_WIN = "guacd.exe";

    /**
     * classpath zip 资源（windows）
     */
    public static final String WIN_RESOURCE = "META-INF/resources/native/windows-x86_64/guacd-windows-x86_64.zip";

    /**
     * classpath zip 资源（linux）
     */
    public static final String LINUX_RESOURCE = "META-INF/resources/native/linux-x86_64/guacd-linux-x86_64.zip";

    /**
     * Linux 包管理器安装命令（按优先级）
     */
    private static final List<String[]> LINUX_INSTALL_CMDS = Arrays.asList(
            new String[]{"apt-get", "install", "-y", "guacd"},   // Debian/Ubuntu
            new String[]{"yum", "install", "-y", "guacd"},         // CentOS/RHEL
            new String[]{"dnf", "install", "-y", "guacd"},         // Fedora 22+
            new String[]{"pacman", "-S", "--noconfirm", "guacd"}, // Arch
            new String[]{"zypper", "install", "-y", "guacd"}       // openSUSE
    );

    /**
     * guacd 启动参数（监听 0.0.0.0:<port>，不绑定主机名强制）
     */
    private static final String[] GUACD_ARGS = {"-b", "0.0.0.0", "-p", "%d", "-l", "4822"};

    /**
     * guacd 启动超时（毫秒）
     */
    private static final long GUACD_READY_TIMEOUT_MS = 15000L;

    /**
     * 私有构造
     */
    private GuacdBootstrapper() {
    }

    /**
     * 一键引导并启动 guacd（如可启动）。
     *
     * @return GuacdHandle 句柄（包含进程 + 文件路径 + 启动方式）
     *         如果启动失败返回 null（gateway 仍可启动纯 SSH / WS 协议）
     */
    public static GuacdHandle bootstrapAndStart() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = os.contains("windows");
        boolean isMac = os.contains("mac");
        boolean isLinux = !isWindows && !isMac && (os.contains("linux") || os.contains("nix"));

        log.info("[guacd-bootstrapper] OS 探测: {}", os);
        log.info("[guacd-bootstrapper] 本地优先级: local-override → classpath jar → package manager");

        Path guacdBin = null;
        String source = null;

        // 1. local-override 已有 guacd？
        guacdBin = findGuacdInLocalOverride();
        if (guacdBin != null) {
            source = "local-override";
            log.info("[guacd-bootstrapper] ✓ 命中 local-override: {}", guacdBin);
        }

        // 2. classpath 内嵌 zip？
        if (guacdBin == null) {
            try {
                guacdBin = extractFromClasspath(isWindows, isLinux);
                if (guacdBin != null) {
                    source = "classpath-zip";
                    log.info("[guacd-bootstrapper] ✓ 从 classpath 解压: {}", guacdBin);
                }
            } catch (IOException ex) {
                log.warn("[guacd-bootstrapper] classpath 解压失败: {}", ex.getMessage());
            }
        }

        // 3. Linux 包管理器安装
        if (guacdBin == null && isLinux) {
            log.info("[guacd-bootstrapper] 尝试 Linux 包管理器安装 guacd...");
            try {
                boolean installed = tryLinuxPackageManager();
                if (installed) {
                    guacdBin = findGuacdInLocalOverride(); // 重查 guacd 路径
                    if (guacdBin != null) {
                        source = "linux-pkg-manager";
                        log.info("[guacd-bootstrapper] ✓ 包管理器安装: {}", guacdBin);
                    }
                }
            } catch (Exception ex) {
                log.warn("[guacd-bootstrapper] 包管理器安装异常: {}", ex.getMessage());
            }
        }

        // 4. 兜底：Linux 常见系统路径
        if (guacdBin == null && (isLinux || isMac)) {
            for (String p : new String[]{"/usr/sbin/guacd", "/usr/local/sbin/guacd", "/opt/guacamole/sbin/guacd"}) {
                if (Files.exists(Paths.get(p))) {
                    guacdBin = Paths.get(p);
                    source = "system-path";
                    log.info("[guacd-bootstrapper] ✓ 命中系统路径: {}", guacdBin);
                    break;
                }
            }
        }

        // 5. 全部失败
        if (guacdBin == null) {
            log.warn("[guacd-bootstrapper] ✗ 未找到 guacd。RDP/VNC 协议不可用，但 SSH / WebSocket 仍可用。");
            log.warn("[guacd-bootstrapper] 提示:");
            log.warn("[guacd-bootstrapper]   - Linux:  apt-get install guacd  /  yum install guacd");
            log.warn("[guacd-bootstrapper]   - Windows: 准备 guacd-windows-x86_64.zip 放到");
            log.warn("[guacd-bootstrapper]           {}/guacd/{}/sbin/guacd.exe", GatewayProperties.localOverrideDir(), DEFAULT_VERSION);
            return null;
        }

        // 6. 启动 guacd 子进程
        try {
            return startGuacd(guacdBin, source);
        } catch (IOException ex) {
            log.error("[guacd-bootstrapper] 启动失败: {}", ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * 在 local-override 查找 guacd 可执行文件。
     */
    private static Path findGuacdInLocalOverride() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = os.contains("windows");
        Path installDir = Paths.get(LocalOverrideResolver.expandUserHome(GatewayProperties.localOverrideDir()),
                "guacd", DEFAULT_VERSION);
        Path guacdBin = installDir.resolve("sbin").resolve(isWindows ? EXE_WIN : EXE_LINUX);
        if (Files.isRegularFile(guacdBin)) {
            return guacdBin;
        }
        return null;
    }

    /**
     * 从 classpath 内嵌 zip 资源解压。
     */
    private static Path extractFromClasspath(boolean isWindows, boolean isLinux) throws IOException {
        String resourcePath;
        if (isWindows) {
            resourcePath = WIN_RESOURCE;
        } else if (isLinux) {
            resourcePath = LINUX_RESOURCE;
        } else {
            return null;
        }
        URL zipUrl = GuacdBootstrapper.class.getClassLoader().getResource(resourcePath);
        if (zipUrl == null) {
            log.debug("[guacd-bootstrapper] classpath 找不到 zip: {}", resourcePath);
            return null;
        }

        Path targetDir = Paths.get(LocalOverrideResolver.expandUserHome(GatewayProperties.localOverrideDir()),
                "guacd", DEFAULT_VERSION);
        Files.createDirectories(targetDir);
        log.info("[guacd-bootstrapper] 从 classpath 解压 {} → {}", zipUrl, targetDir);
        int count;
        try (InputStream in = zipUrl.openStream();
             ZipInputStream zis = new ZipInputStream(in)) {
            count = extractZip(zis, targetDir);
        }
        log.info("[guacd-bootstrapper] 解压 {} 个条目", count);

        boolean isWin = isWindows;
        Path bin = targetDir.resolve("sbin").resolve(isWin ? EXE_WIN : EXE_LINUX);
        return Files.isRegularFile(bin) ? bin : null;
    }

    /**
     * 尝试 Linux 包管理器安装 guacd。
     *
     * @return 是否安装成功
     */
    private static boolean tryLinuxPackageManager() {
        for (String[] cmd : LINUX_INSTALL_CMDS) {
            try {
                log.info("[guacd-bootstrapper] 尝试: {} {}", String.join(" ", cmd), "(可能需要 sudo)");
                ProcessBuilder pb = new ProcessBuilder(cmd);
                // 某些系统需要 sudo，优先尝试不加 sudo
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean done = p.waitFor(180, TimeUnit.SECONDS);
                int exit = p.exitValue();
                if (done && exit == 0) {
                    log.info("[guacd-bootstrapper] ✓ 安装成功: {}", String.join(" ", cmd));
                    return true;
                }
                log.warn("[guacd-bootstrapper] 安装退出码 {}: {}", exit, String.join(" ", cmd));
            } catch (Exception ex) {
                log.debug("[guacd-bootstrapper] {} 不可用: {}", cmd[0], ex.getMessage());
            }
        }
        log.warn("[guacd-bootstrapper] 所有包管理器安装尝试失败");
        return false;
    }

    /**
     * 启动 guacd 子进程。
     */
    private static GuacdHandle startGuacd(Path guacdBin, String source) throws IOException {
        int port = com.chua.gateway.server.config.GatewayProperties.guacdPort();
        List<String> cmd = new ArrayList<>();
        cmd.add(guacdBin.toString());
        cmd.add("-b"); cmd.add("0.0.0.0");
        cmd.add("-p"); cmd.add(String.valueOf(port));
        cmd.add("-l"); cmd.add("4822");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(guacdBin.getParent().getParent().toFile());
        pb.inheritIO();  // 把 guacd stdout/stderr 透传到 gateway stdout
        // guacd 是 C 进程不能用 ProcessHandle 直接看 alive；
        // 用 isAlive() 周期性检查
        log.info("[guacd-bootstrapper] 启动 guacd: {} (port={})", String.join(" ", cmd), port);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException ex) {
            log.error("[guacd-bootstrapper] 启动失败: {}", ex.getMessage(), ex);
            throw ex;
        }

        // 等 guacd 监听端口
        long deadline = System.currentTimeMillis() + GUACD_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) {
                int exit = proc.exitValue();
                log.error("[guacd-bootstrapper] guacd 立即退出 exit={}", exit);
                return null;
            }
            if (isPortListening(port)) {
                log.info("[guacd-bootstrapper] ✓ guacd 已监听 :{}", port);
                break;
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
        }

        // 注册 shutdown hook：JVM 退出时关闭 guacd
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[guacd-bootstrapper] shutdown hook: destroy guacd pid={}", proc.pid());
            try {
                proc.destroy();
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (Exception ex) {
                log.warn("[guacd-bootstrapper] destroy 失败: {}", ex.getMessage());
            }
        }, "guacd-shutdown"));

        return new GuacdHandle(proc, guacdBin, port, source);
    }

    /**
     * 检测本地端口是否在监听。
     */
    private static boolean isPortListening(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 解压 zip 流到目标目录（带路径穿越防护）。
     */
    private static int extractZip(ZipInputStream zis, Path targetDir) throws IOException {
        int count = 0;
        ZipEntry entry;
        byte[] buf = new byte[8192];
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if (name.contains("..")) {
                log.warn("[guacd-bootstrapper] 跳过非法路径: {}", name);
                zis.closeEntry();
                continue;
            }
            Path out = targetDir.resolve(name).normalize();
            if (!out.startsWith(targetDir)) {
                log.warn("[guacd-bootstrapper] 跳过路径穿越: {}", name);
                zis.closeEntry();
                continue;
            }
            if (entry.isDirectory()) {
                Files.createDirectories(out);
            } else {
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (OutputStream os = Files.newOutputStream(out,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                // Windows 上 guacd.exe 需要可执行权限
                out.toFile().setExecutable(true, false);
            }
            zis.closeEntry();
            count++;
        }
        return count;
    }

    /**
     * guacd 启动结果句柄。
     */
    public static final class GuacdHandle {
        private final Process process;
        private final Path binaryPath;
        private final int port;
        private final String source;

        GuacdHandle(Process process, Path binaryPath, int port, String source) {
            this.process = process;
            this.binaryPath = binaryPath;
            this.port = port;
            this.source = source;
        }

        public Process process() { return process; }
        public Path binaryPath() { return binaryPath; }
        public int port() { return port; }
        public String source() { return source; }
    }
}
