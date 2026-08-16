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

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean passive;
    private final boolean binary;
    private final int connectTimeout;

    private FTPClient ftpClient;

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

    public static Builder builder() { return new Builder(); }

    public static FtpClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    // ==================== 连接管理 ====================

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
    public void close() { disconnect(); }

    // ==================== 操作入口 ====================

    public UploadOperation upload() { return new UploadOperation(this); }
    public DownloadOperation download() { return new DownloadOperation(this); }
    public ListOperation ls() { return new ListOperation(this); }
    public MkdirOperation mkdir() { return new MkdirOperation(this); }
    public RmOperation rm() { return new RmOperation(this); }
    public RenameOperation rename() { return new RenameOperation(this); }
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
        private final FtpClient client;
        private String localPath;
        private String remotePath;

        UploadOperation(FtpClient client) { this.client = client; }

        public UploadOperation local(String p) { this.localPath = p; return this; }
        public UploadOperation remote(String p) { this.remotePath = p; return this; }

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
        private final FtpClient client;
        private String remotePath;
        private String localPath;

        DownloadOperation(FtpClient client) { this.client = client; }

        public DownloadOperation remote(String p) { this.remotePath = p; return this; }
        public DownloadOperation local(String p) { this.localPath = p; return this; }

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
        private final FtpClient client;
        private String path = ".";

        ListOperation(FtpClient client) { this.client = client; }

        public ListOperation path(String p) { this.path = p; return this; }

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
        private final FtpClient client;
        private String path;

        MkdirOperation(FtpClient client) { this.client = client; }

        public MkdirOperation path(String p) { this.path = p; return this; }

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
        private final FtpClient client;
        private String path;

        RmOperation(FtpClient client) { this.client = client; }

        public RmOperation path(String p) { this.path = p; return this; }

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
        private final FtpClient client;
        private String oldPath;
        private String newPath;

        RenameOperation(FtpClient client) { this.client = client; }

        public RenameOperation from(String p) { this.oldPath = p; return this; }
        public RenameOperation to(String p) { this.newPath = p; return this; }

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
        private String host;
        private int port = 21;
        private String username;
        private String password;
        private boolean passive = true;
        private boolean binary = true;
        private int connectTimeout = 30000;

        public Builder host(String h) { this.host = h; return this; }
        public Builder port(int p) { this.port = p; return this; }
        public Builder username(String u) { this.username = u; return this; }
        public Builder password(String p) { this.password = p; return this; }
        public Builder passive(boolean v) { this.passive = v; return this; }
        public Builder binary(boolean v) { this.binary = v; return this; }
        public Builder connectTimeout(int t) { this.connectTimeout = t; return this; }

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
        public FtpClientException(String msg, Throwable cause) { super(msg, cause); }
    }
}
