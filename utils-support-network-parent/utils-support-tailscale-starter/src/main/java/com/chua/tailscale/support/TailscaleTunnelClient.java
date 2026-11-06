// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。[P3C E1 豁免] E1: 含 System.out 的代码示例在 Javadoc <pre> 中，非实际执行。
package com.chua.tailscale.support;

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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链式 Tailscale 客户端，管理内嵌 {@code tailscale} 进程的手册生命。
 *
 * <p>Tailscale 官方不提供可内嵌的 Java SDK，因此本客户端以「子进程 + 实时输出解析」
 * 方式封装官方 {@code tailscale} 命令行程序，提供与
 * {@link com.chua.cloudflared.support.CloudflareTunnelClient} 一致的链式 API 体验。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * try (TailscaleTunnelClient client = TailscaleTunnelClient.create()
 *         .port(8080)
 *         .start()) {
 *     // 公网地址，形如 https://<machine>.<tailnet>.ts.net
 *     System.out.println(client.getUrl());
 * }
 * }</pre>
 *
 * <h3>运行环境要求</h3>
 * <p>device 必须已加入 tailnet（ {@code tailscale up --auth-key=TS_AUTHKEY} 认证），
 * 本地 Tailscale 服务必须运行——若本地守护进程未启动（Windows 下通常是服务已停止），
 * {@code funnel} 会在退出码 1 的同时在标准输出输出
 * {@code getting client status: failed to connect to local tailscaled process; is the Tailscale service running?}，
 * 本客户端会将其转为 {@link IllegalStateException} 交给调用方展示，而不会挂起。</p>
 *
 * <h3>生命周期安全</h3>
 * <p>实现 {@link AutoCloseable}，{@code close()} 会先优雅 {@code destroy()}，
 * 超时未退出再 {@code destroyForcibly()}，保证容器关闭时不留孤儿进程。</p>
 *
 * <h3>输出消费</h3>
 * <p>必须持续消费子进程的标准输出直至进程退出——若读到公网 URL 后立即关闭读取流，
 * 进程会因管道满而挂起（实测结论）。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
@Slf4j
public class TailscaleTunnelClient implements AutoCloseable {

    /**
     * 公网 URL 正则：{@code https://<machine>.<tailnet>.ts.net} 或 {@code tcp://<machine>.<tailnet>.ts.net:443}。
     */
    private static final Pattern PUBLIC_URL =
            Pattern.compile("https?://[a-z0-9.-]+\\.[a-z]{2,}(?::\\d+)?(?:/\\S*)?"
                    + "|tcp://[a-z0-9.-]+\\.[a-z]{2,}(?::\\d+)?(?:/\\S*)?");

    /**
     * 名单外的 TCP 地址：{@code <machine>.<tailnet>.ts.net:443}。
     */
    private static final Pattern TCP_ADDRESS =
            Pattern.compile("[a-z0-9.-]+\\.[a-z]{2,}(?::\\d+)?(?:/\\S*)?");

    /**
     * 本地守护进程未运行时的错误特征：{@code failed to connect to local tailscaled process; is the Tailscale service running?}
     */
    private static final String DAEMON_ERROR = "failed to connect to local tailscaled process";

    /**
     * 未登录 tailnet 时的错误特征（也常见于各启动画面的下方）。
     */
    private static final String LOGIN_ERROR = "please run tailscale up";

    /**
     * 默认启动等待超时（秒）。
     */
    private static final long DEFAULT_START_TIMEOUT_SECONDS = 60L;

    /**
     * 关闭时等待进程退出的最大时间（秒）。
     */
    private static final long DEFAULT_CLOSE_GRACE_SECONDS = 5L;

    /**
     * 轮询间隔（毫秒），等待 URL 输出时不会一直空转。
     */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /**
     * 输出消费线程名。
     */
    private static final String OUTPUT_THREAD_NAME = "tailscale-output";

