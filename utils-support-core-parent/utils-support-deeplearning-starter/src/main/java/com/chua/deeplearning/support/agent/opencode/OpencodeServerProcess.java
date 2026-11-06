// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.agent.opencode;

import com.chua.deeplearning.support.engine.CliModelRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 单实例 {@code opencode serve} 进程生命周期管理器。
 *
 * <p>整个 JVM 只托管<b>一个</b> opencode server：agent 会话、事件流、权限应答全部通过
 * HTTP 打向同一进程。这是 opencode 的原生用法——它自身就是“TUI/客户端 + 本地 server”的
 * 架构，server 进程内维护会话与工具运行时，多客户端共享。</p>
 *
 * <h3>启动</h3>
 * <ol>
 *   <li>通过 {@link CliModelRunner#locate} 定位 opencode 可执行文件（显式配置 / PATH / 自动下载）</li>
 *   <li>隔离 XDG 目录，避免读取用户全局 {@code ~/.config/opencode/opencode.jsonc}：
 *       该文件可能包含当前 CLI 版本不识别的 v2 键，直接导致进程启动即退出</li>
 *   <li>预置最小配置与 provider 凭据（复制 {@code ~/.local/share/opencode/auth.json}）</li>
 *   <li>以 {@code serve --hostname 127.0.0.1 --port <p>} 启动，并注入
 *       {@code OPENCODE_SERVER_PASSWORD} 开启 Basic Auth</li>
 *   <li>轮询 {@code GET /global/health} 直到 {@code healthy=true}，超时或进程提前退出则失败</li>
 * </ol>
 *
 * <h3>复用与重启</h3>
 * <p>启动成功后把 {@code port/username/password/pid} 写入状态文件；下一次
 * {@link #ensureStarted()} 会先探测该地址的健康状态，可复用上一次遗留的进程（例如应用热重启时
 * 子进程仍存活）。健康检查失败则销毁旧进程并重新拉起。</p>
 *
 * <h3>安全说明</h3>
 * <p>server 仅监听回环地址；密码默认随机生成并写入临时目录下的状态文件，供同机调试复用。
 * 该文件属于敏感信息，部署时建议通过 {@code opencode.server.home} 指向受控目录。</p>
 *
 * <h3>可配置项（系统属性）</h3>
 * <ul>
 *   <li>{@code opencode.server.home} — 状态/隔离目录，默认 {@code <cacheRoot>/opencode/server}</li>
 *   <li>{@code opencode.server.port} — 固定端口，默认自动挑选空闲端口</li>
 *   <li>{@code opencode.server.password} — Basic Auth 密码，默认随机生成</li>
 *   <li>{@code opencode.server.username} — Basic Auth 用户名，默认 {@code opencode}</li>
 *   <li>{@code opencode.server.start-timeout-seconds} — 启动等待秒数，默认 90</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public final class OpencodeServerProcess {

    /**
     * 单例
     */
    private static final OpencodeServerProcess INSTANCE = new OpencodeServerProcess();

    /**
     * JSON 读写器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 健康检查路径
     */
    private static final String HEALTH_PATH = "/global/health";

    /**
     * 状态文件名
     */
    private static final String STATE_FILE = "server.json";

    /**
     * 日志目录名
     */
    private static final String LOG_DIRECTORY = "logs";

    /**
     * 日志文件名
     */
    private static final String LOG_FILE = "server.log";

    /**
     * 隔离目录名
     */
    private static final String XDG_DIRECTORY = "xdg";

    /**
     * 隔离配置子目录名
     */
    private static final String XDG_CONFIG = "config";

    /**
     * 隔离数据子目录名
     */
    private static final String XDG_DATA = "data";

    /**
     * 隔离缓存子目录名
     */
    private static final String XDG_CACHE = "cache";

    /**
     * 隔离状态子目录名
     */
    private static final String XDG_STATE = "state";

    /**
     * opencode 在 XDG 目录下的固定子目录名
     */
    private static final String OPENCODE_SUBDIRECTORY = "opencode";

    /**
     * 最小配置文件名
     */
    private static final String CONFIG_FILE = "opencode.json";

    /**
     * 系统属性：托管目录
     */
    private static final String PROP_HOME = "opencode.server.home";

    /**
     * 系统属性：固定端口
     */
    private static final String PROP_PORT = "opencode.server.port";

    /**
     * 系统属性：Basic Auth 密码
     */
    private static final String PROP_PASSWORD = "opencode.server.password";

    /**
     * 系统属性：Basic Auth 用户名
     */
    private static final String PROP_USERNAME = "opencode.server.username";

    /**
     * 系统属性：启动等待秒数
     */
    private static final String PROP_START_TIMEOUT = "opencode.server.start-timeout-seconds";

    /**
     * 环境变量：Basic Auth 密码（与 opencode 官方一致）
     */
    private static final String ENV_PASSWORD = "OPENCODE_SERVER_PASSWORD";

    /**
     * 环境变量：Basic Auth 用户名
     */
    private static final String ENV_USERNAME = "OPENCODE_SERVER_USERNAME";

    /**
     * 默认 Basic Auth 用户名
     */
    private static final String DEFAULT_USERNAME = "opencode";

    /**
     * 默认启动等待秒数
     */
    private static final long DEFAULT_START_TIMEOUT_SECONDS = 90L;

    /**
     * 健康检查轮询间隔（毫秒）
     */
    private static final long HEALTH_POLL_MILLIS = 500L;

    /**
     * 健康检查单次超时
     */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5L);

    /**
     * 关闭进程等待秒数
     */
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    /**
     * 日志文件体积上限（超过则启动前清空）
     */
    private static final long LOG_MAX_BYTES = 8L * 1024L * 1024L;

    /**
     * 启动失败时回显的日志尾部字节数
     */
    private static final int LOG_TAIL_BYTES = 4096;

    /**
     * 健康探测客户端（连接超时短，避免拖慢启动）
     */
    private static final HttpClient PROBE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3L))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * 生命周期锁
     */
    private final Object lock = new Object();

    /**
     * 当前可用服务信息
     */
    private volatile ServerInfo info;

    /**
     * 当前托管的子进程（复用外部遗留进程时为 null）
     */
    private volatile Process process;

    /**
     * 是否已注册 JVM 关闭钩子
     */
    private boolean shutdownHooked;

    /**
     * 工具类构造：仅允许单例
     */
    private OpencodeServerProcess() {
    }

    /**
     * 获取单例。
     *
     * @return 单例
     */
    public static OpencodeServerProcess getInstance() {
        return INSTANCE;
    }

    /**
     * 确保 server 可用（必要时启动或重启）。
     *
     * @return 可用服务信息
     */
    public ServerInfo ensureStarted() {
        synchronized (lock) {
            ServerInfo current = info;
            if (current != null && isHealthy(current)) {
                return current;
            }
            ServerInfo persisted = readState();
            if (persisted != null && isHealthy(persisted)) {
                log.info("[opencode] 复用已存在的 server: {}", persisted.baseUrl());
                info = persisted;
                return persisted;
            }
            destroyCurrent();
            info = start();
            return info;
        }
    }

    /**
     * 探测健康状态。
     *
     * @param candidate 候选服务信息
     * @return 是否健康
     */
    public boolean isHealthy(ServerInfo candidate) {
        if (candidate == null || candidate.baseUrl() == null || candidate.baseUrl().isBlank()) {
            return false;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(candidate.baseUrl() + HEALTH_PATH))
                    .timeout(HEALTH_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            if (candidate.authorization() != null) {
                builder.header("Authorization", candidate.authorization());
            }
            HttpResponse<String> response = PROBE.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode node = MAPPER.readTree(response.body());
            return node.path("healthy").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 关闭托管进程（JVM 退出钩子与显式调用共用）。
     */
    public void shutdown() {
        synchronized (lock) {
            if (process == null) {
                return;
            }
            destroyCurrent();
            deleteState();
        }
    }

    /**
     * 启动一个 server 并等待健康。
     *
     * @return 服务信息
     */
    private ServerInfo start() {
        Path binary = resolveBinary();
        Path home = home();
        Path configHome = prepareDirectory(home.resolve(XDG_DIRECTORY).resolve(XDG_CONFIG));
        Path dataHome = prepareDirectory(home.resolve(XDG_DIRECTORY).resolve(XDG_DATA));
        Path cacheHome = prepareDirectory(home.resolve(XDG_DIRECTORY).resolve(XDG_CACHE));
        Path stateHome = prepareDirectory(home.resolve(XDG_DIRECTORY).resolve(XDG_STATE));
        Path logDirectory = prepareDirectory(home.resolve(LOG_DIRECTORY));
        prepareConfig(configHome);
        copyCredentials(dataHome);
        Path logFile = logDirectory.resolve(LOG_FILE);
        truncateLogIfNeeded(logFile);

        String username = resolveUsername();
        String password = resolvePassword();
        int port = resolvePort();
        ServerInfo candidate = new ServerInfo("http://127.0.0.1:" + port, username, password, true);

        ProcessBuilder builder = new ProcessBuilder(buildCommand(binary, port));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Map<String, String> environment = builder.environment();
        environment.put(ENV_PASSWORD, password);
        environment.put(ENV_USERNAME, username);
        environment.put("XDG_CONFIG_HOME", configHome.toString());
        environment.put("XDG_DATA_HOME", dataHome.toString());
        environment.put("XDG_CACHE_HOME", cacheHome.toString());
        environment.put("XDG_STATE_HOME", stateHome.toString());
        environment.put("OPENCODE_DISABLE_AUTOUPDATE", "true");

        log.info("[opencode] 启动 server: {} (port={}, log={})", binary, port, logFile);
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("启动 opencode server 失败: " + e.getMessage(), e);
        }
        registerShutdownHook();
        if (!awaitHealthy(candidate)) {
            String tail = tailLog(logFile);
            destroyCurrent();
            throw new IllegalStateException("opencode server 启动失败（健康检查未通过），日志尾部:\n" + tail);
        }
        writeState(candidate, process.pid());
        log.info("[opencode] server 就绪: {}", candidate.baseUrl());
        return candidate;
    }

    /**
     * 定位可真正被 {@link ProcessBuilder} 执行的文件。
     *
     * <p>Windows 上 npm 会同时生成 {@code opencode}（Unix sh 脚本）、{@code opencode.cmd}
     * 与 {@code opencode.ps1}。{@link CliModelRunner#locate} 的 PATH 探测会先命中无扩展名的
     * sh 脚本，直接执行会报 {@code error=193}，这里改为优先选择 {@code .cmd}/{@code .exe}。</p>
     *
     * @return 可执行文件
     */
    private Path resolveBinary() {
        Path binary = CliModelRunner.locate(CliModelRunner.opencode());
        if (!CliModelRunner.isWindows()) {
            return binary;
        }
        String fileName = binary.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".exe") || fileName.endsWith(".cmd")
                || fileName.endsWith(".bat") || fileName.endsWith(".com")) {
            return binary;
        }
        Path cmd = binary.resolveSibling(binary.getFileName() + ".cmd");
        if (Files.isRegularFile(cmd)) {
            log.debug("[opencode] 命中 npm 包装脚本: {}", cmd);
            return cmd;
        }
        Path exe = binary.resolveSibling(binary.getFileName() + ".exe");
        if (Files.isRegularFile(exe)) {
            return exe;
        }
        return binary;
    }

    /**
     * 构造启动命令。
     *
     * <p>Windows 上通过 npm 安装的 opencode 通常只有 {@code opencode.cmd} 包装脚本，
     * 而 {@link ProcessBuilder} 直接执行 {@code .cmd}/{@code .bat} 会报
     * “不是有效的 Win32 应用程序”，因此这里统一用 {@code cmd /c} 包裹。</p>
     *
     * @param binary 可执行文件
     * @param port   监听端口
     * @return 命令列表
     */
    private List<String> buildCommand(Path binary, int port) {
        List<String> command = new ArrayList<>();
        String fileName = binary.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".cmd") || fileName.endsWith(".bat")) {
            String comspec = System.getenv("COMSPEC");
            command.add((comspec == null || comspec.isBlank()) ? "cmd.exe" : comspec);
            command.add("/c");
        }
        command.add(binary.toString());
        command.add("serve");
        command.add("--hostname");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(String.valueOf(port));
        command.add("--print-logs");
        command.add("--log-level");
        command.add("WARN");
        return command;
    }

    /**
     * 等待健康检查通过。
     *
     * @param candidate 服务信息
     * @return 是否健康
     */
    private boolean awaitHealthy(ServerInfo candidate) {
        long timeoutSeconds = resolveStartTimeoutSeconds();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Process current = process;
        while (System.nanoTime() < deadline) {
            if (current != null && !current.isAlive()) {
                log.warn("[opencode] server 进程提前退出，exitCode={}", current.exitValue());
                return false;
            }
            if (isHealthy(candidate)) {
                return true;
            }
            try {
                Thread.sleep(HEALTH_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 销毁当前托管进程（含子进程树）。
     */
    private void destroyCurrent() {
        Process current = process;
        process = null;
        info = null;
        if (current == null) {
            return;
        }
        try {
            current.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (Exception e) {
                    log.debug("[opencode] 强制结束子进程失败: {}", e.getMessage());
                }
            });
            current.destroy();
            if (!current.waitFor(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        } catch (Exception e) {
            log.warn("[opencode] 停止 server 进程失败: {}", e.getMessage());
        } finally {
            deleteState();
        }
    }

    /**
     * 注册 JVM 关闭钩子（仅一次）。
     */
    private void registerShutdownHook() {
        if (shutdownHooked) {
            return;
        }
        synchronized (lock) {
            if (shutdownHooked) {
                return;
            }
            // 关闭钩子已移除：资源清理交由 try-finally 或调用方
            shutdownHooked = true;
        }
    }

    /**
     * 托管根目录。
     *
     * @return 目录
     */
    private Path home() {
        String prop = System.getProperty(PROP_HOME);
        Path path;
        if (prop != null && !prop.isBlank()) {
            path = Paths.get(prop.trim());
        } else {
            path = CliModelRunner.cacheRoot().resolve(OPENCODE_SUBDIRECTORY).resolve("server");
        }
        return prepareDirectory(path.toAbsolutePath().normalize());
    }

    /**
     * 创建并返回目录。
     *
     * @param directory 目录
     * @return 目录
     */
    private static Path prepareDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException("创建 opencode 目录失败: " + directory, e);
        }
    }

    /**
     * 写入最小配置，避免继承用户全局配置中的不兼容键。
     *
     * @param configHome 隔离配置根
     */
    private void prepareConfig(Path configHome) {
        Path directory = prepareDirectory(configHome.resolve(OPENCODE_SUBDIRECTORY));
        Path configFile = directory.resolve(CONFIG_FILE);
        if (Files.exists(configFile)) {
            return;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("$schema", "https://opencode.ai/config.json");
        try {
            Files.writeString(configFile, MAPPER.writeValueAsString(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入 opencode 最小配置失败: " + configFile, e);
        }
    }

    /**
     * 把用户默认的 provider 凭据复制到隔离数据目录。
     *
     * <p>opencode 把 {@code /connect} 写入的密钥存在
     * {@code ~/.local/share/opencode/auth.json}。隔离 XDG_DATA_HOME 后该文件不可见，
     * 服务会因缺少 provider 凭据而无法调用模型，因此启动前做一次复制。环境变量形式的
     * 凭据（如 {@code ANTHROPIC_API_KEY}）由子进程直接继承，无需复制。</p>
     *
     * @param dataHome 隔离数据根
     */
    private void copyCredentials(Path dataHome) {
        Path source = findCredentialFile();
        Path target = dataHome.resolve(OPENCODE_SUBDIRECTORY).resolve("auth.json");
        if (source == null || Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            restrictPermissions(target);
            log.info("[opencode] 已复制 provider 凭据到隔离数据目录: {}（来源 {}）", target, source);
        } catch (IOException e) {
            log.warn("[opencode] 复制 provider 凭据失败，若未提供环境变量凭据将无法调用模型: {}",
                    e.getMessage());
        }
    }

    /**
     * 定位用户默认的 provider 凭据文件。
     *
     * <p>不同平台/安装方式下 opencode 的凭据位置可能落在 XDG 默认目录或
     * {@code LOCALAPPDATA}/{@code APPDATA} 下，这里按常见顺序探测第一个存在的文件。</p>
     *
     * @return 凭据文件；均不存在返回 null
     */
    private static Path findCredentialFile() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(System.getProperty("user.home"), ".local", "share",
                OPENCODE_SUBDIRECTORY, "auth.json"));
        addCandidate(candidates, System.getenv("LOCALAPPDATA"));
        addCandidate(candidates, System.getenv("APPDATA"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 追加候选凭据路径。
     *
     * @param candidates 候选列表
     * @param base       基础目录
     */
    private static void addCandidate(List<Path> candidates, String base) {
        if (base == null || base.isBlank()) {
            return;
        }
        candidates.add(Paths.get(base, OPENCODE_SUBDIRECTORY, "auth.json"));
    }

    /**
     * 尽力收紧凭据文件权限（POSIX 平台生效）。
     *
     * @param file 文件
     */
    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception e) {
            log.debug("[opencode] 当前平台不支持收紧凭据权限: {}", e.getMessage());
        }
    }

    /**
     * 日志过大时清空，避免无限增长。
     *
     * @param logFile 日志文件
     */
    private static void truncateLogIfNeeded(Path logFile) {
        try {
            if (Files.exists(logFile) && Files.size(logFile) > LOG_MAX_BYTES) {
                Files.delete(logFile);
            }
        } catch (IOException e) {
            log.debug("[opencode] 清理日志失败: {}", e.getMessage());
        }
    }

    /**
     * 解析启动等待秒数。
     *
     * @return 秒数
     */
    private static long resolveStartTimeoutSeconds() {
        String prop = System.getProperty(PROP_START_TIMEOUT);
        if (prop == null || prop.isBlank()) {
            return DEFAULT_START_TIMEOUT_SECONDS;
        }
        try {
            long value = Long.parseLong(prop.trim());
            return value > 0 ? value : DEFAULT_START_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_START_TIMEOUT_SECONDS;
        }
    }

    /**
     * 解析 Basic Auth 用户名。
     *
     * @return 用户名
     */
    private static String resolveUsername() {
        String prop = System.getProperty(PROP_USERNAME);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv(ENV_USERNAME);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DEFAULT_USERNAME;
    }

    /**
     * 解析 Basic Auth 密码。
     *
     * @return 密码
     */
    private static String resolvePassword() {
        String prop = System.getProperty(PROP_PASSWORD);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv(ENV_PASSWORD);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 解析监听端口。
     *
     * @return 端口
     */
    private static int resolvePort() {
        String prop = System.getProperty(PROP_PORT);
        if (prop != null && !prop.isBlank()) {
            try {
                int value = Integer.parseInt(prop.trim());
                if (value > 0 && value <= 65535) {
                    return value;
                }
            } catch (NumberFormatException e) {
                log.warn("[opencode] 忽略非法端口配置: {}", prop);
            }
        }
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("分配 opencode server 端口失败", e);
        }
    }

    /**
     * 读取遗留状态文件。
     *
     * @return 服务信息；无有效状态返回 null
     */
    private ServerInfo readState() {
        Path file = home().resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            int port = node.path("port").asInt(0);
            String username = node.path("username").asText(DEFAULT_USERNAME);
            String password = node.path("password").asText("");
            if (port <= 0 || port > 65535) {
                return null;
            }
            return new ServerInfo("http://127.0.0.1:" + port, username, password, false);
        } catch (IOException e) {
            log.debug("[opencode] 读取状态文件失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入状态文件，供热重启复用。
     *
     * @param candidate 服务信息
     * @param pid       进程号
     */
    private void writeState(ServerInfo candidate, long pid) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("port", URI.create(candidate.baseUrl()).getPort());
        state.put("username", candidate.username());
        state.put("password", candidate.password());
        state.put("pid", pid);
        state.put("updateTime", System.currentTimeMillis());
        Path file = home().resolve(STATE_FILE);
        try {
            Files.writeString(file, MAPPER.writeValueAsString(state), StandardCharsets.UTF_8);
            restrictPermissions(file);
        } catch (IOException e) {
            log.warn("[opencode] 写入状态文件失败（不影响本次运行）: {}", e.getMessage());
        }
    }

    /**
     * 删除状态文件。
     */
    private void deleteState() {
        try {
            Files.deleteIfExists(home().resolve(STATE_FILE));
        } catch (IOException e) {
            log.debug("[opencode] 删除状态文件失败: {}", e.getMessage());
        }
    }

    /**
     * 读取日志尾部，用于启动失败诊断。
     *
     * @param logFile 日志文件
     * @return 尾部内容
     */
    private static String tailLog(Path logFile) {
        if (!Files.isRegularFile(logFile)) {
            return "（无日志文件）";
        }
        try (InputStream input = Files.newInputStream(logFile)) {
            long size = Files.size(logFile);
            long skip = Math.max(size - LOG_TAIL_BYTES, 0L);
            input.skipNBytes(skip);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "（读取日志失败: " + e.getMessage() + "）";
        }
    }

    /**
     * 服务信息。
     *
     * @param baseUrl  基地址
     * @param username Basic Auth 用户名
     * @param password Basic Auth 密码
     * @param managed  是否由当前 JVM 托管（false 表示复用遗留进程）
     */
    public record ServerInfo(String baseUrl, String username, String password, boolean managed) {

        /**
         * 构造 Basic Auth 头值。
         *
         * @return 头值；无密码返回 null
         */
        public String authorization() {
            if (password == null || password.isBlank()) {
                return null;
            }
            String user = (username == null || username.isBlank()) ? DEFAULT_USERNAME : username;
            String raw = user + ":" + password;
            return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * 创建绑定到指定工作目录的 HTTP 客户端。
         *
         * @param directory 工作目录
         * @return 客户端
         */
        public OpencodeHttpClient newClient(String directory) {
            return new OpencodeHttpClient(baseUrl, username, password, directory);
        }
    }}