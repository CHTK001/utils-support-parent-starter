package com.chua.remote.support.agent.ssh;

import com.chua.remote.support.agent.BaseRemoteAgent;
import com.chua.ssh.support.client.SshClient;
import com.chua.ssh.support.client.SftpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author CH
 */
@Slf4j
public class SshAgentService {

    private static final int CHUNK_SIZE = 256 * 1024;

    private final BaseRemoteAgent agent;
    private final Map<String, SshSession> sshSessions = new ConcurrentHashMap<>();
    private final Map<String, UploadContext> uploadContexts = new ConcurrentHashMap<>();

    public SshAgentService(BaseRemoteAgent agent) {
        this.agent = agent;
    }

    public void handleConnect(String sessionId, Map<String, Object> target, Map<String, Object> auth) {
        String host = target != null ? (String) target.getOrDefault("host", "127.0.0.1") : "127.0.0.1";
        int port = target != null && target.get("port") instanceof Number
                ? ((Number) target.get("port")).intValue() : 22;
        if ("0.0.0.0".equals(host)) {
            host = "127.0.0.1";
        }
        if (port <= 0) {
            port = 22;
        }
        String username = auth != null ? (String) auth.get("username") : System.getProperty("user.name");
        String password = auth != null ? (String) auth.get("password") : "";

        try {
            SshClient sshClient = SshClient.builder()
                    .host(host).port(port)
                    .username(username).password(password)
                    .connectTimeout(15000)
                    .build()
                    .connect();

            SshClient.TerminalOperation terminal = sshClient.terminal()
                    .pty(true).width(80).height(24)
                    .onOutput(output -> {
                        try {
                            String json = agent.mapper.writeValueAsString(Map.of(
                                    "type", "terminal_output",
                                    "sessionId", sessionId,
                                    "data", output
                            ));
                            agent.sendToGateway(json);
                        } catch (Exception e) {
                            log.warn("SSH 输出转发失败: {}", e.getMessage());
                        }
                    })
                    .onClose(() -> {
                        log.info("SSH 连接关闭: sessionId={}", sessionId);
                        agent.sendToGateway("{\"type\":\"disconnected\",\"sessionId\":\"" + sessionId + "\"}");
                        sshSessions.remove(sessionId);
                    })
                    .connect();

            sshSessions.put(sessionId, new SshSession(sessionId, host, port, username, password, sshClient, terminal));
            agent.sessions.put(sessionId, new BaseRemoteAgent.AgentSessionContext(sessionId, "SSH", auth));
            agent.sendToGateway("{\"type\":\"connected\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"SSH connected\"}");
            log.info("SSH 连接成功: sessionId={} {}:{}", sessionId, host, port);
        } catch (Throwable e) {
            log.error("SSH 连接异常: {}", e.getMessage(), e);
            try {
                agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\""
                        + e.getMessage().replace("\"", "'") + "\"}");
            } catch (Exception ignored) {
            }
        }
    }

    public void handleDisconnect(String sessionId) {
        SshSession session = sshSessions.remove(sessionId);
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            log.info("SSH 会话已断开: sessionId={}", sessionId);
        }
        uploadContexts.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + ":"));
    }

    public void handleDisconnectAll() {
        if (sshSessions.isEmpty()) {
            return;
        }
        log.info("[SshAgent] 断开所有 SSH 会话: count={}", sshSessions.size());
        sshSessions.forEach((sid, session) -> {
            try { session.close(); } catch (Exception ignored) {}
        });
        sshSessions.clear();
        uploadContexts.clear();
    }

    public void handleInput(String sessionId, String type, Map<String, Object> payload) {
        SshSession session = sshSessions.get(sessionId);
        if (session == null) {
            return;
        }
        try {
            if ("input".equals(type)) {
                String data = (String) payload.get("data");
                if (data != null) {
                    session.getTerminal().sendRaw(data);
                }
            } else if ("resize".equals(type)) {
                log.debug("SSH resize: sessionId={} cols={} rows={}",
                        sessionId, payload.get("cols"), payload.get("rows"));
            } else if ("file_op".equals(type)) {
                handleFileOp(sessionId, session, payload);
            }
        } catch (Exception e) {
            log.warn("SSH 输入处理失败: {}", e.getMessage());
        }
    }

    private void handleFileOp(String sessionId, SshSession session, Map<String, Object> payload) {
        String action = (String) payload.get("action");
        if (action == null) {
            return;
        }
        try {
            switch (action) {
                case "list" -> handleList(sessionId, session, payload);
                case "upload" -> handleUpload(sessionId, session, payload);
                case "download" -> handleDownload(sessionId, session, payload);
                case "delete" -> handleDelete(sessionId, session, payload);
                case "mkdir" -> handleMkdir(sessionId, session, payload);
                case "rename" -> handleRename(sessionId, session, payload);
                default -> sendFileOpError(sessionId, action, "unknown action: " + action);
            }
        } catch (Exception e) {
            log.error("文件操作异常: sessionId={} action={} error={}", sessionId, action, e.getMessage());
            sendFileOpError(sessionId, action, e.getMessage());
        }
    }

    private void handleList(String sessionId, SshSession session, Map<String, Object> payload) throws Exception {
        String path = (String) payload.getOrDefault("path", "/");
        SftpClient sftp = session.getSftpClient();
        List<Map<String, Object>> files = sftp.ls().path(path).exec();

        List<Map<String, Object>> fileList = new ArrayList<>();
        for (Map<String, Object> f : files) {
            Map<String, Object> attrs = (Map<String, Object>) f.getOrDefault("attributes", Map.of());
            fileList.add(Map.of(
                    "name", f.get("name"),
                    "path", path,
                    "size", attrs.getOrDefault("size", 0),
                    "directory", attrs.getOrDefault("isDirectory", false),
                    "permissions", attrs.getOrDefault("permissions", ""),
                    "modifyTime", attrs.getOrDefault("lastModifiedTime", 0)
            ));
        }

        String json = agent.mapper.writeValueAsString(Map.of(
                "type", "file_op",
                "sessionId", sessionId,
                "action", "list",
                "path", path,
                "files", fileList
        ));
        agent.sendToGateway(json);
    }

    private void handleUpload(String sessionId, SshSession session, Map<String, Object> payload) throws Exception {
        String remotePath = (String) payload.get("path");
        String fileName = (String) payload.get("fileName");
        String data = (String) payload.get("data");

        if (remotePath == null || fileName == null || data == null) {
            sendFileOpError(sessionId, "upload", "missing parameters");
            return;
        }

        File tmp = File.createTempFile("ssh-upload-", ".tmp");
        tmp.deleteOnExit();
        java.nio.file.Files.write(tmp.toPath(), Base64.getDecoder().decode(data));

        SftpClient sftp = session.getSftpClient();
        String remoteFilePath = remotePath.endsWith("/") ? remotePath + fileName : remotePath + "/" + fileName;
        sftp.upload().local(tmp.getAbsolutePath()).remote(remoteFilePath).exec();

        String json = agent.mapper.writeValueAsString(Map.of(
                "type", "file_op",
                "sessionId", sessionId,
                "action", "upload_complete",
                "path", remoteFilePath,
                "size", (long) Base64.getDecoder().decode(data).length,
                "success", true
        ));
        agent.sendToGateway(json);
        log.info("文件上传完成: {}", remoteFilePath);
        tmp.delete();
    }

    private void handleDownload(String sessionId, SshSession session, Map<String, Object> payload) {
        String path = (String) payload.get("path");
        if (path == null) {
            sendFileOpError(sessionId, "download", "missing path");
            return;
        }

        new Thread(() -> {
            try {
                File tmp = File.createTempFile("ssh-download-", ".tmp");
                tmp.deleteOnExit();
                SftpClient sftp = session.getSftpClient();
                sftp.download().remote(path).local(tmp.getAbsolutePath()).exec();

                byte[] data = java.nio.file.Files.readAllBytes(tmp.toPath());
                String b64 = Base64.getEncoder().encodeToString(data);

                String json = agent.mapper.writeValueAsString(Map.of(
                        "type", "file_op",
                        "sessionId", sessionId,
                        "action", "download_complete",
                        "path", path,
                        "data", b64,
                        "size", (long) data.length
                ));
                agent.sendToGateway(json);
                log.info("文件下载完成: {} ({} bytes)", path, data.length);
                tmp.delete();
            } catch (Exception e) {
                log.error("文件下载失败: {}", e.getMessage());
                sendFileOpError(sessionId, "download", e.getMessage());
            }
        }, "sftp-download-" + sessionId).start();
    }

    private void handleDelete(String sessionId, SshSession session, Map<String, Object> payload) throws Exception {
        String path = (String) payload.get("path");
        SftpClient sftp = session.getSftpClient();
        sftp.rm().path(path).exec();

        String json = agent.mapper.writeValueAsString(Map.of(
                "type", "file_op",
                "sessionId", sessionId,
                "action", "delete",
                "path", path,
                "success", true
        ));
        agent.sendToGateway(json);
        log.info("文件已删除: {}", path);
    }

    private void handleMkdir(String sessionId, SshSession session, Map<String, Object> payload) throws Exception {
        String path = (String) payload.get("path");
        SftpClient sftp = session.getSftpClient();
        sftp.mkdir().path(path).recursive(true).exec();

        String json = agent.mapper.writeValueAsString(Map.of(
                "type", "file_op",
                "sessionId", sessionId,
                "action", "mkdir",
                "path", path,
                "success", true
        ));
        agent.sendToGateway(json);
        log.info("目录已创建: {}", path);
    }

    private void handleRename(String sessionId, SshSession session, Map<String, Object> payload) throws Exception {
        String oldPath = (String) payload.get("oldPath");
        String newPath = (String) payload.get("newPath");
        SftpClient sftp = session.getSftpClient();
        sftp.rename().from(oldPath).to(newPath).exec();

        String json = agent.mapper.writeValueAsString(Map.of(
                "type", "file_op",
                "sessionId", sessionId,
                "action", "rename",
                "oldPath", oldPath,
                "newPath", newPath,
                "success", true
        ));
        agent.sendToGateway(json);
        log.info("文件已重命名: {} -> {}", oldPath, newPath);
    }

    private void sendFileOpError(String sessionId, String action, String msg) {
        try {
            String json = agent.mapper.writeValueAsString(Map.of(
                    "type", "file_op",
                    "sessionId", sessionId,
                    "action", action,
                    "error", msg != null ? msg : "unknown error"
            ));
            agent.sendToGateway(json);
        } catch (Exception e) {
            log.warn("发送文件操作错误失败: {}", e.getMessage());
        }
    }

    private static class SshSession {
        private final String sessionId;
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final SshClient.TerminalOperation terminal;
        private SftpClient sftpClient;

        SshSession(String sessionId, String host, int port, String username, String password,
                   SshClient client, SshClient.TerminalOperation terminal) {
            this.sessionId = sessionId;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.terminal = terminal;
        }

        public SshClient.TerminalOperation getTerminal() {
            return terminal;
        }

        public synchronized SftpClient getSftpClient() throws Exception {
            if (sftpClient == null) {
                sftpClient = SftpClient.builder()
                        .host(host).port(port)
                        .username(username).password(password)
                        .connectTimeout(15000)
                        .build()
                        .connect();
            }
            return sftpClient;
        }

        public void close() {
            try { terminal.close(); } catch (Exception ignored) {}
            if (sftpClient != null) {
                try { sftpClient.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static class UploadContext {
        private final String uploadId;
        private final String remotePath;
        private final long fileSize;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private long receivedBytes;

        UploadContext(String uploadId, String remotePath, long fileSize) {
            this.uploadId = uploadId;
            this.remotePath = remotePath;
            this.fileSize = fileSize;
        }

        public synchronized void writeChunk(byte[] data) {
            buffer.write(data, 0, data.length);
            receivedBytes += data.length;
        }

        public byte[] getData() { return buffer.toByteArray(); }
        public String getRemotePath() { return remotePath; }
        public long getFileSize() { return fileSize; }
        public long getReceivedBytes() { return receivedBytes; }
    }
}