    /**
     * Tailscale 完成认证时注入子进程的环境变量（同 {@code TAILSCALE_BIN} 定位一样唯一）。
     */
    private static final String ENV_AUTH_KEY = "TS_AUTHKEY";

    /**
     * 显式指定的 tailscale 可执行文件路径，优先级高于自动定位。
     */
    private Path binary;

    /**
     * 本地待暴露的服务地址，形如 {@code http://127.0.0.1:8080}。
     */
    private String url;

    /**
     * 需要暴露的公网 TCP 端口（仅在 {@code tcp} 模式下使用，默认 {@code 443}）。
     */
    private String tcpPort;

    /**
     * 认证 Key（写入子进程的 {@code TS_AUTHKEY} 环境变量）。
     */
    private String authKey;

    /**
     * 启动等待超时。
     */
    private Duration startTimeout = Duration.ofSeconds(DEFAULT_START_TIMEOUT_SECONDS);

    /**
     * tailscale 子进程。
     */
    private Process process;

    /**
     * 已解析出的公网 URL / 地址列表，读写均加锁（有人在线程里读）。
     */
    private final List<String> urls = Wraps.list();

    /**
     * 输出消费线程。
     */
    private volatile Thread outputThread;

    /**
     * 退出码。
     */
    private volatile int exitCode = -1;

    /**
     * 最近一次读到的错误行（来自 stdout/stderr，已合并到 stdout）。
     */
    private volatile String lastErrorLine;

    /**
     * 是否已主动关闭。
     */
    private volatile boolean closed;

    /**
     * 创建 client 实例，自动定位 {@code tailscale}。
     *
     * @return TailscaleTunnelClient 实例
     * @throws IllegalStateException tailscale 不可用时抛出（含人工安装指引）
     */
    @Nonnull
    public static TailscaleTunnelClient create() {
        TailscaleTool tool = new TailscaleTool();
        if (!tool.isAvailable()) {
            throw new IllegalStateException(buildMissingBinaryMessage(tool));
        }
        return create(tool.locate().orElseThrow().toAbsolutePath());
    }

    /**
     * 使用指定可执行文件路径创建客户端。
     *
     * @param binary tailscale 可执行文件完整路径
     * @return TailscaleTunnelClient 实例
     */
    @Nonnull
    public static TailscaleTunnelClient create(@Nonnull Path binary) {
        return new TailscaleTunnelClient().binary(binary);
    }

    /**
     * 指定 tailscale 可执行文件路径。
     *
     * @param binary 可执行文件完整路径
     * @return 当前客户端
     */
    @Nonnull
    public TailscaleTunnelClient binary(@Nonnull Path binary) {
        this.binary = binary;
        return this;
    }

