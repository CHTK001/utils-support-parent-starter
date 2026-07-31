package com.chua.remote.support.agent.rustdesk;

import com.chua.common.support.network.download.Downloader;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RustDesk Agent 服务 — 通过进程包裹方式管理 RustDesk 的完整生命周期。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>下载/缓存可执行文件（支持本地路径优先，URL 下载备选）</li>
 *   <li>生成 RustDesk 配置文件（rendezvous 服务器地址、密钥、临时密码）</li>
 *   <li>启动 RustDesk headless 服务进程</li>
 *   <li>从 stdout 解析 RustDesk ID</li>
 *   <li>定时密码轮换（默认 30 分钟）</li>
 *   <li>进程健康检查 + 异常退出自动重启</li>
 *   <li>将 RustDesk ID / 密码 / 服务器信息作为 capabilities 上报 Gateway</li>
 * </ul>
 *
 * <h3>进程生命周期</h3>
 * <pre>
 * handleConnect  → startProcess() + 等待 ID 生成 → 上报 capability
 * handleDisconnect → 不停止进程（多客户端共享同一 RustDesk 实例）
 * onAgentDisconnect → stopProcess() 清理资源
 * </pre>
 *
 * @author CH
 */
@Slf4j
public class RustDeskAgentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ===== 版本注册表 (version -> {url, md5}) =====
    private static final Map<String, VersionInfo> VERSION_REGISTRY = new LinkedHashMap<>();
    static {
        // Linux x86_64 AppImage
        VERSION_REGISTRY.put("1.4.7-linux-x86_64", new VersionInfo(
                "https://github.com/rustdesk/rustdesk/releases/download/1.4.7/rustdesk-1.4.7-x86_64.AppImage",
                null
        ));
        // Windows x86_64
        VERSION_REGISTRY.put("1.4.7-windows-x86_64", new VersionInfo(
                "https://github.com/rustdesk/rustdesk/releases/download/1.4.7/rustdesk-1.4.7-x86_64.exe",
                null
        ));
        // Linux aarch64 AppImage
        VERSION_REGISTRY.put("1.4.7-linux-aarch64", new VersionInfo(
                "https://github.com/rustdesk/rustdesk/releases/download/1.4.7/rustdesk-1.4.7-aarch64.AppImage",
                null
        ));
    }

    /** 版本信息：下载地址 + MD5 校验值（null 表示不校验） */
    record VersionInfo(String url, String md5) {}

    private static final Pattern ID_PATTERN = Pattern.compile("id=([0-9]+)");
    private static final Pattern SERVER_ID_PATTERN =
            Pattern.compile("(?:Generated id |id updated from [0-9]+ to )([0-9]+)");
    private static final int ID_CAPTURE_TIMEOUT_SECONDS = 30;
    private static final int HEALTH_CHECK_INTERVAL_SECONDS = 10;
    private static final int DEFAULT_PASSWORD_ROTATION_SECONDS = 1800;
    private static final int PASSWORD_LENGTH = 16;
    private static final int RUSTDESK_LOCAL_PORT = 21116;
    private static final int MAX_RESTART_COUNT = 3;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("windows");
    private static final boolean IS_LINUX = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("linux");

    private final RustDeskAgentContext context;
    private final RustDeskProperties props;
    private final int localPort;

    private volatile Process process;
    private volatile boolean running;
    private volatile int restartCount;
    private final AtomicReference<String> rustDeskId = new AtomicReference<>("");
    private final AtomicReference<String> currentPassword = new AtomicReference<>("");

    private ExecutorService stdoutReader;
    private ScheduledExecutorService scheduler;
    private final SecureRandom secureRandom = new SecureRandom();

    public RustDeskAgentService(RustDeskAgentContext context) {
        this.context = context;
        this.props = RustDeskProperties.fromCapabilities(context.getCapabilities());
        this.localPort = resolveLocalPort();
        // 将 RustDesk 端口写入 capabilities，使 Gateway 注册时能创建 TargetEntry
        Map<String, String> caps = new LinkedHashMap<>(context.getCapabilities());
        caps.put("rustdesk_port", String.valueOf(localPort));
        context.setCapabilities(caps);
    }

    // ===== 进程管理 =====

    /**
     * 启动 RustDesk 服务进程。
     * <p>流程：检查可执行文件 → 写入配置文件 → 启动进程 → 解析 ID → 上报 capabilities</p>
     */
    public synchronized void startProcess() {
        if (running) { return; }

        // Windows: 启动前清理残留 RustDesk 进程，避免 IPC pipe 冲突
        if (IS_WINDOWS) {
            killResidualProcesses();
        }

        Path executable = resolveExecutable();
        if (executable == null) {
            log.warn("[RustDesk] 无法解析 RustDesk 可执行文件路径（OS={}），使用模拟模式",
                    System.getProperty("os.name"));
            startMockMode();
            return;
        }

        Path workDir = resolveWorkDir();
        Path configDir = resolveConfigDir(workDir);
        try {
            Files.createDirectories(configDir);
            Files.createDirectories(workDir.resolve("rustdesk-logs"));
        } catch (IOException e) {
            log.error("[RustDesk] 创建工作目录失败: {}", e.getMessage());
            return;
        }

        // 生成临时密码
        String password = generatePassword();
        currentPassword.set(password);

        // 清除旧配置文件，强制 RustDesk 重新生成 ID
        Path configFile = configDir.resolve("RustDesk.toml");
        Path configFile2 = configDir.resolve("RustDesk2.toml");
        try {
            boolean deleted1 = Files.deleteIfExists(configFile);
            boolean deleted2 = Files.deleteIfExists(configFile2);
            if (deleted1 || deleted2) {
                log.info("[RustDesk] 已清除旧配置文件: RustDesk.toml={} RustDesk2.toml={}", deleted1, deleted2);
            }
        } catch (IOException e) {
            log.warn("[RustDesk] 清除旧配置文件失败: {}", e.getMessage());
        }

        // 写入 RustDesk 配置文件
        writeConfig(configDir, password);

        // 检查持久化的 ID（上次启动已捕获的）
        String persistedId = readPersistedId(workDir);
        if (persistedId != null && !persistedId.isEmpty()) {
            rustDeskId.set(persistedId);
            log.info("[RustDesk] 使用持久化 ID: {}", persistedId);
        }

        // 启动进程
        try {
            process = launchProcess(executable, workDir);
            running = true;
            log.info("[RustDesk] 进程已启动 PID={}", process.pid());
        } catch (IOException e) {
            log.error("[RustDesk] 启动进程失败: {}", e.getMessage());
            return;
        }

        // 异步读取 stdout 以捕获 ID（仅在无持久化 ID 时需要）
        if (persistedId == null || persistedId.isEmpty()) {
            stdoutReader = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "rustdesk-stdout");
                t.setDaemon(true);
                return t;
            });
            stdoutReader.submit(this::captureIdFromStdout);
        }

        // 等待 ID 生成（阻塞最多 30 秒；有持久化 ID 则跳过）
        if (persistedId == null || persistedId.isEmpty()) {
            String capturedId = waitForId();
            if (!capturedId.isEmpty()) {
                rustDeskId.set(capturedId);
                persistId(workDir, capturedId);
                log.info("[RustDesk] ID 已捕获并持久化: {}", capturedId);
            } else {
                log.warn("[RustDesk] 在 {}s 内未能捕获 ID，进程可能启动失败", ID_CAPTURE_TIMEOUT_SECONDS);
            }
        }

        // 上报 capabilities 到 Gateway
        reportCapabilities();

        // 启动健康检查 + 密码轮换调度器
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "rustdesk-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::healthCheck, HEALTH_CHECK_INTERVAL_SECONDS,
                HEALTH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        int rotationInterval = props.getPasswordRotationSeconds() > 0
                ? props.getPasswordRotationSeconds() : DEFAULT_PASSWORD_ROTATION_SECONDS;
        scheduler.scheduleWithFixedDelay(this::rotatePassword, rotationInterval,
                rotationInterval, TimeUnit.SECONDS);
    }

    /**
     * 模拟模式 — 当 RustDesk 可执行文件不可用时，生成模拟 ID 和密码用于测试。
     */
    private void startMockMode() {
        String agentId = context.getAgentId();
        String mockId = "mock_" + (agentId != null && agentId.length() >= 8
                ? agentId.substring(0, 8) : UUID.randomUUID().toString().substring(0, 8));
        String password = generatePassword();
        rustDeskId.set(mockId);
        currentPassword.set(password);
        running = true;
        log.info("[RustDesk] 模拟模式已启动 id={}", mockId);
        reportCapabilities();
    }

    /**
     * Windows: 清理残留 RustDesk 进程，避免 IPC pipe 冲突。
     */
    private void killResidualProcesses() {
        try {
            new ProcessBuilder("taskkill", "/F", "/IM", "rustdesk.exe")
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            new ProcessBuilder("taskkill", "/F", "/IM", "RuntimeBroker_rustdesk.exe")
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            log.info("[RustDesk] 已清理残留 RustDesk 进程");
        } catch (Exception e) {
            log.debug("[RustDesk] 清理残留进程时忽略: {}", e.getMessage());
        }
    }

    /**
     * 停止 RustDesk 进程并清理资源。
     */
    public synchronized void stopProcess() {
        running = false;
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        if (stdoutReader != null) { stdoutReader.shutdownNow(); stdoutReader = null; }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    log.warn("[RustDesk] 进程被强制终止");
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            log.info("[RustDesk] 进程已停止");
        }
        process = null;
        rustDeskId.set("");
    }

    /**
     * 关闭所有资源（Agent 断开连接时调用）。
     */
    public void close() {
        stopProcess();
    }

    // ===== 访问控制 =====

    public String getRustDeskId() { return rustDeskId.get(); }
    public String getCurrentPassword() { return currentPassword.get(); }
    public boolean isRunning() { return running && process != null && process.isAlive(); }
    public boolean hasValidId() {
        String id = rustDeskId.get();
        return id != null && !id.isEmpty() && running;
    }
    public int getLocalPort() { return localPort; }

    /**
     * 处理控制端连接请求 — 返回 RustDesk ID + 密码，控制端用此凭证直连。
     */
    public Map<String, Object> handleConnect(String sessionId,
                                              Map<String, Object> target,
                                              Map<String, Object> auth) {
        if (!running || !hasValidId()) {
            log.warn("[RustDesk] 连接请求被拒绝 running={} hasValidId={}", running, hasValidId());
            return Map.of("type", "error", "sessionId", sessionId, "msg", "RustDesk 服务未就绪");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "rustdesk_credentials");
        response.put("sessionId", sessionId);
        response.put("rustdesk_id", rustDeskId.get());
        response.put("rustdesk_password", currentPassword.get());
        response.put("rustdesk_port", localPort);
        log.info("[RustDesk] 返回连接凭证: sessionId={} id={}", sessionId, rustDeskId.get());
        return response;
    }

    // ===== 内部实现 =====

    private int resolveLocalPort() {
        Map<String, String> caps = context.getCapabilities();
        if (caps != null) {
            String portStr = caps.get("rustdesk.local-port");
            if (portStr != null && !portStr.isBlank()) {
                try { return Integer.parseInt(portStr); }
                catch (NumberFormatException ignored) {}
            }
        }
        return RUSTDESK_LOCAL_PORT;
    }

    private Path resolveExecutable() {
        // 1) 配置的通用可执行文件路径（最高优先级）
        String exePath = props.getExePath();
        if (exePath != null && !exePath.isBlank()) {
            Path path = Path.of(exePath);
            if (Files.isRegularFile(path)) {
                if (!IS_WINDOWS && !Files.isExecutable(path)) {
                    try { path.toFile().setExecutable(true); }
                    catch (Exception e) { log.warn("[RustDesk] 无法设置可执行权限: {}", e.getMessage()); }
                }
                log.info("[RustDesk] 使用本地可执行文件: {}", path);
                return path;
            }
            log.warn("[RustDesk] 配置的可执行文件路径不可用: {}", exePath);
        }

        // 2) 解析下载地址：优先用配置的 URL，否则从版本注册表自动选择
        VersionInfo versionInfo = resolveVersionInfo();
        String downloadUrl = versionInfo.url();
        String expectedMd5 = versionInfo.md5();

        // 3) 使用 Downloader 下载（支持 MD5 校验、断点续传、自动跳过/更新）
        Path cacheDir = Path.of(props.getAppImageDir());
        try {
            Downloader.DownloadResult result = Downloader.create()
                    .url(downloadUrl)
                    .target(cacheDir)
                    .expectedMd5(expectedMd5)
                    .execute();

            Path target = result.getFile();
            if (!IS_WINDOWS && !Files.isExecutable(target)) {
                target.toFile().setExecutable(true);
            }
            log.info("[RustDesk] 可执行文件就绪: {} (skipped={})", target, result.isSkipped());
            return target;
        } catch (Exception e) {
            log.error("[RustDesk] 可执行文件下载失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析版本信息：优先使用配置的 URL，否则从版本注册表按 OS/arch 自动选择。
     */
    private VersionInfo resolveVersionInfo() {
        // 配置了自定义 URL → 直接使用（无 MD5）
        String configuredUrl = props.getExeUrl();
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            return new VersionInfo(configuredUrl, null);
        }

        // 从注册表匹配
        String osKey = IS_WINDOWS ? "windows" : IS_LINUX ? "linux" : "linux";
        String archKey = System.getProperty("os.arch", "").contains("aarch64") ? "aarch64" : "x86_64";
        String registryKey = "1.4.7-" + osKey + "-" + archKey;

        VersionInfo info = VERSION_REGISTRY.get(registryKey);
        if (info != null) {
            log.info("[RustDesk] 使用版本注册表: key={} url={}", registryKey, info.url());
            return info;
        }

        // 降级：返回默认 Linux x86_64
        log.warn("[RustDesk] 版本注册表未匹配 key={}，使用默认 Linux x86_64", registryKey);
        return VERSION_REGISTRY.get("1.4.7-linux-x86_64");
    }

    private Path resolveConfigDir(Path workDir) {
        if (IS_WINDOWS) {
            return Path.of(System.getenv("APPDATA"), "RustDesk", "config");
        }
        return workDir.resolve(".config").resolve("rustdesk");
    }

    private Path resolveWorkDir() {
        String configured = props.getWorkDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home", "."), ".rustdesk-agent");
    }

    private String generatePassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void writeConfig(Path configDir, String password) {
        Path configFile = configDir.resolve("RustDesk.toml");
        StringBuilder toml = new StringBuilder();
        toml.append("# Generated by utils-rustdesk-agent RustDeskAgentService\n");
        toml.append("[options]\n");
        toml.append("password = \"").append(password).append("\"\n");

        String serverHost = props.getServerHost();
        if (serverHost != null && !serverHost.isBlank()) {
            toml.append("custom-rendezvous-server = \"").append(serverHost).append("\"\n");
        }
        String serverKey = props.getServerKey();
        if (serverKey != null && !serverKey.isBlank()) {
            toml.append("key = \"").append(serverKey).append("\"\n");
        }
        try {
            Files.writeString(configFile, toml.toString(), StandardCharsets.UTF_8);
            log.info("[RustDesk] 配置文件已写入: {}", configFile);
        } catch (IOException e) {
            log.error("[RustDesk] 写入配置文件失败: {}", e.getMessage());
        }
    }

    private Process launchProcess(Path executable, Path workDir) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable.toAbsolutePath().toString());
        cmd.add("--server");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        Map<String, String> env = pb.environment();
        env.put("RUSTDESK_NO_GUI", "1");
        if (!IS_WINDOWS) {
            env.put("HOME", workDir.toAbsolutePath().toString());
            env.remove("DISPLAY");
            env.remove("WAYLAND_DISPLAY");
        }

        Path logFile = workDir.resolve("rustdesk-logs").resolve("rustdesk.log");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        log.info("[RustDesk] 启动命令: {} (OS={})", String.join(" ", cmd),
                IS_WINDOWS ? "Windows" : "Linux");
        return pb.start();
    }

    private void captureIdFromStdout() {
        Path primaryLog = resolveWorkDir().resolve("rustdesk-logs").resolve("rustdesk.log");
        Path serverLogDir = IS_WINDOWS
                ? Path.of(System.getenv("APPDATA"), "RustDesk", "log", "server") : null;

        try {
            long primaryLastSize = 0;
            Map<Path, Long> serverFileSizes = new HashMap<>();
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ID_CAPTURE_TIMEOUT_SECONDS);
            while (System.currentTimeMillis() < deadline && running) {
                if (Files.exists(primaryLog)) {
                    long size = Files.size(primaryLog);
                    if (size > primaryLastSize) {
                        String id = scanLogFile(primaryLog, primaryLastSize, ID_PATTERN);
                        if (id != null) { rustDeskId.set(id); log.info("[RustDesk] 从 stdout 日志捕获 ID: {}", id); return; }
                        primaryLastSize = size;
                    }
                }
                if (serverLogDir != null && Files.isDirectory(serverLogDir)) {
                    try (var stream = Files.newDirectoryStream(serverLogDir, "rustdesk_r*.log")) {
                        for (Path logFile : stream) {
                            long size = Files.size(logFile);
                            long lastSize = serverFileSizes.getOrDefault(logFile, 0L);
                            if (size > lastSize) {
                                String id = scanLogFile(logFile, lastSize, SERVER_ID_PATTERN);
                                if (id != null) { rustDeskId.set(id); log.info("[RustDesk] 从服务器日志捕获 ID: {}", id); return; }
                                serverFileSizes.put(logFile, size);
                            }
                        }
                    }
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("[RustDesk] 读取日志文件失败: {}", e.getMessage());
        }
    }

    private String scanLogFile(Path logFile, long offset, Pattern pattern) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            raf.seek(offset);
            String line;
            while ((line = raf.readLine()) != null) {
                Matcher m = pattern.matcher(line);
                if (m.find()) { return m.group(1); }
            }
        }
        return null;
    }

    private String waitForId() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ID_CAPTURE_TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            String id = rustDeskId.get();
            if (id != null && !id.isEmpty()) { return id; }
            try { Thread.sleep(200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return rustDeskId.get();
    }

    private String readPersistedId(Path workDir) {
        Path idFile = workDir.resolve("rustdesk-logs").resolve("rustdesk_id.txt");
        try {
            if (Files.isRegularFile(idFile)) {
                String id = Files.readString(idFile, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) { return id; }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void persistId(Path workDir, String id) {
        Path idFile = workDir.resolve("rustdesk-logs").resolve("rustdesk_id.txt");
        try { Files.writeString(idFile, id, StandardCharsets.UTF_8); }
        catch (IOException e) { log.warn("[RustDesk] 持久化 ID 失败: {}", e.getMessage()); }
    }

    private void healthCheck() {
        if (!running) { return; }
        if (process == null || !process.isAlive()) {
            int exitCode = process != null ? process.exitValue() : -1;
            if (exitCode == 0 && hasValidId()) {
                log.info("[RustDesk] 主进程正常退出（exitCode=0），服务组件在后台运行中，停止健康检查");
                process = null;
                if (scheduler != null) { scheduler.shutdown(); scheduler = null; }
                return;
            }
            if (restartCount >= MAX_RESTART_COUNT) {
                log.error("[RustDesk] 进程已连续退出 {} 次（exitCode={}），放弃重启，降级到模拟模式",
                        restartCount, exitCode);
                stopProcess();
                startMockMode();
                return;
            }
            restartCount++;
            log.warn("[RustDesk] 进程异常退出 exitCode={}，尝试重启（第 {}/{} 次）...",
                    exitCode, restartCount, MAX_RESTART_COUNT);
            stopProcess();
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            startProcess();
        }
    }

    private synchronized void rotatePassword() {
        if (!running) { return; }
        String newPassword = generatePassword();
        log.info("[RustDesk] 密码轮换: 新密码已生成");
        currentPassword.set(newPassword);
        writeConfig(resolveConfigDir(resolveWorkDir()), newPassword);
        stopProcess();
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        startProcess();
        reportCapabilities();
    }

    private void reportCapabilities() {
        String id = rustDeskId.get();
        if (id == null || id.isEmpty()) { return; }
        try {
            Map<String, Object> caps = new LinkedHashMap<>();
            caps.put("type", "capability_update");
            caps.put("agent_id", context.getAgentId());
            caps.put("capabilities", Map.of(
                    "rustdesk_id", id,
                    "rustdesk_password", currentPassword.get(),
                    "rustdesk_server", Objects.toString(props.getServerHost(), "public"),
                    "rustdesk_running", String.valueOf(running)
            ));
            String json = MAPPER.writeValueAsString(caps);
            context.sendToGateway(json);
            log.info("[RustDesk] capabilities 已上报 id={}", id);
        } catch (Exception e) {
            log.warn("[RustDesk] 上报 capabilities 失败: {}", e.getMessage());
        }
    }
}
