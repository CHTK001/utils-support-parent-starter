package com.chua.remote.agent.ssh;

import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.ssh.support.client.SshClient;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSH 会话代理。
 *
 * <p>连接本机 SSH 服务（转发已有 sshd / 自启后的 {@code 127.0.0.1:22}），终端会话实时双向流转：</p>
 * <ul>
 *   <li>输出：{@code onOutput} 回调即收即发（output 帧——不攒批、不缓冲延迟）</li>
 *   <li>输入：{@code sendRaw} 原始字节即写即达（键盘逐键透传）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SshSessionChannel {

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
            String password = meta.getOrDefault("password", defaultPassword != null ? defaultPassword : "");
            int cols = Integer.parseInt(meta.getOrDefault("cols", "80"));
            int rows = Integer.parseInt(meta.getOrDefault("rows", "24"));

            // ③ 连接 SSH 服务（local=本机 sshd；remote=meta 指定主机），建立 pty 终端（实时回调）
            SshClient ssh = SshClient.create(host, username, password);
            ssh.connect();
            SshClient.TerminalOperation terminal = ssh.terminal()
                    .width(cols)
                    .height(rows)
                    .onOutput(text -> {
                        byte[] data = text.getBytes(StandardCharsets.UTF_8);
                        sendSSHFrame(sessionId, "output", data);
                    })
                    .onClose(() -> {
                        sessions.remove(sessionId);
                        sendSSHFrame(sessionId, "stopped", null);
                    })
                    .connect();

            sessions.put(sessionId, new SshSession(sessionId, ssh, terminal));
            // ④ 回 started（会话就绪）
            sendSSHFrame(sessionId, "started", null);
            log.info("SSH会话已启动: sessionId={}, {}@127.0.0.1:22 ({}x{})",
                    sessionId, username, cols, rows);
        } catch (Exception e) {
            log.error("SSH会话启动失败: sessionId={}", sessionId, e);
            sendSSHFrame(sessionId, "error", e.getMessage().getBytes());
        }
    }

    private void writeInput(String sessionId, byte[] data) {
        SshSession session = sessions.get(sessionId);
        if (session == null || data == null || data.length == 0) {
            return;
        }
        try {
            // 原始字节透传（键盘逐键——实时）
            session.terminal.sendRaw(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("SSH输入写入失败: sessionId={}", sessionId, e);
        }
    }

    private void stopSession(String sessionId) {
        SshSession session = sessions.remove(sessionId);
        if (session != null) {
            try {
                session.terminal.close();
                session.ssh.disconnect();
            } catch (Exception ignored) {
            }
            sendSSHFrame(sessionId, "stopped", null);
            log.info("SSH会话已停止: sessionId={}", sessionId);
        }
    }

    private void sendSSHFrame(String sessionId, String action, byte[] data) {
        Map<String, String> meta = Map.of(
                "sshAction", action,
                "sshSessionId", sessionId);
        client.getTransport().send(FrameCodec.sshFrame(agentId, data != null ? data : new byte[0], meta));
    }

    /**
     * 关闭全部会话（agent 退出）。
     */
    public void stopAll() {
        sessions.forEach((id, session) -> {
            try {
                session.terminal.close();
                session.ssh.disconnect();
            } catch (Exception ignored) {
            }
        });
        sessions.clear();
        executor.shutdownNow();
    }

    private static final class SshSession {
        final String sessionId;
        final SshClient ssh;
        final SshClient.TerminalOperation terminal;

        SshSession(String sessionId, SshClient ssh, SshClient.TerminalOperation terminal) {
            this.sessionId = sessionId;
            this.ssh = ssh;
            this.terminal = terminal;
        }
    }
}
