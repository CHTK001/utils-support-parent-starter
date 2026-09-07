package com.chua.remote.agent;

import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class SSHChannelManager {

    private final RemoteClient client;
    private final String agentId;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, SSHSession> sessions = new ConcurrentHashMap<>();

    public SSHChannelManager(RemoteClient client, String agentId) {
        this.client = client;
        this.agentId = agentId;
    }

    public void handleSSHFrame(Frame frame) {
        Map<String, String> meta = frame.getMetadata();
        if (meta == null) {
            return;
        }
        String action = meta.get("sshAction");
        String sessionId = meta.get("sshSessionId");
        if (action == null || sessionId == null) {
            return;
        }
        switch (action) {
            case "start":
                startSession(sessionId, meta);
                break;
            case "input":
                writeInput(sessionId, frame.getPayload());
                break;
            case "resize":
                resize(sessionId, meta);
                break;
            case "stop":
                stopSession(sessionId);
                break;
        }
    }

    private void startSession(String sessionId, Map<String, String> meta) {
        String host = meta.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(meta.getOrDefault("port", "22"));
        String username = meta.getOrDefault("username", "");
        String password = meta.getOrDefault("password", "");
        int cols = Integer.parseInt(meta.getOrDefault("cols", "80"));
        int rows = Integer.parseInt(meta.getOrDefault("rows", "24"));

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ssh", "-o", "StrictHostKeyChecking=no",
                    username + "@" + host, "-p", String.valueOf(port));
            if (password != null && !password.isEmpty()) {
                pb.environment().put("SSHPASS", password);
                pb = new ProcessBuilder(
                        "sshpass", "-p", password,
                        "ssh", "-o", "StrictHostKeyChecking=no",
                        username + "@" + host, "-p", String.valueOf(port));
            }
            pb.redirectErrorStream(false);
            Process process = pb.start();
            SSHSession session = new SSHSession(sessionId, process);
            sessions.put(sessionId, session);

            executor.submit(() -> readOutput(sessionId, "stdout", process.getInputStream()));
            executor.submit(() -> readOutput(sessionId, "stderr", process.getErrorStream()));

            sendSSHFrame(sessionId, "started", null);
            log.info("SSH会话已启动: sessionId={}, host={}:{}", sessionId, host, port);
        } catch (Exception e) {
            log.error("SSH会话启动失败: sessionId={}", sessionId, e);
            sendSSHFrame(sessionId, "error", e.getMessage().getBytes());
        }
    }

    private void readOutput(String sessionId, String stream, InputStream is) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                byte[] data = new byte[n];
                System.arraycopy(buf, 0, data, 0, n);
                Map<String, String> meta = Map.of(
                        "sshAction", "output",
                        "sshSessionId", sessionId,
                        "stream", stream);
                client.getTransport().send(FrameCodec.sshFrame(agentId, data, meta));
            }
        } catch (Exception e) {
            log.debug("SSH输出流结束: sessionId={}, stream={}", sessionId, stream);
        }
    }

    private void writeInput(String sessionId, byte[] data) {
        SSHSession session = sessions.get(sessionId);
        if (session != null) {
            try {
                OutputStream os = session.process.getOutputStream();
                os.write(data);
                os.flush();
            } catch (Exception e) {
                log.error("SSH输入写入失败: sessionId={}", sessionId, e);
            }
        }
    }

    private void resize(String sessionId, Map<String, String> meta) {
        SSHSession session = sessions.get(sessionId);
        if (session != null) {
            int cols = Integer.parseInt(meta.getOrDefault("cols", "80"));
            int rows = Integer.parseInt(meta.getOrDefault("rows", "24"));
            log.debug("SSH终端resize: sessionId={}, {}x{}", sessionId, cols, rows);
        }
    }

    private void stopSession(String sessionId) {
        SSHSession session = sessions.remove(sessionId);
        if (session != null) {
            session.process.destroyForcibly();
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

    public void stopAll() {
        sessions.forEach((id, session) -> {
            session.process.destroyForcibly();
        });
        sessions.clear();
        executor.shutdownNow();
    }

    private static class SSHSession {
        final String sessionId;
        final Process process;

        SSHSession(String sessionId, Process process) {
            this.sessionId = sessionId;
            this.process = process;
        }
    }
}
