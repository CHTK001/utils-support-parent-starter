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

    /**
     * 主机地址
     */
    private final String host;
    /**
     * 端口号
     */
    private final int port;
    /**
     * 登录用户名
     */
    private final String username;
    /**
     * 登录密码
     */
    private final String password;
    /**
     * 私募 键 路径
     */
    private final String privateKeyPath;
    /**
     * 连接超时时间（毫秒）
     */
    private final int connectTimeout;

    /**
     * ssh 客户端
     */
    private SshClient sshClient;
    /**
     * 会话对象
     */
    private ClientSession session;
    /**
     * sftp
     */
    private org.apache.sshd.sftp.client.SftpClient sftp;

    /**
     * 创建 sftp客户端 实例
     * @param b b
     */
    private SftpClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.privateKeyPath = b.privateKeyPath;
        this.connectTimeout = b.connectTimeout;
    }

    /**
     * 构建器
     *
     * @return 构建器的结果
     */
    public static Builder builder() { return new Builder(); }
    /**
     * 创建
     *
     * @param host 主机
     * @param username 用户名
     * @param password 密码
     * @return 创建的结果
     */
    public static SftpClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    /**
     * 连接
     *
     * @return 连接的结果
     */
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

    /** 断开 */
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
    /** 关闭 */
    public void close() { disconnect(); }

    /**
     * Upload
     *
     * @return upload的结果
     */
    public UploadOperation upload() { return new UploadOperation(this); }
    /**
     * Download
     *
     * @return download的结果
     */
    public DownloadOperation download() { return new DownloadOperation(this); }
    /**
     * Ls
     *
     * @return ls的结果
     */
    public ListOperation ls() { return new ListOperation(this); }
    /**
     * 创建目录
     *
     * @return mkdir的结果
     */
    public MkdirOperation mkdir() { return new MkdirOperation(this); }
    /**
     * Rm
     *
     * @return rm的结果
     */
    public RmOperation rm() { return new RmOperation(this); }
    /**
     * 重命名
     *
     * @return rename的结果
     */
    public RenameOperation rename() { return new RenameOperation(this); }
    /**
     * Stat
     *
     * @return stat的结果
     * @author CH
     * @since 4.0.0
     */
    public StatOperation stat() { return new StatOperation(this); }

    @Getter
    public static class UploadOperation {
        /**
         * 客户端实例
         */
        private final SftpClient client;
        /**
         * 本地文件路径
         */
        private String localPath;
        /**
         * 远程文件路径
         */
        private String remotePath;
        UploadOperation(SftpClient client) { this.client = client; }
        /**
         * 本地
         *
         * @param p p
         * @return 本地的结果
         */
        public UploadOperation local(String p) {
            localPath = p;
            return this;
        }
        /**
         * 远程
         *
         * @param p p
         * @return 远程的结果
         */
        public UploadOperation remote(String p) {
            remotePath = p;
            return this;
        }
        /** 执行 */
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
        /**
         * 客户端实例
         */
        private final SftpClient client;
        /**
         * 远程文件路径
         */
        private String remotePath;
        /**
         * 本地文件路径
         */
        private String localPath;
        DownloadOperation(SftpClient client) { this.client = client; }
        /**
         * 远程
         *
         * @param p p
         * @return 远程的结果
         */
        public DownloadOperation remote(String p) {
            remotePath = p;
            return this;
        }
        /**
         * 本地
         *
         * @param p p
         * @return 本地的结果
         */
        public DownloadOperation local(String p) {
            localPath = p;
            return this;
        }
        /** 执行 */
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
        /**
         * 客户端实例
         */
        private final SftpClient client;
        /**
         * 路径
         */
        private String path = ".";
        ListOperation(SftpClient client) { this.client = client; }
        /**
         * 路径
         *
         * @param p p
         * @return 路径的结果
         */
        public ListOperation path(String p) {
            path = p;
            return this;
        }
        /**
         * 执行
         *
         * @return 执行的结果
         * @author CH
         * @since 4.0.0
         */
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
        /**
         * 客户端实例
         */
        private final SftpClient client;
        /**
         * 路径
         */
        private String path;
        /**
         * recursive
         */
        private boolean recursive = false;
        MkdirOperation(SftpClient client) { this.client = client; }
        /**
         * 路径
         *
         * @param p p
         * @return 路径的结果
         */
        public MkdirOperation path(String p) {
            path = p;
            return this;
        }
        /**
         * Recursive
         *
         * @param r r
         * @return recursive的结果
         */
        public MkdirOperation recursive(boolean r) {
            recursive = r;
            return this;
        }
        /** 执行 */
        public void exec() {
            try {
                if (recursive) {
                    for (String part : path.split("/")) {
                        if (part.isEmpty()) {
                            continue;
                        }
                        try {
                            client.getSftp().mkdir(path);
                        } catch (IOException ignored) {}
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
        /**
         * 客户端实例
         */
        private final SftpClient client;
        /**
         * 路径
         */
        private String path;
        /**
         * recursive
         */
        private boolean recursive = false;
        RmOperation(SftpClient client) { this.client = client; }
        /**
         * 路径
         *
         * @param p p
         * @return 路径的结果
         */
        public RmOperation path(String p) {
            path = p;
            return this;
        }
        /**
         * Recursive
         *
         * @param r r
         * @return recursive的结果
         */
        public RmOperation recursive(boolean r) {
            recursive = r;
            return this;
        }
        /** 执行 */
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
        /**
         * 客户端实例
         */
        private final SftpClient client;
        /**
         * 原路径
         */
        private String oldPath;
        /**
         * 新路径
         */
        private String newPath;
        RenameOperation(SftpClient client) { this.client = client; }
        /**
         * 从
         *
         * @param p p
         * @return 从的结果
         */
        public RenameOperation from(String p) {
            oldPath = p;
            return this;
        }
        /**
         * 转为
         *
         * @param p p
         * @return 转为的结果
         */
        public RenameOperation to(String p) {
            newPath = p;
            return this;
        }
        /** 执行 */
        public void exec() {
            try {
                client.getSftp().rename(oldPath, newPath);
                log.info("重命名: {} -> {}", oldPath, newPath);
            } catch (Exception e) {
                throw new SftpClientException("重命名失败", e);
            }
        }
    }

    @Getter
    public static class StatOperation {
        /**
         * 客户端实例
         */
        private final SftpClient client;
        /**
         * 路径
         */
        private String path;
        StatOperation(SftpClient client) { this.client = client; }
        /**
         * 路径
         *
         * @param p p
         * @return 路径的结果
         */
        public StatOperation path(String p) {
            path = p;
            return this;
        }
        /**
         * 执行
         *
         * @return 执行的结果
         * @author CH
         * @since 4.0.0
         */
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
        /**
         * 主机地址
         */
        private String host;
        /**
         * 端口号
         */
        private int port = 22;
        /**
         * 登录用户名
         */
        private String username;
        /**
         * 登录密码
         */
        private String password;
        /**
         * 私募 键 路径
         */
        private String privateKeyPath;
        /**
         * 连接超时时间（毫秒）
         */
        private int connectTimeout = 30;
        /**
         * 主机
         *
         * @param h h
         * @return 主机的结果
         */
        public Builder host(String h) {
            host = h;
            return this;
        }
        /**
         * 端口
         *
         * @param p p
         * @return 端口的结果
         */
        public Builder port(int p) {
            port = p;
            return this;
        }
        /**
         * 用户名
         *
         * @param u u
         * @return 用户名的结果
         */
        public Builder username(String u) {
            username = u;
            return this;
        }
        /**
         * 密码
         *
         * @param p p
         * @return 密码的结果
         */
        public Builder password(String p) {
            password = p;
            return this;
        }
        /**
         * 私募键
         *
         * @param path 路径
         * @return 私募键的结果
         */
        public Builder privateKey(String path) {
            this.privateKeyPath = path;
            return this;
        }
        /**
         * 连接超时
         *
         * @param t t
         * @return 连接超时的结果
         */
        public Builder connectTimeout(int t) {
            connectTimeout = t;
            return this;
        }
        /**
         * 构建
         *
         * @return 构建的结果
         * @author CH
         * @since 4.0.0
         */
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
        /**
         * 创建 sftp客户端异常 实例
         * @param msg msg
         * @param cause Throwable
         * @param cause cause
         */
        public SftpClientException(String msg, Throwable cause) { super(msg, cause); }
    }
}
