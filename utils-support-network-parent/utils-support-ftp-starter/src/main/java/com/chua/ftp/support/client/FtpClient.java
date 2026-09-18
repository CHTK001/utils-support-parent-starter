package com.chua.ftp.support.client;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
* FTP/FTPS 链式客户端，基于 Apache Commons Net。
*
* <p>支持明文 FTP 与隐式/显式 FTPS 两种连接模式。
* 所有文件操作均采用链式 DSL 设计，与 {@link com.chua.ssh.support.client.SftpClient} 风格统一。</p>
*
* <pre>{@code
* // 明文 FTP
* FtpClient ftp = FtpClient.builder()
*         .host("192.168.1.100").port(21)
*         .username("user").password("pass")
*         .build().connect();
*
* // FTPS（显式 TLS）
* FtpClient ftps = FtpClient.builder()
*         .host("192.168.1.100").port(21)
*         .username("user").password("pass")
*         .ssl(true).implicit(false)
*         .build().connect();
*
* // 链式操作
* ftp.upload().local("/tmp/file.txt").remote("/data/file.txt").exec();
* ftp.download().remote("/data/file.txt").local("/tmp/file.txt").exec();
* ftp.ls().path("/data").exec();
* ftp.cd().path("/data/sub").exec();
* ftp.pwd();
* ftp.mkdir().path("/data/new-dir").exec();
* ftp.rm().path("/data/old.txt").exec();
* ftp.rmdir().path("/data/empty-dir").exec();
* ftp.rename().from("/data/a.txt").to("/data/b.txt").exec();
* ftp.exists("/data/file.txt");
* ftp.size("/data/file.txt");
* }</pre>ir().path("/data/empty-dir").exec();
* ftp.rename().from("/data/a.txt").to("/data/b.txt").exec();
* ftp.exists("/data/file.txt");
* ftp.size("/data/file.txt");
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Getter
public class FtpClient implements AutoCloseable {

    /**
    * 默认 FTP 端口
    */
    private static final int DEFAULT_FTP_PORT = 21;

    /**
    * 默认连接超时（毫秒）
    */
    private static final int DEFAULT_CONNECT_TIMEOUT = 30000;

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
    * 是否启用 SSL/TLS（FTPS 模式）
    */
    private final boolean ssl;

    /**
    * 是否隐式 SSL 模式（true=端口 990 直接 TLS，false=显式 认证 TLS）
    */
    private final boolean implicit;

    /**
    * Apache Commons Net FTP 客户端实例
    */
    private FTPClient ftpClient;