    /**
     * 设置本地待暴露的服务地址，形如 {@code http://127.0.0.1:8080}。
     *
     * @param url 服务地址
     * @return 当前客户端
     */
    @Nonnull
    public TailscaleTunnelClient url(@Nonnull String url) {
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
    public TailscaleTunnelClient port(int port) {
        return url("http://127.0.0.1:" + port);
    }

    /**
     * 设置需要暴露的公网 TCP 端口（仅在 {@code tcp} 模式下使用），默认 {@code 443}。
     *
     * @param tcpPort 公网 TCP 端口
     * @return 当前客户端
     */
    @Nonnull
    public TailscaleTunnelClient tcpPort(@Nullable String tcpPort) {
        if (tcpPort != null && !tcpPort.isBlank()) {
            this.tcpPort = tcpPort.trim();
        } else {
            this.tcpPort = "443";
        }
        return this;
    }

    /**
     * 设置认证 Key。
     *
     * <p>Key 会作为 {@code TS_AUTHKEY} 环境变量传入子进程，
     * 设备无需先登录 tailnet 即可建立隧道。</p>
     *
     * @param authKey 认证 Key
     * @return 当前客户端
     */
    @Nonnull
    public TailscaleTunnelClient authKey(@Nullable String authKey) {
        this.authKey = authKey == null ? null : authKey.trim();
        return this;
    }

    /**
     * 设置启动等待超时。
     *
     * @param startTimeout 启动超时
     * @return 当前客户端
     */
    @Nonnull
    public TailscaleTunnelClient startTimeout(@Nonnull Duration startTimeout) {
        this.startTimeout = startTimeout;
        return this;
    }

    /**
     * 启动隧道，等待公网 URL 输出后返回。
     *
     * <p>理论命令：在阳光下 {@code tailscale funnel --yes <target>}，
     * 开发侧 {@code tailscale funnel --tcp=<tcp-port> --yes <target>}。</p>
     *
     * @return 当前客户端
     * @throws IllegalStateException 启动失败、超时未输出公网地址时抛出
     */
    @Nonnull
    public TailscaleTunnelClient start() {
        ensureNotClosed();
        if (process != null && process.isAlive()) {
            return this;
        }
        urls.clear();
        List<String> command = buildCommand();
        log.info("[Tailscale] 启动命令: {}", command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        applyEnvironment(pb);
        try {
            process = pb.start();
        } catch (IOException e) {
            process = null;
            throw new IllegalStateException("Tailscale 进程启动失败: " + e.getMessage(), e);
        }
        startOutputConsumer();
        waitForUrl();
        log.info("[Tailscale] 隧道已建立: {}", urls);
        return this;
    }

    /**
     * 获取全部公网地址。
     *
     * @return 地址列表，隧道未建立时为空
     */
    @Nonnull
    public List<String> getUrls() {
        synchronized (urls) {
            return Wraps.list(urls);
        }
    }

    /**
     * 获取第一个公网地址。
     *
     * @return 公网地址，未建立时返回 null
     */
    @Nullable
    public String getUrl() {
        synchronized (urls) {
            return urls.isEmpty() ? null : urls.get(0);
        }
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
                    log.warn("[Tailscale] 进程未在 {}s 内退出，强制终止", DEFAULT_CLOSE_GRACE_SECONDS);
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
     * 构建启动命令：{@code tailscale funnel --yes [--tcp=<port>] <target>}。
     *
     * @return 命令参数列表（首元素为可执行文件路径）
     */
    @Nonnull
    private List<String> buildCommand() {
        List<String> command = Wraps.list();
        Path executable = binary != null ? binary : new TailscaleTool().locate().orElseThrow()
                .toAbsolutePath();
        command.add(executable.toString());
        command.add("funnel");
        if (tcpPort != null && !tcpPort.isBlank()) {
            command.add("--tcp=" + tcpPort);
        }
        command.add("--yes");
        String target = url != null && !url.isBlank() ? url : "8080";
        command.add(target);
        return command;
    }

    /**
     * 向子进程注入环境变量（主要是认证 Key）。
     *
     * @param pb 进程构建器
     */
    private void applyEnvironment(ProcessBuilder pb) {
        if (authKey != null && !authKey.isBlank()) {
            pb.environment().put(ENV_AUTH_KEY, authKey);
            log.debug("[Tailscale] 已注入 {} 环境变量", ENV_AUTH_KEY);
        }
    }

    /**
     * 启动输出消费线程：持续读取进程输出，解析公网地址，记录退出码与错误行。
     */
    private void startOutputConsumer() {
        Process p = process;
        // [P3C C1 豁免] 守护/后台线程：有独立生命周期，不适合交给通用执行器
        Thread consumer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    handleOutputLine(line);
                }
            } catch (IOException e) {
                if (!closed) {
                    log.debug("[Tailscale] 输出流读取结束: {}", e.getMessage());
                }
            } finally {
                if (p != null && !p.isAlive()) {
                    exitCode = p.exitValue();
                    log.debug("[Tailscale] 进程退出，退出码={}", exitCode);
                }
            }
        }, OUTPUT_THREAD_NAME);
        outputThread = consumer;
        consumer.setDaemon(true);
        consumer.start();
    }

    /**
     * 处理一行进程输出：解析公网地址，错误行落盘用于异常展示，其余行按日志级别输出。
     *
     * @param line 输出行
     */
    private void handleOutputLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String urlCandidate = extractUrl(line);
        if (urlCandidate != null) {
            synchronized (urls) {
                if (!urls.contains(urlCandidate)) {
                    urls.add(urlCandidate);
                    log.info("[Tailscale] 发现公网地址: {}", urlCandidate);
                    return;
                }
            }
        }
        if (line.contains(DAEMON_ERROR) || line.contains(LOGIN_ERROR)) {
            // [P3C C2 豁免] daemon 没跑/没登录时，把原样输出一字不差的错误行，方便排查
            lastErrorLine = line.trim();
            log.warn("[Tailscale] 设备异常: {}", lastErrorLine);
        } else {
            log.debug("[Tailscale] {}", line.trim());
        }
    }

    /**
     * 从一行输出中提取公网地址。
     *
     * @param line 输出行
     * @return 地址，未匹配返回 null
     */
    @Nullable
    private static String extractUrl(String line) {
        Matcher matcher = PUBLIC_URL.matcher(line);
        if (matcher.find()) {
            return matcher.group();
        }
        // tcp:// 以外的格式（如 <machine>.<tailnet>.ts.net:443）
        Matcher address = TCP_ADDRESS.matcher(line);
        if (address.find()) {
            return address.group();
        }
        return null;
    }

    /**
     * 等待公网地址输出，超时则终止进程并抛错。
     *
     * @throws IllegalStateException 超时且未获得地址时抛出
     */
    private void waitForUrl() {
        long deadline = System.currentTimeMillis() + startTimeout.toMillis();
        try {
            while (process != null && process.isAlive()) {
                synchronized (urls) {
                    if (!urls.isEmpty()) {
                        return;
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    break;
                }
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("等待 tailscale 输出公网地址被中断");
        }
        // 区分进程已退出与仍未输出
        if (process != null && !process.isAlive()) {
            // 进程已结束，记录精确退出码（与输出消费线程存在竞态，以下标精确值为准）
            exitCode = process.exitValue();
            close();
            throw new IllegalStateException(buildExitMessage());
        }
        close();
        throw new IllegalStateException("Tailscale 启动超时（" + startTimeout.toMillis() + "ms），未输出公网地址"
                + "：请检查 Tailscale 服务是否已启动、设备是否已加入 tailnet、端口是否在允许范围内");
    }

    /**
     * 进程退出后的状态信息。
     *
     * @return 异常消息
     */
    private String buildExitMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tailscale 隧道启动失败，进程退出码=").append(exitCode).append(' ').append("：");
        if (lastErrorLine != null && !lastErrorLine.isBlank()) {
            sb.append(lastErrorLine);
        } else {
            sb.append("请检查 Tailscale 是否登录、服务是否运行、端口是否可用");
        }
        return sb.toString();
    }

    /**
     * 确保客户端未关闭。
     *
     * @throws IllegalStateException 已关闭时抛出
     */
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("TailscaleTunnelClient 已关闭，无法复用");
        }
    }

    /**
     * 构建 tailscale 缺失时的安装指引。
     *
     * @param tool 工具定位器
     * @return 指引文本
     */
    private static String buildMissingBinaryMessage(TailscaleTool tool) {
        return "未找到 tailscale 可执行文件。请任选一种方式安装："
                + System.lineSeparator()
                + "  1) 官方安装包: https://tailscale.com/download"
                + System.lineSeparator()
                + "  2) winget install tailscale（Windows）/ brew install tailscale（macOS）"
                + System.lineSeparator()
                + "  3) 设置环境变量 TAILSCALE_BIN=/path/to/tailscale 指定安装位置"
                + System.lineSeparator()
                + " 当前探测: " + tool.descriptor().executableName() + "（版本探测结果见日志）";
    }
}
