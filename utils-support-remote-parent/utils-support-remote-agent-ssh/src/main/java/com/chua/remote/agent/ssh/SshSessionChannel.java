package com.chua.remote.agent.ssh;

import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SSH 会话代理（系统 ssh 命令实现——无 SSH 客户端库依赖）。
 *
 * <p>连接本机 SSH 服务（转发已有 sshd / 自启后的 {@code 127.0.0.1:22}），终端会话实时双向流转：</p>
 * <ul>
 *   <li>输出：stdout 读取线程即读即发（output 帧——不攒批、不缓冲延迟）</li>
 *   <li>输入：原始字节即写即达（键盘逐键透传）</li>
 * </ul>
 *
 * <p>免密：首次启动用系统 {@code ssh-keygen} 生成 ed25519 key 并装入目标用户
 * {@code ~/.ssh/authorized_keys}——后续 {@code ssh -i} 公钥认证——不依赖任何 SSH 库。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SshSessionChannel {

    private static final String KEY_NAME = "agent_proxy_key";
    private static final int SSH_PORT = 22;

    private final RemoteClient client;
    private final String agentId;
    private final SshServiceManager serviceManager;
    private final String defaultUsername;
    private final String defaultPassword;
    private final Map<String, SshSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SshSessionChannel(RemoteClient client, String agentId, SshServiceManager serviceManager) {
        this(client, agentId, serviceManager, null, null);
    }

    public SshSessionChannel(RemoteClient client, String agentId, SshServiceManager serviceManager,
                             String defaultUsername, String defaultPassword) {
        this.client = client;
        this.agentId = agentId;
        this.serviceManager = serviceManager;
        this.defaultUsername = defaultUsername;
        this.defaultPassword = defaultPassword;
    }

    /**
     * SSH 帧分派（start / input / stop）。
     */
    public void handleSSHFrame(Frame frame) {
        Map<String, String> meta = frame.getMetadata();
        if (meta == null) {
            return;
        }
        String action = meta.getOrDefault("sshAction", "");
        String sessionId = meta.getOrDefault("sshSessionId", "");
        switch (action) {
            case "start":
                startSession(sessionId, meta);
                break;
            case "input":
                writeInput(sessionId, frame.getPayload());
                break;
            case "stop":
                stopSession(sessionId);
                break;
            default:
                log.debug("未知 sshAction: {}", action);
        }
    }

    private void startSession(String sessionId, Map<String, String> meta) {
        try {
            // ① 目标主机：meta 优先（远程直连），local/缺省走本机 sshd 供给（转发已有 / 自启缺失）
            String host = meta.getOrDefault("host", "local");
            boolean local = "local".equals(host) || "localhost".equals(host) || "127.0.0.1".equals(host);
            if (local) {
                if (!serviceManager.ensureSshService()) {
                    sendSSHFrame(sessionId, "error", "SSH服务不可用（自启失败）".getBytes());
                    return;
                }
                host = "127.0.0.1";
            }
            // ② 会话参数（凭据 meta 优先——缺省回落到 agent 注册上报的凭据 + 终端尺寸）
            String username = meta.getOrDefault("username", defaultUsername != null ? defaultUsername : "");
            int cols = Integer.parseInt(meta.getOrDefault("cols", "80"));
            int rows = Integer.parseInt(meta.getOrDefault("rows", "24"));

            // ③ 免密 key 准备（系统 ssh-keygen + authorized_keys——无密码库依赖）
            String keyPath = prepareProxyKey(username);

            // ④ 系统 ssh 命令（pty 实时双向）
            Process process = startSshProcess(keyPath, host, username, cols, rows);

            // ⑤ 输出即读即发（虚拟线程——不攒批）
            executor.submit(() -> readLoop(sessionId, process));

            sessions.put(sessionId, new SshSession(sessionId, process));
            sendSSHFrame(sessionId, "started", null);
            log.info("SSH会话已启动: sessionId={}, {}@{}:{} ({}x{})", sessionId, username, host, SSH_PORT, cols, rows);
        } catch (Exception e) {
            log.error("SSH会话启动失败: sessionId={}", sessionId, e);
            sendSSHFrame(sessionId, "error", (e.getMessage() == null ? "启动失败" : e.getMessage()).getBytes());
        }
    }

    /**
     * 免密 key 准备：生成 ed25519 key（首次）并装入目标用户 authorized_keys。
     */
    private String prepareProxyKey(String username) {
        String home = System.getProperty("user.home", "");
        String sshDir = home + File.separator + ".ssh";
        String keyPath = sshDir + File.separator + KEY_NAME;
        try {
            File dir = new File(sshDir);
            if (!dir.exists()) {
                Files.createDirectories(dir.toPath());
            }
            if (!new File(keyPath).exists()) {
                runCommand("ssh-keygen", "-t", "ed25519", "-N", "", "-f", keyPath);
                log.info("已生成免密 key: {}", keyPath);
            }
            File pub = new File(keyPath + ".pub");
            if (pub.exists()) {
                String pubContent = Files.readString(pub.toPath()).trim();
                File ak = new File(sshDir, "authorized_keys");
                String akContent = ak.exists() ? Files.readString(ak.toPath()) : "";
                if (!akContent.contains(pubContent)) {
                    Files.writeString(ak.toPath(), pubContent + System.lineSeparator(),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    log.info("已安装免密公钥到: {}", ak.getAbsolutePath());
                }
            }
            // 权限收紧（sshd 拒绝宽松权限——Linux/Mac）
            runCommand("chmod", "700", sshDir);
            runCommand("chmod", "600", keyPath);
            runCommand("chmod", "600", sshDir + File.separator + "authorized_keys");
        } catch (Exception e) {
            log.warn("免密 key 准备异常（继续——将回退其它认证）: {}", e.getMessage());
        }
        return keyPath;
    }

    /**
     * 系统 ssh 命令启动（pty——-tt）。Linux/Mac 远端设置终端尺寸后进 bash；Windows 走默认 shell。
     */
    private Process startSshProcess(String keyPath, String host, String username, int cols, int rows) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-i");
        cmd.add(keyPath);
        cmd.add("-tt");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o");
        cmd.add("UserKnownHostsFile=/dev/null");
        cmd.add("-o");
        cmd.add("PreferredAuthentications=publickey");
        cmd.add("-p");
        cmd.add(String.valueOf(SSH_PORT));
        cmd.add(username + "@" + host);
        if (serviceManager.getPlatform() != SshServiceProbe.Platform.WINDOWS) {
            cmd.add("stty cols " + cols + " rows " + rows + "; exec bash -l");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // TERM 必须显式设置（Windows 启动的 agent 环境常缺——远端 top/vim/htop 依赖它）
        pb.environment().putIfAbsent("TERM", "xterm-256color");
        return pb.start();
    }

    private void readLoop(String sessionId, Process process) {
        try (InputStream in = process.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sendSSHFrame(sessionId, "output", buf, n);
            }
        } catch (Exception e) {
            log.warn("SSH会话读取结束: sessionId={}, err={}", sessionId, e.getMessage());
        } finally {
            if (sessions.remove(sessionId) != null) {
                sendSSHFrame(sessionId, "stopped", null);
            }
        }
    }

    private void writeInput(String sessionId, byte[] data) {
        SshSession session = sessions.get(sessionId);
        if (session == null || data == null || data.length == 0) {
            return;
        }
        try {
            session.process.getOutputStream().write(data);
            session.process.getOutputStream().flush();
        } catch (Exception e) {
            log.error("SSH输入写入失败: sessionId={}", sessionId, e);
        }
    }

    private void stopSession(String sessionId) {
        SshSession session = sessions.remove(sessionId);
        if (session != null) {
            session.process.destroy();
            try {
                if (!session.process.waitFor(3, TimeUnit.SECONDS)) {
                    session.process.destroyForcibly();
                }
            } catch (Exception ignored) {
            }
            sendSSHFrame(sessionId, "stopped", null);
            log.info("SSH会话已停止: sessionId={}", sessionId);
        }
    }

    private void sendSSHFrame(String sessionId, String action, byte[] data) {
        sendSSHFrame(sessionId, action, data, data != null ? data.length : 0);
    }

    private void sendSSHFrame(String sessionId, String action, byte[] data, int len) {
        Map<String, String> meta = Map.of(
                "sshAction", action,
                "sshSessionId", sessionId);
        byte[] payload = data != null ? Arrays.copyOf(data, len) : new byte[0];
        client.getTransport().send(FrameCodec.sshFrame(agentId, payload, meta));
    }

    /**
     * 关闭全部会话（agent 退出）。
     */
    public void stopAll() {
        sessions.forEach((id, session) -> session.process.destroyForcibly());
        sessions.clear();
        executor.shutdownNow();
    }

    private static void runCommand(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("命令执行失败: {} — {}", String.join(" ", cmd), e.getMessage());
        }
    }

    private static final class SshSession {
        final String sessionId;
        final Process process;

        SshSession(String sessionId, Process process) {
            this.sessionId = sessionId;
            this.process = process;
        }
    }
}
