package com.chua.cloudflared.support;

import com.chua.common.support.collection.wrap.Wraps;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链式 Cloudflare Tunnel 客户端，管理内嵌 {@code cloudflared} 进程生命周期。
 *
 * <p>Cloudflare 官方并不提供可内嵌的 Java Agent SDK（与 ngrok-java 不同），
 * 因此本客户端以「子进程 + 实时输出解析」方式封装官方 {@code cloudflared} 守护进程，
 * 提供与 {@link com.chua.ngrok.support.NgrokClient} 一致的链式 API 体验。</p>
 *
 * <h3>Quick Tunnel（免账号、免登录，最常用）</h3>
 * <pre>{@code
 * try (CloudflareTunnelClient client = CloudflareTunnelClient.create()
 *         .url("http://127.0.0.1:8080")
 *         .start()) {
 *     // 公网地址
 *     System.out.println(client.getUrls());
 * }
 * }</pre>
 *
 * <h3>远程管理命名隧道（Zero Trust 面板生成 Token）</h3>
 * <pre>{@code
 * try (CloudflareTunnelClient client = CloudflareTunnelClient.create()
 *         .token(System.getenv("CLOUDFLARE_TUNNEL_TOKEN"))
 *         .url("http://127.0.0.1:8080")
 *         .start()) {
 *     System.out.println(client.getUrls());
 * }
 * }</pre>
 *
 * <h3>固定自定义域名（需账户 + DNS CNAME 路由）</h3>
 * <pre>{@code
 * try (CloudflareTunnelClient client = CloudflareTunnelClient.create()
 *         .url("http://127.0.0.1:8080")
 *         .hostname("tunnel.example.com")
 *         .start()) {
 *     System.out.println(client.getUrls());
 * }
 * }</pre>
 *
 * <p><b>生命周期安全</b>：实现 {@link AutoCloseable}，{@code close()} 会先优雅
 * {@code destroy()}，超时未退出再 {@code destroyForcibly()}，保证容器关闭时不留孤儿进程。</p>
 *
 * <p><b>输出消费</b>：必须持续消费 {@code cloudflared} 的标准输出直至进程退出——
 * 若读到公网 URL 后立即关闭读取流，进程会随之退出、隧道立即变 530
 * （实测结论，参见 Spring AI 模块 AiDevOpsController 的同类坑）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CloudflareTunnelClient implements AutoCloseable {

    /**
     * Quick Tunnel 公网 URL 正则：{@code https://xxx-xxx-xxx.trycloudflare.com}
     */
    private static final Pattern TRYCLOUDFLARE_URL =
            Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    /**
     * 自定义域名 URL 正则：{@code https://tunnel.example.com}
     */
    private static final Pattern HTTP_URL =
            Pattern.compile("https://[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}(?:/\\S*)?");

    /**
     * 默认启动等待超时（秒）
     */
    private static final long DEFAULT_START_TIMEOUT_SECONDS = 40L;

    /**
     * 关闭时等待进程退出的最大时间（秒）
     */
    private static final long DEFAULT_CLOSE_GRACE_SECONDS = 5L;

    /**
     * 输出消费线程名
     */
    private static final String OUTPUT_THREAD_NAME = "cloudflared-output";

    /**
     * 显式指定的 cloudflared 可执行文件路径
     */
    private Path binary;

    /**
     * 远程管理隧道 Token（Zero Trust 面板生成），为空时使用 Quick Tunnel
     */
    private String token;

    /**
     * 本地待暴露的服务地址，形如 {@code http://127.0.0.1:8080}
     */
    private String url;

    /**
     * 固定自定义域名（需账户 + DNS CNAME），为空时由 Cloudflare 分配随机域名
     */
    private String hostname;

    /**
     * 是否禁用自动更新（默认关闭，避免运行期自更新导致隧道中断）
     */
    private boolean noAutoUpdate = true;

    /**
     * 日志级别：debug / info / warn / error / fatal，默认 info
     */
    private String logLevel = "info";

    /**
     * 启动等待超时
     */
    private Duration startTimeout = Duration.ofSeconds(DEFAULT_START_TIMEOUT_SECONDS);

    /**
     * cloudflared 子进程
     */
    private Process process;

    /**
     * 已解析出的公网 URL 列表
     */
    private final List<String> urls = Wraps.list();

    /**
     * 输出消费线程
     */
    private volatile Thread outputThread;

    /**
     * 退出码
     */
    private volatile int exitCode = -1;

    /**
     * 是否已主动关闭
     */
    private volatile boolean closed;

    /**
     * 创建 client 实例
     */
    private CloudflareTunnelClient() {
    }

    /**
     * 创建一个 Cloudflare Tunnel 客户端构造器，自动定位 {@code cloudflared}。
     *
     * @return CloudflareTunnelClient 实例
     * @throws IllegalStateException cloudflared 不可用时抛出（含人工安装指引）
     */
    @Nonnull
    public static CloudflareTunnelClient create() {
        CloudflaredTool tool = new CloudflaredTool();
        if (!tool.isAvailable()) {
            throw new IllegalStateException(buildMissingBinaryMessage(tool));
        }
        return create(tool.locate().orElseThrow().toAbsolutePath());
    }

    /**
     * 使用指定可执行文件路径创建客户端。
     *
     * @param binary cloudflared 可执行文件完整路径
     * @return CloudflareTunnelClient 实例
     */
    @Nonnull
    public static CloudflareTunnelClient create(@Nonnull Path binary) {
        return new CloudflareTunnelClient().binary(binary);
    }

    /**
     * 指定 cloudflared 可执行文件路径。
     *
     * @param binary 可执行文件完整路径
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient binary(@Nonnull Path binary) {
        this.binary = binary;
        return this;
    }

    /**
     * 设置远程管理隧道 Token（Zero Trust 面板生成）。
     *
     * @param token 隧道 Token
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient token(@Nullable String token) {
        this.token = token;
        return this;
    }

    /**
     * 设置本地待暴露的服务地址。
     *
     * @param url 形如 {@code http://127.0.0.1:8080}
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient url(@Nonnull String url) {
        this.url = url;
        return this;
    }

    /**
     * 设置本地待暴露的端口（自动拼装为 {@code http://127.0.0.1:<port>}）。
     *
     * @param port 本地端口
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient port(int port) {
        return url("http://127.0.0.1:" + port);
    }

    /**
     * 设置固定自定义域名（需账户下 DNS CNAME 路由）。
     *
     * @param hostname 形如 {@code tunnel.example.com}
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient hostname(@Nullable String hostname) {
        this.hostname = hostname;
        return this;
    }

    /**
     * 设置是否需要禁用自动更新，默认关闭。
     *
     * @param noAutoUpdate true 关闭自动更新（推荐），false 允许运行期自更新
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient noAutoUpdate(boolean noAutoUpdate) {
        this.noAutoUpdate = noAutoUpdate;
        return this;
    }

    /**
     * 设置 cloudflared 日志级别。
     *
     * @param logLevel debug / info / warn / error / fatal
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient logLevel(@Nullable String logLevel) {
        if (logLevel != null && !logLevel.isBlank()) {
            this.logLevel = logLevel.trim();
        }
        return this;
    }

    /**
     * 设置启动等待超时。
     *
     * @param startTimeout 启动超时
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient startTimeout(@Nonnull Duration startTimeout) {
        this.startTimeout = startTimeout;
        return this;
    }

    /**
     * 启动隧道，等待公网 URL 输出后返回。
     *
     * <p>根据是否配置 {@link #token} 自动选择启动方式：
     * <ul>
     *   <li>未配置 token — Quick Tunnel：{@code cloudflared tunnel --url <url>}</li>
     *   <li>配置 token — 远程管理命名隧道：{@code cloudflared tunnel run --token <token>}，
     *       目标服务与固定域名在 Zero Trust 模板的入口规则中配置（故不再追加 --url/--hostname）</li>
     * </ul>
     * 仅 Quick Tunnel 模式下 {@link #hostname} 生效，会追加 {@code --hostname}。</p>
     *
     * @return 当前客户端
     * @throws IllegalStateException 启动失败、超时未输出公网地址时抛出
     */
    @Nonnull
    public CloudflareTunnelClient start() {
        ensureNotClosed();
        if (process != null && process.isAlive()) {
            return this;
        }
        urls.clear();
        List<String> command = buildCommand();
        log.info("[Cloudflared] 启动命令: {}", String.join(" ", maskToken(command)));

        String executable = binary != null ? binary.toString() : "cloudflared";
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            process = pb.start();
        } catch (IOException e) {
            process = null;
            throw new IllegalStateException("cloudflared 进程启动失败: " + e.getMessage(), e);
        }
        CountDownLatch urlReady = new CountDownLatch(1);
        startOutputConsumer(urlReady);
        waitForUrl(urlReady);
        log.info("[Cloudflared] 隧道已建立: {}", urls);
        return this;
    }

    /**
     * 阻塞当前线程，直到 隧道 进程退出。
     *
     * <p>通常在所有隧道建立后调用此方法，使进程保持运行。</p>
     *
     * @return 当前客户端
     */
    @Nonnull
    public CloudflareTunnelClient block() {
        Process p = process;
        if (p == null) {
            return this;
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("cloudflared 阻塞等待被中断");
        }
        return this;
    }

    /**
     * 获取全部公网 URL。
     *
     * @return URL 列表，隧道未建立时为空
     */
    @Nonnull
    public List<String> getUrls() {
        return Wraps.list(urls);
    }

    /**
     * 获取第一个公网 URL。
     *
     * @return 公网 URL，未建立时返回 null
     */
    @Nullable
    public String getUrl() {
        return urls.isEmpty() ? null : urls.get(0);
    }

    /**
     * 判断隧道进程是否存活。
     *
     * @return 存活返回 true
     */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * 获取进程退出码。
     *
     * @return 退出码，进程未结束返回 -1
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * 获取底层进程。
     *
     * @return 子进程实例，未启动时返回 null
     */
    @Nullable
    public Process getProcess() {
        return process;
    }

    /**
     * 关闭隧道：先优雅停止，超时强杀，保证不留孤儿进程。
     */
    @Override
    public void close() {
        closed = true;
        Process p = process;
        if (p == null) {
            return;
        }
        if (p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(DEFAULT_CLOSE_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("[Cloudflared] 进程未在 {}s 内退出，强制终止", DEFAULT_CLOSE_GRACE_SECONDS);
                    p.destroyForcibly();
                    p.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        process = null;
    }

    /**
     * 构建启动命令。
     *
     * @return 命令参数列表（首元素为可执行文件路径）
     */
    @Nonnull
    private List<String> buildCommand() {
        String executable = binary != null ? binary.toString() : "cloudflared";
        List<String> command = Wraps.list();
        command.add(executable);
        if (noAutoUpdate) {
            command.add("--no-autoupdate");
        }
        if (token != null && !token.isBlank()) {
            command.add("tunnel");
            command.add("run");
            command.add("--token");
            command.add(token);
        } else {
            command.add("tunnel");
        }
        if (url != null && !url.isBlank()) {
            command.add("--url");
            command.add(url);
        }
        if (hostname != null && !hostname.isBlank()) {
            command.add("--hostname");
            command.add(hostname);
        }
        command.add("--loglevel");
        command.add(logLevel);
        return command;
    }

    /**
     * 启动输出消费线程：持续读取进程输出，解析公网 URL，进程结束后记录退出码。
     *
     * @param urlReady 公网 URL 就绪信号
     */
    private void startOutputConsumer(CountDownLatch urlReady) {
        Process p = process;
        Thread consumer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    handleOutputLine(line, urlReady);
                }
            } catch (IOException e) {
                if (!closed) {
                    log.debug("[Cloudflared] 输出流读取结束: {}", e.getMessage());
                }
            } finally {
                if (p != null && !p.isAlive()) {
                    exitCode = p.exitValue();
                    log.debug("[Cloudflared] 进程退出，退出码={}", exitCode);
                }
            }
        }, OUTPUT_THREAD_NAME);
        outputThread = consumer;
        consumer.setDaemon(true);
        consumer.start();
    }

    /**
     * 处理一行进程输出：解析公网 URL，其余行按日志级别输出。
     *
     * @param line     输出行
     * @param urlReady 就绪信号
     */
    private void handleOutputLine(String line, CountDownLatch urlReady) {
        if (line == null || line.isBlank()) {
            return;
        }
        String urlCandidate = extractUrl(line);
        if (urlCandidate != null && !urls.contains(urlCandidate)) {
            synchronized (urls) {
                if (!urls.contains(urlCandidate)) {
                    urls.add(urlCandidate);
                    log.info("[Cloudflared] 发现公网 URL: {}", urlCandidate);
                    urlReady.countDown();
                    return;
                }
            }
        }
        log.debug("[Cloudflared] {}", line.trim());
    }

    /**
     * 从一行输出中提取公网 URL：优先 Quick Tunnel 随机域名，其次自定义域名。
     *
     * @param line 输出行
     * @return URL，未匹配返回 null
     */
    @Nullable
    private static String extractUrl(String line) {
        Matcher quick = TRYCLOUDFLARE_URL.matcher(line);
        if (quick.find()) {
            return quick.group();
        }
        Matcher http = HTTP_URL.matcher(line);
        if (http.find()) {
            String candidate = http.group();
            // 排除日志中出现的 curl 帮助地址等干扰项
            if (candidate.contains("://")
                    && !candidate.contains("developers.cloudflare.com")
                    && !candidate.contains("github.com")
                    && !candidate.contains("cloudflare.com/")) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 等待公网 URL 输出，超时则终止进程并抛错。
     *
     * @param urlReady 就绪信号
     * @throws IllegalStateException 超时且未获得 URL 时抛出
     */
    private void waitForUrl(CountDownLatch urlReady) {
        long timeoutMillis = startTimeout.toMillis();
        try {
            boolean ready = urlReady.await(timeoutMillis, TimeUnit.MILLISECONDS);
            if (ready) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("等待 cloudflared 输出公网地址被中断");
        }
        // 超时：区分进程已退出与仍未输出
        if (process != null && !process.isAlive()) {
            close();
            throw new IllegalStateException("cloudflared 进程提前退出，退出码=" + exitCode
                    + "：请检查 cloudflared 是否安装、认证是否有效、本地服务是否可达");
        }
        close();
        throw new IllegalStateException("cloudflared 启动超时（" + timeoutMillis + "ms），未输出公网地址"
                + "：请检查网络（需能访问 Cloudflare 边缘）、token/hostname 配置是否正确");
    }

    /**
     * 确保客户端未关闭。
     *
     * @throws IllegalStateException 已关闭时抛出
     */
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("CloudflareTunnelClient 已关闭，无法复用");
        }
    }

    /**
     * 构建 cloudflared 缺失时的安装指引。
     *
     * @param tool 工具定位器
     * @return 指引文本
     */
    private static String buildMissingBinaryMessage(CloudflaredTool tool) {
        return "未找到 cloudflared 可执行文件。请任选一种方式安装："
                + System.lineSeparator()
                + "  1) brew install cloudflared（macOS）/ winget install Cloudflare.cloudflared（Windows）"
                + System.lineSeparator()
                + "  2) 下载官方二进制: https://github.com/cloudflare/cloudflared/releases/latest"
                + System.lineSeparator()
                + "  3) 设置环境变量 CLOUDFLARED_BIN=/path/to/cloudflared 指定安装位置"
                + System.lineSeparator()
                + "  当前探测: " + tool.descriptor().executableName() + "（版本探测结果见日志）";
    }

    /**
     * 隐藏命令中的 token，避免日志泄露凭据。
     *
     * @param command 原始命令
     * @return 脱敏后的命令
     */
    private static List<String> maskToken(List<String> command) {
        List<String> copy = Wraps.list(command);
        for (int i = 1; i < copy.size(); i++) {
            if ("--token".equals(copy.get(i)) && i + 1 < copy.size()) {
                copy.set(i + 1, "***");
                i++;
            }
        }
        return copy;
    }
}