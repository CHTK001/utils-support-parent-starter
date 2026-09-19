package com.chua.runtime.core.manager;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.runtime.core.model.LogStream;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.core.model.RuntimeStatus;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * 默认运行时实例 — 基于 处理构建器 管理进程生命周期。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultRuntimeInstance implements RuntimeInstance {


    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(DefaultRuntimeInstance.class.getName());
    /**
     * 健康检查超时（秒）
     */
    private static final int HEALTH_TIMEOUT = 5;

    /**
     * 优雅停止超时（秒）
     */
    private static final int GRACEFUL_STOP = 10;

    /**
     * 强制停止超时（秒）
     */
    private static final int FORCE_STOP = 5;

    /**
     * 工件
     */
    private final RuntimeArtifact artifact;

    /**
     * 状态
     */
    private final AtomicReference<RuntimeStatus> status;

    /**
     * 进程
     */
    private final AtomicReference<Process> processRef;

    /**
     * 日志流
     */
    private final LogStream logStream;

    /**
     * 退出 期货
     */
    private final CompletableFuture<CmdResult> onExitFuture;

    /**
     * 日志线程
     */
    private volatile Thread logThread;

    /**
     * 启动时间
     */
    private volatile long startTime;

    /**
     * 创建 默认runtimeinstance 实例
     * @param artifact artifact
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
        Process p = processRef.get();
        if (p != null && p.isAlive()) {
            return p.pid();
        }
        return -1;
    }

    @Override
    /** 开始 */
    public synchronized CmdResult start() {
        if (status.get() == RuntimeStatus.RUNNING) {
            return CmdResult.builder()
                    .exitCode(0)
                    .command(artifact.getName() + " 已在运行")
                    .build();
        }

 // 优先通过 SPI runtimelauncher 启动
        RuntimeLauncher launcher = RuntimeLauncher.find(artifact.getType().name());
        if (launcher != null) {
            CmdResult result = launcher.start(artifact);
            status.set(result.getExitCode() == 0 ? RuntimeStatus.RUNNING : RuntimeStatus.CRASHED);
            return result;
        }

        Path executable = artifact.getExecutable();
        if (executable != null && !Files.exists(executable)) {
            status.set(RuntimeStatus.CRASHED);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("可执行文件不存在: " + executable)
                    .command(artifact.getName())
                    .build();
        }

        try {
            List<String> cmd = buildCommand();
            LOG.log(Level.FINE, String.format("启动命令: %s", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (artifact.getWorkDir() != null) {
                pb.directory(artifact.getWorkDir().toFile());
            }
            if (CollectionUtils.isNotEmpty(artifact.getEnv())) {
                pb.environment().putAll(artifact.getEnv());
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            processRef.set(process);
            startLogReader(process);

            long timeout = artifact.getStartupTimeoutMs();
            if (timeout > 0) {
                boolean started = process.waitFor(timeout, TimeUnit.MILLISECONDS);
                if (started) {
                    int exitCode = process.exitValue();
                    status.set(RuntimeStatus.CRASHED);
                    return CmdResult.builder()
                            .exitCode(exitCode)
                            .stderr("启动后立即退出，退出码: " + exitCode)
                            .command(artifact.getName())
                            .build();
                }
            }

            status.set(RuntimeStatus.RUNNING);
            startTime = System.currentTimeMillis();
            LOG.log(Level.INFO, String.format("工件[%s] 启动成功，PID: %s", artifact.getId(), process.pid()));
            waitForExitAsync(process);

            if (hasHealthCheck()) {
                healthCheck();
            }

            return CmdResult.builder()
                    .exitCode(0)
                    .stdout("工件[" + artifact.getId() + "] 启动成功，PID: " + process.pid())
                    .command(artifact.getName())
                    .startTime(startTime)
                    .build();

        } catch (Exception e) {
            status.set(RuntimeStatus.CRASHED);
            LOG.log(Level.SEVERE, String.format("启动失败", e));
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
 // 优先通过 SPI runtimelauncher 停止
        RuntimeLauncher launcher = RuntimeLauncher.find(artifact.getType().name());
        if (launcher != null) {
            CmdResult result = launcher.stop(artifact);
            status.set(RuntimeStatus.STOPPED);
            return result;
        }

        Process p = processRef.get();
        if (p == null || !p.isAlive()) {
            status.set(RuntimeStatus.STOPPED);
            return CmdResult.builder().exitCode(0).command(artifact.getName() + " 未在运行").build();
        }

        status.set(RuntimeStatus.STOPPING);
        try {
            p.destroy();
            boolean ok = p.waitFor(GRACEFUL_STOP, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                p.waitFor(FORCE_STOP, TimeUnit.SECONDS);
            }
            status.set(RuntimeStatus.STOPPED);
            processRef.set(null);
            stopLogReader();
            logStream.onComplete(0);
            onExitFuture.complete(CmdResult.builder().exitCode(0).build());
            return CmdResult.builder().exitCode(0).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.set(RuntimeStatus.UNKNOWN);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr(e.getMessage())
                    .throwable(e)
                    .build();
        }
    }

    @Override
    /** Restart */
    public synchronized CmdResult restart() {
        stop();
        return start();
    }

    @Override
    /** 健康校验 */
    public CmdResult healthCheck() {
        String url = artifact.getHealthCheckUrl();
        String cmd = artifact.getHealthCheckCommand();
        if (StringUtils.isNotEmpty(url)) {
            return healthCheckHttp(url);
        }
        if (StringUtils.isNotEmpty(cmd)) {
            return CmdExecutors.execute(cmd, HEALTH_TIMEOUT, TimeUnit.SECONDS);
        }
        Process p = processRef.get();
        boolean alive = p != null && p.isAlive();
        return CmdResult.builder()
                .exitCode(alive ? 0 : 1)
                .stdout(alive ? "进程存活" : "进程已退出")
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
     * 构建命令
     *
     * @return 构建命令的结果
     */
    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        Path exec = artifact.getExecutable();
        switch (artifact.getType()) {
            case JAR -> {
                cmd.add("java");
                cmd.add("-jar");
                cmd.add(exec.toAbsolutePath().toString());
            }
            case APPIMAGE, NATIVE, SCRIPT -> cmd.add(exec.toAbsolutePath().toString());
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
                cmd.add(exec.toAbsolutePath().toString());
            }
            default -> cmd.add(exec.toAbsolutePath().toString());
        }
        if (artifact.getArgs() != null) {
            cmd.addAll(artifact.getArgs());
        }
        return cmd;
    }

    /**
     * 是否拥有健康校验
     *
     * @return 是否包含健康检查的结果
     */
    private boolean hasHealthCheck() {
        return StringUtils.isNotEmpty(artifact.getHealthCheckUrl())
                || StringUtils.isNotEmpty(artifact.getHealthCheckCommand());
    }

    /**
     * 健康校验Http
     *
     * @param url url
     * @return 健康检查http的结果
     */
    private CmdResult healthCheckHttp(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(HEALTH_TIMEOUT)).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(HEALTH_TIMEOUT)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 400;
            return CmdResult.builder()
                    .exitCode(ok ? 0 : resp.statusCode())
                    .stdout("HTTP " + resp.statusCode() + ": " + resp.body())
                    .build();
        } catch (Exception e) {
            return CmdResult.builder().exitCode(1).stderr(e.getMessage()).throwable(e).build();
        }
    }

    /**
     * 开始记录日志Reader
     *
     * @param process 处理
     */
    private void startLogReader(Process process) {
        stopLogReader();
        Thread t = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is, Charset.defaultCharset()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logStream.onLine(line);
                }
            } catch (IOException e) {
                if (status.get() == RuntimeStatus.RUNNING) {
                    LOG.log(Level.WARNING, String.format("日志读取异常", e));
                }
            }
        }, "runtime-log-" + artifact.getId());
        t.setDaemon(true);
        this.logThread = t;
        t.start();
    }

    /** 停止记录日志Reader */
    private void stopLogReader() {
        Thread t = this.logThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            this.logThread = null;
        }
    }

    /**
     * waitforexit异步
     *
     * @param process 处理
     */
    private void waitForExitAsync(Process process) {
        CompletableFuture.runAsync(() -> {
            try {
                int code = process.waitFor();
                if (status.get() == RuntimeStatus.RUNNING) {
                    status.set(RuntimeStatus.CRASHED);
                }
                onExitFuture.complete(CmdResult.builder().exitCode(code).build());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}