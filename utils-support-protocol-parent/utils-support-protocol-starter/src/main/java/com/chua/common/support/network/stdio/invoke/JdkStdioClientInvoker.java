package com.chua.common.support.network.stdio.invoke;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.utils.StringUtils;
import com.chua.common.support.network.stdio.StdioClientInvoker;
import com.chua.common.support.network.stdio.StdioListener;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 JDK 的 Stdio 客户端实现
 *
 * @author CH
 */
@Slf4j
@Spi(value = "jdk", order = 0)
public class JdkStdioClientInvoker extends AbstractStdioClientInvoker {

    private Process process;
    private long pid;
    private BufferedReader reader;
    
    /**
     * 获取进程 PID（用于测试）
     *
     * @return 进程 PID
     */
    public long getPid() {
        return pid;
    }
    private BufferedWriter writer;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean connected = false;
    private Thread readerThread;
    private final StringBuilder lineBuffer = new StringBuilder();
    private boolean lastWasCarriageReturn = false;

    @Override
    public void start(StdioListener listener) {
        try {
            var commandList = buildCommand();
            
            // 使用 ProcessBuilder 创建进程
            var processBuilder = new ProcessBuilder(commandList);
            if (workDirectory != null) {
                processBuilder.directory(new File(workDirectory));
            }
            if (env != null && !env.isEmpty()) {
                processBuilder.environment().putAll(env);
            }
            processBuilder.redirectErrorStream(true);
            this.process = processBuilder.start();
            
            // 记录进程 PID
            this.pid = process.pid();
            log.info("[Stdio客户端][JDK]进程已启动，PID: {}", pid);
            
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            
            connected = true;
            listener.onOpen();
            
            // 启动读取线程
            startResponseReader(listener);
        } catch (Exception e) {
            log.error("[Stdio客户端][JDK]启动失败", e);
            listener.onFailure(e);
        }
    }

    /**
     * 构建命令列表
     *
     * @return 命令列表
     */
    private List<String> buildCommand() {
        List<String> commandList = new ArrayList<>();
        if (StringUtils.isNotBlank(remoteHost)) {
            commandList.add("ssh");
            commandList.add(remoteHost);
        }
        if (useCmdWrapper && isWindows()) {
            commandList.add("cmd");
            commandList.add("/c");
        }
        commandList.add(command);
        if (args != null) {
            commandList.addAll(args);
        }
        return commandList;
    }

