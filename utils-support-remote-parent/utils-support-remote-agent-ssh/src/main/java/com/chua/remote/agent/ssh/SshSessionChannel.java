package com.chua.remote.agent.ssh;

import com.chua.remote.agent.ssh.spi.SshSessionStrategies;
import com.chua.remote.agent.ssh.spi.SshSessionStrategy;
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

    /** 免密公钥文件名（agent 生成 + 装入目标用户 authorized_keys） */
    private static final String KEY_NAME = "agent_proxy_key";

    private final RemoteClient client;
    private final String agentId;
    private final SshServiceManager serviceManager;
    /** 会话服务端（9004——网关每会话独立建连；信令仍走 9000 主连接） */
    private com.chua.remote.core.transport.FrameServer sessionServer;
    private final Map<String, SshSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SshSessionChannel(RemoteClient client, String agentId, SshServiceManager serviceManager) {
        this.client = client;
        this.agentId = agentId;
        this.serviceManager = serviceManager;
    }

    /** 绑定会话服务端（agent 启动时注入——9004 端口——每会话独立连接） */
    public void bindSessionServer(com.chua.remote.core.transport.FrameServer sessionServer) {
        this.sessionServer = sessionServer;
    }

    /**
     * SSH 帧分派（start / input / stop）。
     *
     * @param frame    帧
     * @param clientId 会话连接 id（9004 独立连接——回传走该连接）
     */
    public void handleSSHFrame(Frame frame, String clientId) {
        Map<String, String> meta = frame.getMetadata();
        if (meta == null) {
            return;
        }
        String action = meta.getOrDefault("sshAction", "");
        String sessionId = meta.getOrDefault("sshSessionId", "");
        switch (action) {
            case "start":
                startSession(sessionId, meta, clientId);
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

    private void startSession(String sessionId, Map<String, String> meta, String clientId) {
        try {
            int cols = Integer.parseInt(meta.getOrDefault("cols", "80"));
            int rows = Integer.parseInt(meta.getOrDefault("rows", "24"));
            // 双分支策略：已有 sshd 复用套壳 / 无 sshd 自启——免密公钥认证（无账号密码）
            SshSessionStrategy strategy = SshSessionStrategies.select(
                    serviceManager.getPlatform(), serviceManager.isForwardMode());
            if (!strategy.ensureReady()) {
                sendSSHFrame(sessionId, "error", "SSH服务不可用（复用/自启失败）".getBytes());
                return;
            }
            // 免密公钥准备（系统 ssh-keygen + authorized_keys——无密码库依赖）
            String keyPath = prepareProxyKey();

            // ssh -tt 套壳（真实 pty——top/vim 可用）
            Process process = strategy.startShell(keyPath, cols, rows);

            // 输出即读即发（虚拟线程——不攒批）
            executor.submit(() -> readLoop(sessionId, process));

            sessions.put(sessionId, new SshSession(sessionId, process, clientId));
            sendSSHFrame(sessionId, "started", null);
            log.info("SSH会话已启动: sessionId={}, strategy={}, conn={} ({}x{})",
                    sessionId, strategy.getClass().getSimpleName(), clientId, cols, rows);
        } catch (Exception e) {
            log.error("SSH会话启动失败: sessionId={}", sessionId, e);
            sendSSHFrame(sessionId, "error", (e.getMessage() == null ? "启动失败" : e.getMessage()).getBytes());
        }
    }

    /**
     * 免密 key 准备：生成 ed25519 key（首次）并装入当前用户 authorized_keys（agent 在目标机上——连本机 sshd）。
     */
    private String prepareProxyKey() {
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
                if (serviceManager.getPlatform() == SshServiceProbe.Platform.WINDOWS) {
                    // Windows OpenSSH：管理员用户公钥须在 C:\ProgramData\ssh\administrators_authorized_keys
                    // （sshd_config 的 Match Group administrators 指定）——普通 ~/.ssh 位置 sshd 不读
                    installWindowsAdminKey(pubContent);
                } else {
                    // Linux/Mac：装入目标用户 authorized_keys
                    File ak = new File(sshDir, "authorized_keys");
                    String akContent = ak.exists() ? Files.readString(ak.toPath()) : "";
                    if (!akContent.contains(pubContent)) {
                        Files.writeString(ak.toPath(), pubContent + System.lineSeparator(),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        log.info("已安装免密公钥到: {}", ak.getAbsolutePath());
                    }
                }
            }
            if (serviceManager.getPlatform() == SshServiceProbe.Platform.WINDOWS) {
                // Windows 权限由 installWindowsAdminKey 内 icacls 收紧
                runCommand("chmod", "600", keyPath);
            } else {
                // 权限收紧（sshd 拒绝宽松权限——Linux/Mac）
                runCommand("chmod", "700", sshDir);
                runCommand("chmod", "600", keyPath);
                runCommand("chmod", "600", sshDir + File.separator + "authorized_keys");
            }
        } catch (Exception e) {
            log.warn("免密 key 准备异常（继续——将回退其它认证）: {}", e.getMessage());
        }
        return keyPath;
    }

    /**
     * Windows OpenSSH：公钥装入管理员 authorized_keys（C:\ProgramData\ssh\administrators_authorized_keys）
     * 并收紧 ACL（仅 SYSTEM + Administrators 可访问——否则 sshd 拒绝使用该文件）。
     */
    private void installWindowsAdminKey(String pubContent) {
        File ak = new File("C:\\ProgramData\\ssh\\administrators_authorized_keys");
        try {
            if (!ak.exists() && !ak.createNewFile()) {
                log.warn("创建 administrators_authorized_keys 失败（需管理员权限）");
                return;
            }
            String akContent = ak.exists() ? Files.readString(ak.toPath()) : "";
            if (!akContent.contains(pubContent)) {
                Files.writeString(ak.toPath(), pubContent + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.info("已安装免密公钥到(Windows 管理员): {}", ak.getAbsolutePath());
            }
            // Windows sshd 严格要求：仅 SYSTEM + Administrators 可访问
            runCommand("icacls", ak.getAbsolutePath(),
                    "/inheritance:r", "/grant", "SYSTEM:(F)", "Administrators:(F)");
        } catch (Exception e) {
            log.warn("安装 Windows 管理员公钥异常: {}", e.getMessage());
        }
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
        SshSession session = sessions.get(sessionId);
        if (session != null && session.clientId != null && sessionServer != null) {
            // 会话独立连接（9004）回传——多会话并发互不干扰
            sessionServer.send(session.clientId, FrameCodec.sshFrame(sessionId, payload, meta));
        } else {
            // 回退：9000 主连接
            client.getTransport().send(FrameCodec.sshFrame(sessionId, payload, meta));
        }
    }

    /**
     * 关闭全部会话（agent 退出）。
     */
    public void stopAll() {
        sessions.forEach((id, session) -> session.process.destroyForcibly());
        sessions.clear();
        executor.shutdownNow();
    }

    private static final class SshSession {
        final String sessionId;
        final Process process;
        /** 会话独立连接 id（9004——回传走该连接） */
        final String clientId;

        SshSession(String sessionId, Process process, String clientId) {
            this.sessionId = sessionId;
            this.process = process;
            this.clientId = clientId;
        }
    }
}