    /**
    * 创建 ftp客户端 实例。
    *
    * @param b 构建器
    */
    private FtpClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.passive = b.passive;
        this.binary = b.binary;
        this.connectTimeout = b.connectTimeout;
        this.ssl = b.ssl;
        this.implicit = b.implicit;
    }

    // ==================== 工厂方法 ====================

    /**
    * 创建构建器。
    *
    * @return 新构建器实例
    */
    public static Builder builder() {
        return new Builder();
    }

    /**
    * 快速创建明文 FTP 客户端。
    *
    * @param host     服务器地址
    * @param username 用户名
    * @param password 密码
    * @return 未连接的客户端实例
    */
    public static FtpClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    /**
    * 快速创建 FTPS 客户端（显式 TLS）。
    *
    * @param host     服务器地址
    * @param username 用户名
    * @param password 密码
    * @return 未连接的客户端实例
    */
    public static FtpClient createFtps(String host, String username, String password) {
        return builder().host(host).username(username).password(password).ssl(true).build();
    }

    // ==================== 连接管理 ====================

    /**
    * 连接到 FTP/FTPS 服务器并完成登录认证。
    *
    * @return 当前实例，支持链式调用
    */
    public FtpClient connect() {
        try {
            if (ssl) {
                ftpClient = new FTPSClient(!implicit);
            } else {
                ftpClient = new FTPClient();
            }
            ftpClient.setConnectTimeout(connectTimeout);
            ftpClient.connect(host, port);
            ftpClient.login(username, password);
            ftpClient.enterLocalPassiveMode();
            if (binary) {
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            }
            log.info("FTP 连接成功: {}@{}:{} (ssl={}, implicit={})", username, host, port, ssl, implicit);
        } catch (Exception e) {
            throw new FtpClientException("FTP 连接失败: " + host + ":" + port, e);
        }
        return this;
    }

    /**
    * 断开连接并释放资源。
    */
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
    /**
    * 关闭连接（auto关闭 实现）。
    */
    public void close() {
        disconnect();
    }

    // ==================== 链式操作入口 ====================

    /**
    * 上传文件操作。
    *
    * @return 上传操作实例
    */
    public UploadOperation upload() {
        return new UploadOperation(this);
    }

    /**
    * 下载文件操作。
    *
    * @return 下载操作实例
    */
    public DownloadOperation download() {
        return new DownloadOperation(this);
    }

    /**
    * 列出目录内容操作。
    *
    * @return 列目录操作实例
    */
    public ListOperation ls() {
        return new ListOperation(this);
    }

    /**
    * 切换目录操作。
    *
    * @return 切换目录操作实例
    */
    public CdOperation cd() {
        return new CdOperation(this);
    }

    /**
    * 创建目录操作。
    *
    * @return 创建目录操作实例
    */
    public MkdirOperation mkdir() {
        return new MkdirOperation(this);
    }

    /**
    * 删除文件操作。
    *
    * @return 删除文件操作实例
    */
    public RmOperation rm() {
        return new RmOperation(this);
    }

    /**
    * 删除目录操作。
    *
    * @return 删除目录操作实例
    */
    public RmdirOperation rmdir() {
        return new RmdirOperation(this);
    }

    /**
    * 重命名文件/目录操作。
    *
    * @return 重命名操作实例
    */
    public RenameOperation rename() {
        return new RenameOperation(this);
    }

    /**
    * 获取当前工作目录。
    *
    * @return 当前工作目录路径
    */
    public String pwd() {
        try {
            return ftpClient.printWorkingDirectory();
        } catch (Exception e) {
            throw new FtpClientException("获取当前目录失败", e);
        }
    }

    /**
    * 检查远程文件/目录是否存在。
    *
    * @param path 远程路径
    * @return true 表示存在
    */
    public boolean exists(String path) {
        try {
            FTPFile[] files = ftpClient.listFiles(path);
            return files != null && files.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
    * 获取远程文件大小（字节）。
    *
    * @param path 远程文件路径
    * @return 文件大小，获取失败返回 -1
    */
    public long size(String path) {
        try {
            return ftpClient.listFiles(path)[0].getSize();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
    * 设置传输类型。
    *
    * @param binary true 二进制模式，false ASCII 模式
    * @return 当前实例，支持链式调用
    */
    public FtpClient setBinary(boolean binary) {
        try {
            ftpClient.setFileType(binary ? FTP.BINARY_FILE_TYPE : FTP.ASCII_FILE_TYPE);
        } catch (Exception e) {
            throw new FtpClientException("设置传输类型失败", e);
        }
        return this;
    }

    /**
    * 获取底层 ftp客户端（高级用法）。
    *
    * @return Apache Commons Net ftp客户端 实例
    */
    FTPClient getFtpClient() {
        return ftpClient;
    }

    // ==================== UploadOperation ====================

    /**
    * 上传文件链式操作。
    *
    * <p>用法：{@code client.upload().local("/tmp/a.txt").remote("/data/a.txt").exec()}</p>
    *
    * @author CH
    * @since 4.0.0.42
    */
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

        /**
        * 本地输入流（与 本地路径 二选一）
        */
        private InputStream inputStream;

        UploadOperation(FtpClient client) {
            this.client = client;
        }

        /**
        * 设置本地文件路径。
        *
        * @param p 本地文件路径
        * @return 当前操作实例
        */
        public UploadOperation local(String p) {
            this.localPath = p;
            return this;
        }

        /**
        * 设置远程目标路径。
        *
        * @param p 远程文件路径
        * @return 当前操作实例
        */
        public UploadOperation remote(String p) {
            this.remotePath = p;
            return this;
        }

        /**
        * 使用输入流上传（与 本地 二选一）。
        *
        * @param in 输入流
        * @return 当前操作实例
        */
        public UploadOperation stream(InputStream in) {
            this.inputStream = in;
            return this;
        }

        /**
        * 执行上传操作。
        */
        public void exec() {
            try {
                InputStream in = inputStream;
                if (in == null && localPath != null) {
                    in = new FileInputStream(localPath);
                }
                if (in == null) {
                    throw new FtpClientException("上传失败：未指定本地文件或输入流", null);
                }
                try (InputStream fis = in) {
                    boolean success = client.getFtpClient().storeFile(remotePath, fis);
                    if (!success) {
                        throw new FtpClientException("上传失败: " + remotePath, null);
                    }
                }
                log.info("上传完成: {} -> {}", localPath != null ? localPath : "stream", remotePath);
            } catch (FtpClientException e) {
                throw e;
            } catch (Exception e) {
                throw new FtpClientException("上传失败: " + localPath, e);
            }
        }
    }

    // ==================== DownloadOperation ====================

    /**
    * 下载文件链式操作。
    *
    * @author CH
    * @since 4.0.0.42
    */
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

        /**
        * 本地输出流（与 本地路径 二选一）
        */
        private OutputStream outputStream;

        DownloadOperation(FtpClient client) {
            this.client = client;
        }

        /**
        * 设置远程文件路径。
        *
        * @param p 远程文件路径
        * @return 当前操作实例
        */
        public DownloadOperation remote(String p) {
            this.remotePath = p;
            return this;
        }

        /**
        * 设置本地保存路径。
        *
        * @param p 本地文件路径
        * @return 当前操作实例
        */
        public DownloadOperation local(String p) {
            this.localPath = p;
            return this;
        }

        /**
        * 使用输出流下载（与 本地 二选一）。
        *
        * @param out 输出流
        * @return 当前操作实例
        */
        public DownloadOperation stream(OutputStream out) {
            this.outputStream = out;
            return this;
        }

        /**
        * 执行下载操作。
        */
        public void exec() {
            try {
                OutputStream out = outputStream;
                if (out == null && localPath != null) {
                    // 确保父目录存在
                    var parent = Path.of(localPath).getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    out = new FileOutputStream(localPath);
                }
                if (out == null) {
                    throw new FtpClientException("下载失败：未指定本地文件或输出流", null);
                }
                try (OutputStream fos = out) {
                    boolean success = client.getFtpClient().retrieveFile(remotePath, fos);
                    if (!success) {
                        throw new FtpClientException("下载失败: " + remotePath, null);
                    }
                }
                log.info("下载完成: {} -> {}", remotePath, localPath != null ? localPath : "stream");
            } catch (FtpClientException e) {
                throw e;
            } catch (Exception e) {
                throw new FtpClientException("下载失败: " + remotePath, e);
            }
        }
    }

    // ==================== ListOperation ====================

    /**
    * 列出目录内容链式操作。
    *
    * @author CH
    * @since 4.0.0.42
    */
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

        ListOperation(FtpClient client) {
            this.client = client;
        }

        /**
        * 设置目标目录路径。
        *
        * @param p 目录路径
        * @return 当前操作实例
        */
        public ListOperation path(String p) {
            this.path = p;
            return this;
        }

        /**
        * 执行列目录操作。
        *
        * @return 文件/目录名称列表
        */
        public List<String> exec() {
            try {
                FTPFile[] files = client.getFtpClient().listFiles(path);
                var result = new ArrayList<String>();
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

    // ==================== CdOperation ====================

    /**
    * 切换远程工作目录链式操作。
    *
    * @author CH
    * @since 4.0.0.43
    */
    @Getter
    public static class CdOperation {

        /**
        * 关联的 FTP 客户端
        */
        private final FtpClient client;

        /**
        * 目标目录路径
        */
        private String path;

        CdOperation(FtpClient client) {
            this.client = client;
        }

        /**
        * 设置目标目录路径。
        *
        * @param p 目录路径
        * @return 当前操作实例
        */
        public CdOperation path(String p) {
            this.path = p;
            return this;
        }

        /**
        * 执行切换目录操作。
        *
        * @return 当前操作实例，支持链式调用
        */
        public CdOperation exec() {
            try {
                boolean success = client.getFtpClient().changeWorkingDirectory(path);
                if (!success) {
                    throw new FtpClientException("切换目录失败: " + path, null);
                }
                log.debug("切换目录: {}", path);
            } catch (FtpClientException e) {
                throw e;
            } catch (Exception e) {
                throw new FtpClientException("切换目录失败: " + path, e);
            }
            return this;
        }
    }

    // ==================== MkdirOperation ====================

    /**
    * 创建目录链式操作。
    *
    * @author CH
    * @since 4.0.0.42
    */
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

        MkdirOperation(FtpClient client) {
            this.client = client;
        }

        /**
        * 设置要创建的目录路径。
        *
        * @param p 目录路径
        * @return 当前操作实例
        */
        public MkdirOperation path(String p) {
            this.path = p;
            return this;
        }

        /**
        * 执行创建目录操作。
        */
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

    /**
    * 删除文件链式操作。
    *
    * @author CH
    * @since 4.0.0.42
    */
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

        RmOperation(FtpClient client) {
            this.client = client;
        }

        /**
        * 设置要删除的文件路径。
        *
        * @param p 文件路径
        * @return 当前操作实例
        */
        public RmOperation path(String p) {
            this.path = p;
            return this;
        }

        /**
        * 执行删除文件操作。
        */
        public void exec() {
            try {
                client.getFtpClient().deleteFile(path);
                log.info("删除完成: {}", path);
            } catch (Exception e) {
                throw new FtpClientException("删除失败: " + path, e);
            }
        }
    }

    // ==================== RmdirOperation ====================

    /**
    * 删除目录链式操作。
    *
    * @author CH
    * @since 4.0.0.43
    */
    @Getter
    public static class RmdirOperation {

        /**
        * 关联的 FTP 客户端
        */
        private final FtpClient client;

        /**
        * 要删除的目录路径
        */
        private String path;

        RmdirOperation(FtpClient client) {
            this.client = client;
        }

        /**
        * 设置要删除的目录路径。
        *
        * @param p 目录路径
        * @return 当前操作实例
        */
        public RmdirOperation path(String p) {
            this.path = p;
            return this;
        }

        /**
        * 执行删除目录操作。
        */
        public void exec() {
            try {
                client.getFtpClient().removeDirectory(path);
                log.info("目录删除: {}", path);
            } catch (Exception e) {
                throw new FtpClientException("删除目录失败: " + path, e);
            }
        }
    }

    // ==================== RenameOperation ====================

    /**
    * 重命名文件/目录链式操作。
    *
    * @author CH
    * @since 4.0.0.42
    */
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

        RenameOperation(FtpClient client) {
            this.client = client;
        }

        /**
        * 设置原文件路径。
        *
        * @param p 原路径
        * @return 当前操作实例
        */
        public RenameOperation from(String p) {
            this.oldPath = p;
            return this;
        }

        /**
        * 设置新文件路径。
        *
        * @param p 新路径
        * @return 当前操作实例
        */
        public RenameOperation to(String p) {
            this.newPath = p;
            return this;
        }

        /**
        * 执行重命名操作。
        */
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

    /**
    * FTP 客户端构建器。
    *
    * @author CH
    * @since 4.0.0.42
    */
    public static class Builder {

        /**
        * FTP 服务器主机地址
        */
        private String host;

        /**
        * FTP 服务器端口号，默认 21
        */
        private int port = DEFAULT_FTP_PORT;

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
        private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;

        /**
        * 是否启用 SSL/TLS（FTPS 模式），默认 false
        */
        private boolean ssl;

        /**
        * 是否隐式 SSL 模式，默认 false（显式）
        */
        private boolean implicit;

        /**
        * 设置服务器地址。
        *
        * @param h 服务器地址
        * @return 当前构建器
        */
        public Builder host(String h) {
            this.host = h;
            return this;
        }

        /**
        * 设置服务器端口。
        *
        * @param p 端口号
        * @return 当前构建器
        */
        public Builder port(int p) {
            this.port = p;
            return this;
        }

        /**
        * 设置登录用户名。
        *
        * @param u 用户名
        * @return 当前构建器
        */
        public Builder username(String u) {
            this.username = u;
            return this;
        }

        /**
        * 设置登录密码。
        *
        * @param p 密码
        * @return 当前构建器
        */
        public Builder password(String p) {
            this.password = p;
            return this;
        }

        /**
        * 设置是否使用被动模式。
        *
        * @param v true 使用被动模式
        * @return 当前构建器
        */
        public Builder passive(boolean v) {
            this.passive = v;
            return this;
        }

        /**
        * 设置是否使用二进制传输模式。
        *
        * @param v true 使用二进制模式
        * @return 当前构建器
        */
        public Builder binary(boolean v) {
            this.binary = v;
            return this;
        }

        /**
        * 设置连接超时时间（毫秒）。
        *
        * @param t 超时毫秒数
        * @return 当前构建器
        */
        public Builder connectTimeout(int t) {
            this.connectTimeout = t;
            return this;
        }

        /**
        * 设置是否启用 SSL/TLS（FTPS 模式）。
        *
        * @param v true 启用 FTPS
        * @return 当前构建器
        */
        public Builder ssl(boolean v) {
            this.ssl = v;
            return this;
        }

        /**
        * 设置是否隐式 SSL 模式。
        *
        * @param v true 隐式模式（端口 990 直接 TLS）
        * @return 当前构建器
        */
        public Builder implicit(boolean v) {
            this.implicit = v;
            return this;
        }

        /**
        * 构建 FTP 客户端实例。
        *
        * @return FTP 客户端实例
        * @throws IllegalArgumentException 参数不合法时抛出
        */
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

    // ==================== 异常 ====================

    /**
    * FTP 客户端操作异常。
    *
    * @author CH
    * @since 4.0.0.42
    */
    public static class FtpClientException extends RuntimeException {

        /**
        * 创建 ftp客户端异常 实例。
        *
        * @param msg   异常消息
        * @param cause 原始异常
        */
        public FtpClientException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