    /**
     * 判断是否为 Windows 系统
     *
     * @return 是否为 Windows
     */
    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    /**
     * 启动响应读取线程
     * 支持实时回调，按字符块读取并立即回调，不等待换行符
     * 同时支持按行回调（遇到换行符时触发）
     *
     * @param listener 监听器
     */
    private void startResponseReader(StdioListener listener) {
        readerThread = new Thread(() -> {
            try {
                var buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer, 0, buffer.length)) != -1 && process.isAlive() && connected) {
                    if (read == 0) {
                        continue;
                    }
                    
                    // 实时回调：每次读取都立即回调字节数据，不等待换行符
                    var bytes = new String(buffer, 0, read).getBytes(StandardCharsets.UTF_8);
                    listener.onData(bytes);
                    
                    // 处理字符，支持按行回调
                    for (int i = 0; i < read; i++) {
                        char ch = buffer[i];
                        
                        // 处理上次可能是 \r 的情况
                        if (lastWasCarriageReturn && ch != '\n') {
                            // 上次是 \r，但这次不是 \n，说明是单独的 \r
                            var line = lineBuffer.toString();
                            if (!line.isEmpty()) {
                                listener.onText(line);
                            }
                            lineBuffer.setLength(0);
                            lastWasCarriageReturn = false;
                        }
                        
                        // 处理换行符：\n 或 \r\n
                        if (ch == '\n') {
                            // 遇到 \n，触发按行回调（可能是 \r\n 或单独的 \n）
                            var line = lineBuffer.toString();
                            if (!line.isEmpty()) {
                                listener.onText(line);
                            }
                            lineBuffer.setLength(0);
                            lastWasCarriageReturn = false;
                        } else if (ch == '\r') {
                            // 遇到 \r，检查下一个字符
                            if (i + 1 < read && buffer[i + 1] == '\n') {
                                // 下一个字符是 \n，跳过 \r，等待 \n 处理
                                lastWasCarriageReturn = false;
                                continue;
                            } else {
                                // 下一个字符不是 \n 或已到末尾，标记为 \r
                                lastWasCarriageReturn = true;
                            }
                        } else {
                            lineBuffer.append(ch);
                            lastWasCarriageReturn = false;
                        }
                    }
                }
                
                // 处理剩余的缓冲区内容（进程结束时）
                if (lastWasCarriageReturn) {
                    var line = lineBuffer.toString();
                    if (!line.isEmpty()) {
                        listener.onText(line);
                    }
                    lineBuffer.setLength(0);
                    lastWasCarriageReturn = false;
                }
                if (lineBuffer.length() > 0) {
                    var remaining = lineBuffer.toString();
                    if (!remaining.isEmpty()) {
                        listener.onText(remaining);
                    }
                    lineBuffer.setLength(0);
                }
            } catch (IOException e) {
                if (process.isAlive() && connected) {
                    log.error("[Stdio客户端][JDK]读取失败", e);
                    listener.onFailure(e);
                }
            } finally {
                if (connected) {
                    connected = false;
                    listener.onClosed();
                }
            }
        }, "stdio-reader-" + command);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    @Override
    public void send(String data) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Stdio 客户端未连接");
        }
        writeLock.lock();
        try {
            // 支持多行命令：按行分割，逐行发送
            if (data.contains("\n")) {
                var lines = data.split("\n");
                for (var line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            } else {
                writer.write(data);
                writer.newLine();
            }
            writer.flush();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void send(byte[] data) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Stdio 客户端未连接");
        }
        writeLock.lock();
        try {
            var text = new String(data, StandardCharsets.UTF_8);
            // 支持多行命令：按行分割，逐行发送
            if (text.contains("\n")) {
                var lines = text.split("\n");
                for (var line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            } else {
                writer.write(text);
                writer.newLine();
            }
            writer.flush();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    @Override
    public void close() throws Exception {
        connected = false;
        
        // 先尝试通过 Process API 关闭
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    // 如果强制关闭失败，尝试通过 PID 关闭
                    if (process.isAlive() && pid > 0) {
                        killProcessByPid(pid);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                // 如果强制关闭失败，尝试通过 PID 关闭
                if (process.isAlive() && pid > 0) {
                    killProcessByPid(pid);
                }
            }
        } else if (pid > 0) {
            // 如果进程对象不可用，直接通过 PID 关闭
            killProcessByPid(pid);
        }
        
        try {
            if (reader != null) {
                reader.close();
            }
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            log.warn("[Stdio客户端][JDK]关闭资源失败", e);
        }
    }
    
    /**
     * 通过 PID 关闭进程
     *
     * @param pid 进程 ID
     */
    private void killProcessByPid(long pid) {
        try {
            var isWindows = isWindows();
            var commandList = new ArrayList<String>();
            
            if (isWindows) {
                commandList.add("taskkill");
                commandList.add("/PID");
                commandList.add(String.valueOf(pid));
                commandList.add("/F");
            } else {
                commandList.add("kill");
                commandList.add("-9");
                commandList.add(String.valueOf(pid));
            }
            
            var killProcessBuilder = new ProcessBuilder(commandList);
            var killProcess = killProcessBuilder.start();
            var exitCode = killProcess.waitFor();
            
            if (exitCode == 0) {
                log.info("[Stdio客户端][JDK]通过 PID {} 成功关闭进程", pid);
            } else {
                log.warn("[Stdio客户端][JDK]通过 PID {} 关闭进程失败，退出码: {}", pid, exitCode);
            }
        } catch (Exception e) {
            log.warn("[Stdio客户端][JDK]通过 PID {} 关闭进程异常", pid, e);
        }
    }
}

