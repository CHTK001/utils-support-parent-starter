package com.chua.ssh.support.client;

import com.chua.common.support.network.protocol.client.FileClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * SFTP 链式客户端，基于 Apache MINA SSHD 3.x。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class SftpClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String privateKeyPath;
    private final int connectTimeout;

    private SshClient sshClient;
    private ClientSession session;
    private org.apache.sshd.sftp.client.SftpClient sftp;

    private SftpClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.privateKeyPath = b.privateKeyPath;
        this.connectTimeout = b.connectTimeout;
    }

    public static Builder builder() { return new Builder(); }
    public static SftpClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    public SftpClient connect() {
        try {
            sshClient = SshClient.setUpDefaultClient();
            sshClient.start();
            session = sshClient.connect(username, host, port)
                    .verify(connectTimeout, TimeUnit.SECONDS).getSession();
            if (password != null && !password.isEmpty()) {
                session.addPasswordIdentity(password);
            }
            if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
                var provider = new org.apache.sshd.common.keyprovider.FileKeyPairProvider(
                        java.nio.file.Path.of(privateKeyPath));
                for (java.security.KeyPair kp : provider.loadKeys(session)) {
                    session.addPublicKeyIdentity(kp);
                }
            }
            session.auth().verify(30, TimeUnit.SECONDS);
            sftp = org.apache.sshd.sftp.client.SftpClientFactory.instance().createSftpClient(session);
            log.info("SFTP 连接成功: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            throw new SftpClientException("SFTP 连接失败: " + host + ":" + port, e);
        }
        return this;
    }

    public void disconnect() {
        try {
            if (sftp != null) {
                sftp.close();
            }
            if (session != null) {
                session.close();
            }
            if (sshClient != null) {
                sshClient.stop();
            }
            log.info("SFTP 断开: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            log.warn("SFTP 断开异常: {}", e.getMessage());
        }
    }

    @Override
    public void close() { disconnect(); }

    public UploadOperation upload() { return new UploadOperation(this); }
    public DownloadOperation download() { return new DownloadOperation(this); }
    public ListOperation ls() { return new ListOperation(this); }
    public MkdirOperation mkdir() { return new MkdirOperation(this); }
    public RmOperation rm() { return new RmOperation(this); }
    public RenameOperation rename() { return new RenameOperation(this); }
    public StatOperation stat() { return new StatOperation(this); }

    @Getter
    public static class UploadOperation {
        private final SftpClient client;
        private String localPath;
        private String remotePath;
        UploadOperation(SftpClient client) { this.client = client; }
        public UploadOperation local(String p) { localPath = p; return this; }
        public UploadOperation remote(String p) { remotePath = p; return this; }
        public void exec() {
            try {
                byte[] data = Files.readAllBytes(Path.of(localPath));
                try (var handle = client.getSftp().open(remotePath,
                        org.apache.sshd.sftp.client.SftpClient.OpenMode.Create,
                        org.apache.sshd.sftp.client.SftpClient.OpenMode.Write)) {
                    client.getSftp().write(handle, 0, data);
                }
                log.info("上传完成: {} -> {}", localPath, remotePath);
            } catch (Exception e) { throw new SftpClientException("上传失败: " + localPath, e); }
        }
    }

    @Getter
    public static class DownloadOperation {
        private final SftpClient client;
        private String remotePath;
        private String localPath;
        DownloadOperation(SftpClient client) { this.client = client; }
        public DownloadOperation remote(String p) { remotePath = p; return this; }
        public DownloadOperation local(String p) { localPath = p; return this; }
        public void exec() {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                try (var handle = client.getSftp().open(remotePath,
                        org.apache.sshd.sftp.client.SftpClient.OpenMode.Read)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = client.getSftp().read(handle, baos.size(), buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                }
                Files.write(Path.of(localPath), baos.toByteArray());
                log.info("下载完成: {} -> {}", remotePath, localPath);
            } catch (Exception e) { throw new SftpClientException("下载失败: " + remotePath, e); }
        }
    }

    @Getter
    public static class ListOperation {
        private final SftpClient client;
        private String path = ".";
        ListOperation(SftpClient client) { this.client = client; }
        public ListOperation path(String p) { path = p; return this; }
        public List<Map<String, Object>> exec() {
            try {
                Iterable<org.apache.sshd.sftp.client.SftpClient.DirEntry> entries = client.getSftp().readDir(path);
                List<Map<String, Object>> result = new ArrayList<>();
                for (var entry : entries) {
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("name", entry.getFilename());
                    file.put("attributes", entry.getAttributes());
                    result.add(file);
                }
                return result;
            } catch (Exception e) { throw new SftpClientException("列出目录失败: " + path, e); }
        }
    }

    @Getter
    public static class MkdirOperation {
        private final SftpClient client;
        private String path;
        private boolean recursive = false;
        MkdirOperation(SftpClient client) { this.client = client; }
        public MkdirOperation path(String p) { path = p; return this; }
        public MkdirOperation recursive(boolean r) { recursive = r; return this; }
        public void exec() {
            try {
                if (recursive) {
                    for (String part : path.split("/")) {
                        if (part.isEmpty()) {
                            continue;
                        }
                        try { client.getSftp().mkdir(path); } catch (IOException ignored) {}
                    }
                } else {
                    client.getSftp().mkdir(path);
                }
                log.info("目录创建: {}", path);
            } catch (Exception e) { throw new SftpClientException("创建目录失败: " + path, e); }
        }
    }

    @Getter
    public static class RmOperation {
        private final SftpClient client;
        private String path;
        private boolean recursive = false;
        RmOperation(SftpClient client) { this.client = client; }
        public RmOperation path(String p) { path = p; return this; }
        public RmOperation recursive(boolean r) { recursive = r; return this; }
        public void exec() {
            try {
                if (recursive) {
                    client.getSftp().rmdir(path);
                } else {
                    client.getSftp().remove(path);
                }
                log.info("删除完成: {}", path);
            } catch (Exception e) {
                throw new SftpClientException("删除失败: " + path, e);
            }
        }
    }

    @Getter
    public static class RenameOperation {
        private final SftpClient client;
        private String oldPath;
        private String newPath;
        RenameOperation(SftpClient client) { this.client = client; }
        public RenameOperation from(String p) { oldPath = p; return this; }
        public RenameOperation to(String p) { newPath = p; return this; }
        public void exec() {
            try { client.getSftp().rename(oldPath, newPath); log.info("重命名: {} -> {}", oldPath, newPath); }
            catch (Exception e) { throw new SftpClientException("重命名失败", e); }
        }
    }

    @Getter
    public static class StatOperation {
        private final SftpClient client;
        private String path;
        StatOperation(SftpClient client) { this.client = client; }
        public StatOperation path(String p) { path = p; return this; }
        public Map<String, Object> exec() {
            try {
                var attrs = client.getSftp().stat(path);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("size", attrs.getSize());
                result.put("permissions", attrs.getPermissions());
                result.put("isDirectory", attrs.isDirectory());
                result.put("isRegularFile", attrs.isRegularFile());
                result.put("lastModifiedTime", attrs.getModifyTime());
                return result;
            } catch (Exception e) { throw new SftpClientException("获取状态失败: " + path, e); }
        }
     }

    public static class Builder {
        private String host;
        private int port = 22;
        private String username;
        private String password;
        private String privateKeyPath;
        private int connectTimeout = 30;
        public Builder host(String h) { host = h; return this; }
        public Builder port(int p) { port = p; return this; }
        public Builder username(String u) { username = u; return this; }
        public Builder password(String p) { password = p; return this; }
        public Builder privateKey(String path) { this.privateKeyPath = path; return this; }
        public Builder connectTimeout(int t) { connectTimeout = t; return this; }
        public SftpClient build() {
            if (host == null) {
                throw new IllegalArgumentException("host 不能为空");
            }
            if (username == null) {
                throw new IllegalArgumentException("username 不能为空");
            }
            return new SftpClient(this);
        }
    }

    public static class SftpClientException extends RuntimeException {
        public SftpClientException(String msg, Throwable cause) { super(msg, cause); }
    }
}
