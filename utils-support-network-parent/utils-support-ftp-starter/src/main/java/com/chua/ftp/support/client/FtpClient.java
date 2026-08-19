package com.chua.ftp.support.client;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP 链式客户端，基于 Apache Commons Net。
 *
 * <pre>{@code
 * FtpClient ftp = FtpClient.builder()
 *     .host("192.168.1.100").port(21)
 *     .username("ftpuser").password("ftppass")
 *     .build();
 *
 * // 上传文件
 * ftp.upload().local("/tmp/file.txt").remote("/data/file.txt").exec();
 *
 * // 下载文件
 * ftp.download().remote("/data/file.txt").local("/tmp/file.txt").exec();
 *
 * // 列出目录
 * List<String> files = ftp.ls().path("/data").exec();
 *
 * // 创建目录
 * ftp.mkdir().path("/data/new-dir").exec();
 *
 * // 删除
 * ftp.rm().path("/data/old.txt").exec();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class FtpClient implements AutoCloseable {

    /**
     * FTP 服务器主机地址
     */
    private final String host;
    /**
     * FTP 服务器端口号
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
     * 是否使用被动模式
     */
    private final boolean passive;
    /**
     * 是否使用二进制传输模式
     */
    private final boolean binary;
    /**
     * 连接超时时间（毫秒）
     */
    private final int connectTimeout;

    /**
     * Apache Commons Net FTP 客户端实例
     */
    private FTPClient ftpClient;

    /**
     * 创建 FtpClient 实例
     * @param b b
     */
    private FtpClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.passive = b.passive;
        this.binary = b.binary;
        this.connectTimeout = b.connectTimeout;
    }

    // ==================== 工厂方法 ====================

    /** Builder */
    public static Builder builder() { return new Builder(); }

    /** 创建 */
    public static FtpClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    // ==================== 连接管理 ====================

    /** 连接 */
    public FtpClient connect() {
        try {
            ftpClient = new FTPClient();
            ftpClient.setConnectTimeout(connectTimeout);
            ftpClient.connect(host, port);
            ftpClient.login(username, password);
            ftpClient.enterLocalPassiveMode();
            if (binary) {
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            }
            log.info("FTP 连接成功: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            throw new FtpClientException("FTP 连接失败: " + host + ":" + port, e);
        }
        return this;
    }

    /** 断开 */
    public void disconnect() {
        try {
            if (ftpClient != null && ftpClient.isConnected()) {
                ftpClient.logout();
                ftpClient.disconnect();
            }
            log.info("FTP 断开: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            log.warn("FTP 断开异常: {}", e.getMessage());
        }
    }

    @Override
    /** 关闭 */
    public void close() { disconnect(); }

    // ==================== 操作入口 ====================

    /** Upload */
    public UploadOperation upload() { return new UploadOperation(this); }
    /** Download */
    public DownloadOperation download() { return new DownloadOperation(this); }
    /** Ls */
    public ListOperation ls() { return new ListOperation(this); }
    /** 创建目录 */
    public MkdirOperation mkdir() { return new MkdirOperation(this); }
    /** Rm */
    public RmOperation rm() { return new RmOperation(this); }
    /** 重命名 */
    public RenameOperation rename() { return new RenameOperation(this); }
    /** 是否存在 */
    public boolean exists(String path) {
        try {
            FTPFile[] files = ftpClient.listFiles(path);
            return files != null && files.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    FTPClient getFtpClient() { return ftpClient; }

    // ==================== UploadOperation ====================

    @Getter
    public static class UploadOperation {
        /**
         * 关联的 FTP 客户端
         */
        private final FtpClient client;
        /**
         * 本地上传文件路径
         */
        private String localPath;
        /**
         * 远程目标文件路径
         */
        private String remotePath;

        UploadOperation(FtpClient client) { this.client = client; }

        /** Local */
        public UploadOperation local(String p) { this.localPath = p; return this; }
        /** Remote */
        public UploadOperation remote(String p) { this.remotePath = p; return this; }

        /** Exec */
        public void exec() {
            try {
                File file = new File(localPath);
                try (FileInputStream fis = new FileInputStream(file)) {
                    boolean success = client.getFtpClient().storeFile(remotePath, fis);
                    if (!success) {
                        throw new FtpClientException("上传失败: " + remotePath, null);
                    }
                }
                log.info("上传完成: {} -> {}", localPath, remotePath);
            } catch (FtpClientException e) {
                throw e;
            } catch (Exception e) {
                throw new FtpClientException("上传失败: " + localPath, e);
            }
        }
    }

    // ==================== DownloadOperation ====================

    @Getter
    public static class DownloadOperation {
        /**
         * 关联的 FTP 客户端
         */
        private final FtpClient client;
        /**
         * 远程文件路径
         */
        private String remotePath;
        /**
         * 本地保存文件路径
         */
        private String localPath;

        DownloadOperation(FtpClient client) { this.client = client; }

        /** Remote */
        public DownloadOperation remote(String p) { this.remotePath = p; return this; }
        /** Local */
        public DownloadOperation local(String p) { this.localPath = p; return this; }

        /** Exec */
        public void exec() {
            try {
                File file = new File(localPath);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    boolean success = client.getFtpClient().retrieveFile(remotePath, fos);
                    if (!success) {
                        throw new FtpClientException("下载失败: " + remotePath, null);
                    }
                }
                log.info("下载完成: {} -> {}", remotePath, localPath);
            } catch (FtpClientException e) {
                throw e;
            } catch (Exception e) {
                throw new FtpClientException("下载失败: " + remotePath, e);
            }
        }
    }

    // ==================== ListOperation ====================

    @Getter
    public static class ListOperation {
        /**
         * 关联的 FTP 客户端
         */
        private final FtpClient client;
        /**
         * 要列出的目录路径，默认当前目录
         */
        private String path = ".";

        ListOperation(FtpClient client) { this.client = client; }

        /** Path */
        public ListOperation path(String p) { this.path = p; return this; }

        /** Exec */
        public List<String> exec() {
            try {
                FTPFile[] files = client.getFtpClient().listFiles(path);
                List<String> result = new ArrayList<>();
                if (files != null) {
                    for (FTPFile file : files) {
                        result.add(file.getName());
                    }
                }
                return result;
            } catch (Exception e) {
                throw new FtpClientException("列出目录失败: " + path, e);
            }
        }
    }

    // ==================== MkdirOperation ====================

    @Getter
    public static class MkdirOperation {
        /**
         * 关联的 FTP 客户端
         */
        private final FtpClient client;
        /**
         * 要创建的目录路径
         */
        private String path;

        MkdirOperation(FtpClient client) { this.client = client; }

        /** Path */
        public MkdirOperation path(String p) { this.path = p; return this; }

        /** Exec */
        public void exec() {
            try {
                client.getFtpClient().makeDirectory(path);
                log.info("目录创建: {}", path);
            } catch (Exception e) {
                throw new FtpClientException("创建目录失败: " + path, e);
            }
        }
    }

    // ==================== RmOperation ====================

    @Getter
    public static class RmOperation {
        /**
         * 关联的 FTP 客户端
         */
        private final FtpClient client;
        /**
         * 要删除的文件路径
         */
        private String path;

        RmOperation(FtpClient client) { this.client = client; }

        /** Path */
        public RmOperation path(String p) { this.path = p; return this; }

        /** Exec */
        public void exec() {
            try {
                client.getFtpClient().deleteFile(path);
                log.info("删除完成: {}", path);
            } catch (Exception e) {
                throw new FtpClientException("删除失败: " + path, e);
            }
        }
    }

    // ==================== RenameOperation ====================

    @Getter
    public static class RenameOperation {
        /**
         * 关联的 FTP 客户端
         */
        private final FtpClient client;
        /**
         * 原文件名
         */
        private String oldPath;
        /**
         * 新文件名
         */
        private String newPath;

        RenameOperation(FtpClient client) { this.client = client; }

        /** From */
        public RenameOperation from(String p) { this.oldPath = p; return this; }
        /** To */
        public RenameOperation to(String p) { this.newPath = p; return this; }

        /** Exec */
        public void exec() {
            try {
                client.getFtpClient().rename(oldPath, newPath);
                log.info("重命名: {} -> {}", oldPath, newPath);
            } catch (Exception e) {
                throw new FtpClientException("重命名失败", e);
            }
        }
    }

    // ==================== Builder ====================

    public static class Builder {
        /**
         * FTP 服务器主机地址
         */
        private String host;
        /**
         * FTP 服务器端口号，默认 21
         */
        private int port = 21;
        /**
         * 登录用户名
         */
        private String username;
        /**
         * 登录密码
         */
        private String password;
        /**
         * 是否使用被动模式，默认 true
         */
        private boolean passive = true;
        /**
         * 是否使用二进制传输模式，默认 true
         */
        private boolean binary = true;
        /**
         * 连接超时时间（毫秒），默认 30000
         */
        private int connectTimeout = 30000;

        /** Host */
        public Builder host(String h) { this.host = h; return this; }
        /** Port */
        public Builder port(int p) { this.port = p; return this; }
        /** Username */
        public Builder username(String u) { this.username = u; return this; }
        /** Password */
        public Builder password(String p) { this.password = p; return this; }
        /** Passive */
        public Builder passive(boolean v) { this.passive = v; return this; }
        /** Binary */
        public Builder binary(boolean v) { this.binary = v; return this; }
        /** 连接Timeout */
        public Builder connectTimeout(int t) { this.connectTimeout = t; return this; }

        /** 构建 */
        public FtpClient build() {
            if (host == null) {
                throw new IllegalArgumentException("host 不能为空");
            }
            if (username == null) {
                throw new IllegalArgumentException("username 不能为空");
            }
            return new FtpClient(this);
        }
    }

    public static class FtpClientException extends RuntimeException {
        /**
         * 创建 FtpClientException 实例
         * @param msg msg
         * @param Throwable Throwable
         */
        public FtpClientException(String msg, Throwable cause) { super(msg, cause); }
    }
}
