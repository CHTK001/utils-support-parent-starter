package com.chua.runtime.support;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.utils.StringUtils;
import com.chua.runtime.support.model.LogStream;
import com.chua.runtime.support.model.RuntimeArtifact;
import com.chua.runtime.support.model.RuntimeStatus;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 默认运行时实例实现 — 基于 {@link ProcessBuilder} 管理进程生命周期。
 *
 * <p>支持启动、停止（优雅 + 强制）、重启、健康检查和实时日志。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultRuntimeInstance implements RuntimeInstance {

    /**
     * 健康检查 HTTP 超时（秒）
     */
    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;

    /**
     * 优雅停止等待时间（秒）
     */
    private static final int GRACEFUL_STOP_TIMEOUT_SECONDS = 10;

    /**
     * 强制停止等待时间（秒）
     */
    private static final int FORCE_STOP_TIMEOUT_SECONDS = 5;

    /**
     * 工件信息
     */
    private final RuntimeArtifact artifact;

    /**
     * 运行时状态
     */
    private final AtomicReference<RuntimeStatus> status;

    /**
     * 底层进程引用
     */
    private final AtomicReference<Process> processRef;

    /**
     * 实时日志流
     */
    private final LogStream logStream;

    /**
      * 进程退出时的 期货
     */
    private final CompletableFuture<CmdResult> onExitFuture;

    /**
     * 日志读取线程
     */
    private volatile Thread logThread;

    /**
     * 进程启动时间戳
     */
    private volatile long startTime;

    /**
     * 创建默认运行时实例。
     *
     * @param artifact 工件描述
     */
    public DefaultRuntimeInstance(RuntimeArtifact artifact) {
        this.artifact = artifact;
        this.status = new AtomicReference<>(RuntimeStatus.STOPPED);
        this.processRef = new AtomicReference<>(null);
        this.logStream = new LogStream();
        this.onExitFuture = new CompletableFuture<>();
    }

    @Override
    /** Artifact */
    public RuntimeArtifact artifact() {
        return artifact;
    }

    @Override
    /** 状态 */
    public RuntimeStatus status() {
        return status.get();
    }

    @Override
    /** Pid */
    public long pid() {
        Process process = processRef.get();
        if (process != null && process.isAlive()) {
            return process.pid();
        }
        return -1;
    }

    @Override
    /** 开始 */
    public synchronized CmdResult start() {
        if (status.get() == RuntimeStatus.RUNNING) {
            log.warn("[runtime] 工件[{}] 已在运行中，跳过启动", artifact.getId());
            return CmdResult.builder()
                    .exitCode(0)
                    .command(artifact.getName() + " 已在运行")
                    .build();
        }

        status.set(RuntimeStatus.STARTING);
        log.info("[runtime] 正在启动工件[{}]: {}", artifact.getId(), artifact.getName());

        Path executable = artifact.getExecutable();
        if (executable != null && !Files.exists(executable)) {
            status.set(RuntimeStatus.CRASHED);
            String msg = "可执行文件不存在: " + executable;
            log.error(msg);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr(msg)
                    .command(artifact.getName())
                    .build();
        }

        try {
            List<String> cmd = buildCommand();
            log.debug("[runtime] 执行命令: {}", cmd);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (artifact.getWorkDir() != null) {
                pb.directory(artifact.getWorkDir().toFile());
            }
            if (artifact.getEnv() != null && !artifact.getEnv().isEmpty()) {
                pb.environment().putAll(artifact.getEnv());
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            processRef.set(process);

            // 启动日志读取线程
            startLogReader(process);

            // 等待启动超时或进程退出
            long timeoutMs = artifact.getStartupTimeoutMs();
            boolean started;

            if (timeoutMs > 0) {
                started = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (started) {
                    int exitCode = process.exitValue();
                    status.set(RuntimeStatus.CRASHED);
                    log.error("[runtime] 工件[{}] 启动失败，进程已退出，退出码: {}", artifact.getId(), exitCode);
                    return CmdResult.builder()
                            .exitCode(exitCode)
                            .stderr("进程启动后立即退出，退出码: " + exitCode)
                            .command(artifact.getName())
                            .build();
                }
            }

            status.set(RuntimeStatus.RUNNING);
            startTime = System.currentTimeMillis();
            log.info("[runtime] 工件[{}] 启动成功，PID: {}", artifact.getId(), process.pid());

            CmdResult result = CmdResult.builder()
                    .exitCode(0)
                    .stdout("工件[" + artifact.getId() + "] 启动成功，PID: " + process.pid())
                    .command(artifact.getName())
                    .startTime(startTime)
                    .build();

            // 异步等待进程退出
            waitForExitAsync(process);

            // 执行健康检查
            if (hasHealthCheck()) {
                CmdResult healthResult = healthCheck();
                if (!healthResult.isSuccess()) {
                    log.warn("[runtime] 工件[{}] 启动后健康检查未通过", artifact.getId());
                }
            }

            return result;

        } catch (Exception e) {
            status.set(RuntimeStatus.CRASHED);
            log.error("[runtime] 启动工件[{}] 异常", artifact.getId(), e);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr(e.getMessage())
                    .command(artifact.getName())
                    .throwable(e)
                    .build();
        }
    }

    @Override
    /** 停止 */
    public synchronized CmdResult stop() {
        Process process = processRef.get();
        if (process == null || !process.isAlive()) {
            status.set(RuntimeStatus.STOPPED);
            return CmdResult.builder()
                    .exitCode(0)
                    .command(artifact.getName() + " 未在运行")
                    .build();
        }

        status.set(RuntimeStatus.STOPPING);
        log.info("[runtime] 正在停止工件[{}]: {}", artifact.getId(), artifact.getName());

        try {
            process.destroy();
            boolean terminated = process.waitFor(GRACEFUL_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!terminated) {
                log.warn("[runtime] 工件[{}] 优雅停止超时，强制终止", artifact.getId());
                process.destroyForcibly();
                terminated = process.waitFor(FORCE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            status.set(RuntimeStatus.STOPPED);
            processRef.set(null);
            stopLogReader();
            logStream.onComplete(0);

            String msg = "工件[" + artifact.getId() + "] 已停止" + (terminated ? "" : "（强制终止）");
            CmdResult result = CmdResult.builder()
                    .exitCode(0)
                    .stdout(msg)
                    .command(artifact.getName() + " stop")
                    .build();

            onExitFuture.complete(result);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.set(RuntimeStatus.UNKNOWN);
            log.error("[runtime] 停止工件[{}] 被中断", artifact.getId(), e);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr(e.getMessage())
                    .command(artifact.getName() + " stop")
                    .throwable(e)
                    .build();
        }
    }

    @Override
    /** Restart */
    public synchronized CmdResult restart() {
        log.info("[runtime] 正在重启工件[{}]: {}", artifact.getId(), artifact.getName());
        CmdResult stopResult = stop();
        if (!stopResult.isSuccess() && status.get() != RuntimeStatus.STOPPED) {
            return stopResult;
        }
        return start();
    }

    @Override
    /** 健康校验 */
    public CmdResult healthCheck() {
        String url = artifact.getHealthCheckUrl();
        String command = artifact.getHealthCheckCommand();

        if (StringUtils.isNotEmpty(url)) {
            return healthCheckHttp(url);
        }

        if (StringUtils.isNotEmpty(command)) {
            return healthCheckCommand(command);
        }

        Process process = processRef.get();
        boolean alive = process != null && process.isAlive();
        return CmdResult.builder()
                .exitCode(alive ? 0 : 1)
                .stdout(alive ? "进程存活" : "进程已退出")
                .command(artifact.getName() + " health")
                .build();
    }

    @Override
    /** 记录日志流 */
    public LogStream logStream() {
        return logStream;
    }

    @Override
    /** onexit */
    public CompletableFuture<CmdResult> onExit() {
        return onExitFuture;
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        stop();
        logStream.close();
    }

    /**
     * 构建启动命令列表。
     *
     * @return 命令列表
     */
    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        Path executable = artifact.getExecutable();

        switch (artifact.getType()) {
            case JAR -> {
                cmd.add("java");
                cmd.add("-jar");
                cmd.add(executable.toAbsolutePath().toString());
            }
            case APPIMAGE, NATIVE, SCRIPT -> {
                cmd.add(executable.toAbsolutePath().toString());
            }
            case NPM -> {
                cmd.add("npm");
                cmd.add("start");
                if (artifact.getWorkDir() != null) {
                    cmd.add("--prefix");
                    cmd.add(artifact.getWorkDir().toAbsolutePath().toString());
                }
            }
            case PYTHON -> {
                cmd.add("python");
                cmd.add(executable.toAbsolutePath().toString());
            }
            default -> {
                cmd.add(executable.toAbsolutePath().toString());
            }
        }

        if (artifact.getArgs() != null) {
            cmd.addAll(artifact.getArgs());
        }
        return cmd;
    }

    /**
     * 是否配置了健康检查。
     *
     * @return 有健康检查配置返回 true
     */
    private boolean hasHealthCheck() {
        return StringUtils.isNotEmpty(artifact.getHealthCheckUrl())
                || StringUtils.isNotEmpty(artifact.getHealthCheckCommand());
    }

    /**
     * HTTP 健康检查。
     *
     * @param url 健康检查 URL
     * @return 检查结果
     */
    private CmdResult healthCheckHttp(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_SECONDS))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean healthy = response.statusCode() >= 200 && response.statusCode() < 400;
            return CmdResult.builder()
                    .exitCode(healthy ? 0 : response.statusCode())
                    .stdout("HTTP " + response.statusCode() + ": " + response.body())
                    .command("GET " + url)
                    .build();
        } catch (Exception e) {
            return CmdResult.builder()
                    .exitCode(1)
                    .stderr("健康检查失败: " + e.getMessage())
                    .command("GET " + url)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * 命令健康检查。
     *
     * @param command 健康检查命令
     * @return 检查结果
     */
    private CmdResult healthCheckCommand(String command) {
        return CmdExecutors.execute(command, HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
      * 启动日志读取线程，从进程的 输入流 逐行读取并推送到 日志流。
     *
     * @param process 进程实例
     */
    private void startLogReader(Process process) {
        stopLogReader();
        Thread thread = new Thread(() -> {
            try (InputStream inputStream = process.getInputStream()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(inputStream, Charset.defaultCharset()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logStream.onLine(line);
                }
            } catch (IOException e) {
                if (status.get() == RuntimeStatus.RUNNING) {
                    log.warn("[runtime] 工件[{}] 日志读取异常", artifact.getId(), e);
                }
            }
        }, "runtime-log-" + artifact.getId());
        thread.setDaemon(true);
        this.logThread = thread;
        thread.start();
    }

    /**
     * 停止日志读取线程。
     */
    private void stopLogReader() {
        Thread thread = this.logThread;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            this.logThread = null;
        }
    }

    /**
      * 异步等待进程退出，退出时更新状态并完成 onexit 期货。
     *
     * @param process 进程实例
     */
    private void waitForExitAsync(Process process) {
        CompletableFuture.runAsync(() -> {
            try {
                int exitCode = process.waitFor();
                if (status.get() == RuntimeStatus.RUNNING) {
                    status.set(RuntimeStatus.CRASHED);
                    log.warn("[runtime] 工件[{}] 进程意外退出，退出码: {}", artifact.getId(), exitCode);
                }
                CmdResult result = CmdResult.builder()
                        .exitCode(exitCode)
                        .stdout("进程退出，退出码: " + exitCode)
                        .command(artifact.getName())
                        .build();
                onExitFuture.complete(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}