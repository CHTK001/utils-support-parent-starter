package com.chua.common.support.network.ftp;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FTP 链式客户端（原生 JDK 实现，无第三方依赖）。
 *
 * <p>与 {@link FtpServer} 同源的原生实现：控制通道与数据通道均基于 {@link Socket}，
 * 不引入 Apache Commons Net。支持被动（PASV）与主动（PORT）两种数据连接模式、
 * 二进制/ASCII 传输模式切换，以及上传、下载、列目录、切换目录、建删目录、
 * 删文件、重命名、存在性探测、文件大小等常用操作。</p>
 *
 * <p>所有文件操作均采用链式 DSL 设计，与
 * {@code com.chua.ssh.support.client.SftpClient}、
 * {@code com.chua.ftp.support.client.FtpClient} 风格统一。</p>
 *
 * <pre>{@code
 * FtpClient ftp = FtpClient.builder()
 *         .host("192.168.1.100").port(21)
 *         .username("user").password("pass")
 *         .build().connect();
 *
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
 * ftp.close();
 * }</pre>
 *
 * <p>需要 FTPS（FTP over TLS）时请使用
 * {@code com.chua.ftp.support.client.FtpClient}（基于 Apache Commons Net）。</p>
 *
 * <p><b>线程安全</b>：单个 FTP 控制连接同一时刻只能处理一条命令，
 * 本实例<b>非线程安全</b>，多线程请各自持有实例。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
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
     * 默认读超时（毫秒）
     */
    private static final int DEFAULT_READ_TIMEOUT = 30000;

    /**
     * FTP 换行符（RFC 959 要求 CRLF）
     */
    private static final String CRLF = "\r\n";

    /**
     * PASV 响应中的六元组（h1,h2,h3,h4,p1,p2）
     */
    private static final Pattern PASV_PATTERN = Pattern.compile("(\\d+,\\d+,\\d+,\\d+),(\\d+),(\\d+)");

    /**
     * 拷贝缓冲区大小
     */
    private static final int COPY_BUFFER_SIZE = 8192;

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
     * 是否使用被动模式（true=PASV，false=PORT 主动模式）
     */
    private final boolean passive;

    /**
     * 是否使用二进制传输模式
     */
    private boolean binary;

    /**
     * 连接超时时间（毫秒）
     */
    private final int connectTimeout;

    /**
     * 读超时时间（毫秒）
     */
    private final int readTimeout;

    /**
     * 控制通道与数据通道文本编码
     */
    private final Charset charset;

    /**
     * 控制通道 Socket
     */
    private Socket controlSocket;

    /**
     * 控制通道输入（按行读取服务端回复）
     */
    private BufferedReader reader;

    /**
     * 控制通道输出（发送命令）
     */
    private Writer writer;

    /**
     * 最近一次服务端回复的完整文本（含多行），供调用侧解析
     */
    private String lastReply = "";

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
        this.readTimeout = b.readTimeout;
        this.charset = b.charset;
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
     * 快速创建 FTP 客户端。
     *
     * @param host     服务器地址
     * @param username 用户名
     * @param password 密码
     * @return 未连接的客户端实例
     */
    public static FtpClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    // ==================== 连接管理 ====================

    /**
     * 连接到 FTP 服务器并完成登录认证。
     *
     * @return 当前实例，支持链式调用
     */
    public FtpClient connect() {
        try {
            controlSocket = new Socket();
            controlSocket.connect(new InetSocketAddress(host, port), connectTimeout);
            controlSocket.setSoTimeout(readTimeout);
            reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), charset), COPY_BUFFER_SIZE);
            writer = new OutputStreamWriter(controlSocket.getOutputStream(), charset);

            int code = readReply();
            require(code == 220, "FTP 服务连接被拒绝: " + host + ":" + port + " -> " + lastReply);

            code = sendCommand("USER " + username);
            if (code == 331) {
                code = sendCommand("PASS " + (password == null ? "" : password));
            }
            require(code == 230 || code == 202,
                    "FTP 登录失败: " + username + "@" + host + ":" + port + " -> " + lastReply);

            if (binary) {
                code = sendCommand("TYPE I");
                require(code == 200, "设置二进制传输模式失败 -> " + lastReply);
            }
            log.info("FTP 连接成功: {}@{}:{} (passive={}, binary={})", username, host, port, passive, binary);
        } catch (FtpClientException e) {
            closeQuietly();
            throw e;
        } catch (Exception e) {
            closeQuietly();
            throw new FtpClientException("FTP 连接失败: " + host + ":" + port, e);
        }
        return this;
    }

    /**
     * 断开连接并释放资源。
     */
    public void disconnect() {
        try {
            if (isControlAlive()) {
                try {
                    writer.write("QUIT");
                    writer.write(CRLF);
                    writer.flush();
                } catch (Exception ignored) {
                    // 服务端可能已先行关闭，忽略
                }
            }
        } finally {
            closeQuietly();
            log.info("FTP 断开: {}@{}:{}", username, host, port);
        }
    }

    /**
     * 关闭连接（AutoCloseable 实现）。
     */
    @Override
    public void close() {
        disconnect();
    }

    /**
     * 控制通道是否仍然可用。
     *
     * @return true 表示已连接且未关闭
     */
    public boolean isConnected() {
        return isControlAlive();
    }

    private boolean isControlAlive() {
        return controlSocket != null && controlSocket.isConnected() && !controlSocket.isClosed();
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
            int code = sendCommand("PWD");
            require(code == 257, "获取当前目录失败 -> " + lastReply);
            return extractQuotedPath(lastReply);
        } catch (FtpClientException e) {
            throw e;
        } catch (Exception e) {
            throw new FtpClientException("获取当前目录失败", e);
        }
    }

    /**
     * 检查远程文件/目录是否存在。
     *
     * <p>先用 {@code SIZE} 探测文件，再回退 {@code CWD} 探测目录
     * （部分服务器对目录返回 550）。</p>
     *
     * @param path 远程路径
     * @return true 表示存在
     */
    public boolean exists(String path) {
        try {
            int code = sendCommand("SIZE " + path);
            if (code == 213) {
                return true;
            }
            if (code == 550) {
                  // SIZE 对目录通常返回 550，回退用 CWD 探测；先记下原目录，探测完切回，
                  // 避免改变控制连接的当前工作目录影响后续操作
                String previous = null;
                if (sendCommand("PWD") == 257) {
                    previous = extractQuotedPath(lastReply);
                }
                code = sendCommand("CWD " + path);
                if (code == 250) {
                    if (previous != null) {
                        sendCommand("CWD " + previous);
                    }
                    return true;
                }
                return false;
            }
            return false;
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
            int code = sendCommand("SIZE " + path);
            if (code != 213) {
                return -1L;
            }
            return Long.parseLong(lastReply.substring(4).trim().split("\\s+")[0]);
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
            int code = sendCommand(binary ? "TYPE I" : "TYPE A");
            require(code == 200, "设置传输类型失败 -> " + lastReply);
            this.binary = binary;
        } catch (FtpClientException e) {
            throw e;
        } catch (Exception e) {
            throw new FtpClientException("设置传输类型失败", e);
        }
        return this;
    }

    // ==================== UploadOperation ====================

    /**
     * 上传文件链式操作。
     *
     * <p>用法：{@code client.upload().local("/tmp/a.txt").remote("/data/a.txt").exec()}</p>
     *
     * @author CH
     * @since 4.0.0.51
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

        /**
         * Upload Operation
         *
         * @param client 参数
         */
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
                    in = new java.io.FileInputStream(localPath);
                }
                if (in == null) {
                    throw new FtpClientException("上传失败：未指定本地文件或输入流", null);
                }
                InputStream source = in;
                client.executeTransfer("STOR " + remotePath, dataSocket -> {
                    try (InputStream fis = source) {
                        copy(fis, dataSocket.getOutputStream());
                    }
                });
                log.info("上传完成: {} -> {}", localPath != null ? localPath : "stream", remotePath);
            } catch (FtpClientException e) {
                throw e;
            } catch (Exception e) {
                throw new FtpClientException("上传失败: " + (localPath != null ? localPath : remotePath), e);
            }
        }
    }

    // ==================== DownloadOperation ====================

    /**
     * 下载文件链式操作。
     *
     * @author CH
     * @since 4.0.0.51
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

        /**
         * Download Operation
         *
         * @param client 参数
         */
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
                OutputStream target = out;
                client.executeTransfer("RETR " + remotePath, dataSocket -> {
                    try (OutputStream fos = target) {
                        copy(new BufferedInputStream(dataSocket.getInputStream(), COPY_BUFFER_SIZE), fos);
                    }
                });
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
     * @since 4.0.0.51
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

        /**
         * List Operation
         *
         * @param client 参数
         */
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
         * <p>优先使用 {@code NLST}（纯名称）；服务器不支持时回退
         * {@code LIST} 并从行尾解析名称。</p>
         *
         * @return 文件/目录名称列表
         */
        public List<String> exec() {
            try {
                return client.readDataLines("NLST " + path);
            } catch (FtpClientException e) {
                log.debug("NLST 失败，回退 LIST: {}", e.getMessage());
                List<String> names = new ArrayList<>();
                for (String line : client.readDataLines("LIST " + path)) {
                    String name = parseListName(line);
                    if (name != null) {
                        names.add(name);
                    }
                }
                return names;
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
     * @since 4.0.0.51
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

        /**
         * Cd Operation
         *
         * @param client 参数
         */
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
                int code = client.sendCommand("CWD " + path);
                client.require(code == 250, "切换目录失败: " + path + " -> " + client.lastReply);
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
     * @since 4.0.0.51
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

        /**
         * Mkdir Operation
         *
         * @param client 参数
         */
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
                int code = client.sendCommand("MKD " + path);
                client.require(code == 257 || code == 250,
                        "创建目录失败: " + path + " -> " + client.lastReply);
                log.info("目录创建: {}", path);
            } catch (FtpClientException e) {
                throw e;
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
     * @since 4.0.0.51
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

        /**
         * Rm Operation
         *
         * @param client 参数
         */
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
                int code = client.sendCommand("DELE " + path);
                client.require(code == 250, "删除失败: " + path + " -> " + client.lastReply);
                log.info("删除完成: {}", path);
            } catch (FtpClientException e) {
                throw e;
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
     * @since 4.0.0.51
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

        /**
         * Rmdir Operation
         *
         * @param client 参数
         */
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
                int code = client.sendCommand("RMD " + path);
                client.require(code == 250, "删除目录失败: " + path + " -> " + client.lastReply);
                log.info("目录删除: {}", path);
            } catch (FtpClientException e) {
                throw e;
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
     * @since 4.0.0.51
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

        /**
         * Rename Operation
         *
         * @param client 参数
         */
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
                int code = client.sendCommand("RNFR " + oldPath);
                client.require(code == 350, "重命名失败（源不存在或不可改名）: " + oldPath
                        + " -> " + client.lastReply);
                code = client.sendCommand("RNTO " + newPath);
                client.require(code == 250, "重命名失败: " + oldPath + " -> " + newPath
                        + " -> " + client.lastReply);
                log.info("重命名: {} -> {}", oldPath, newPath);
            } catch (FtpClientException e) {
                throw e;
            } catch (Exception e) {
                throw new FtpClientException("重命名失败", e);
            }
        }
    }

    // ==================== 协议交互（包内可见，供操作类使用） ====================

    /**
     * 发送命令并读取回复码。
     *
     * @param command 命令（不含 CRLF）
     * @return 服务端回复码
     * @throws IOException 通道 IO 异常
     */
    int sendCommand(String command) throws IOException {
        if (!isControlAlive()) {
            throw new IOException("FTP 控制连接已断开");
        }
        // 密码不落日志
        log.debug("FTP > {}", command.startsWith("PASS") ? "PASS ****" : command);
        writer.write(command);
        writer.write(CRLF);
        writer.flush();
        return readReply();
    }

    /**
     * 读取一条服务端回复（支持多行）。
     *
     * <p>多行回复形如 {@code 220-...} 开头、{@code 220 ...} 结尾，
     * 中间行原样透传；结束行以「回复码 + 空格」开头。</p>
     *
     * @return 回复码
     * @throws IOException 通道 IO 异常或响应被截断
     */
    private int readReply() throws IOException {
        String line = reader.readLine();
        if (line == null || line.length() < 3) {
            throw new IOException("FTP 响应异常: " + line);
        }
        int code;
        try {
            code = Integer.parseInt(line.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new IOException("FTP 响应码无法解析: " + line);
        }
        StringBuilder text = new StringBuilder(line);
        String terminator = code + " ";
        while (!line.startsWith(terminator)) {
            line = reader.readLine();
            if (line == null) {
                throw new IOException("FTP 多行响应被截断（code=" + code + "）");
            }
            text.append('\n').append(line);
        }
        this.lastReply = text.toString();
        log.debug("FTP < {}", lastReply.replace("\n", " | "));
        return code;
    }

    /**
     * 建立数据连接并发起传输命令，传输完成后消费完成回复。
     *
     * <p>被动模式：{@code PASV} → 连接数据端口 → 发送传输命令（150/125）→ 传输 → 226。
     * 主动模式：本地监听 → {@code PORT} → 发送传输命令（150/125）→ accept → 传输 → 226。</p>
     *
     * @param command 传输命令（RETR/STOR/NLST/LIST ...）
     * @param handler 数据通道处理器
     */
    void executeTransfer(String command, TransferHandler handler) {
        ServerSocket acceptSocket = null;
        Socket dataSocket = null;
        try {
            if (passive) {
                int code = sendCommand("PASV");
                require(code == 227, "进入被动模式失败 -> " + lastReply);
                dataSocket = connectPassiveDataSocket();
            } else {
                acceptSocket = new ServerSocket(0);
                acceptSocket.setSoTimeout(readTimeout);
                int localPort = acceptSocket.getLocalPort();
                String localIp = controlSocket.getLocalAddress().getHostAddress().replace('.', ',');
                int code = sendCommand("PORT " + localIp + "," + ((localPort >> 8) & 0xFF) + "," + (localPort & 0xFF));
                require(code == 200, "设置主动模式失败 -> " + lastReply);
            }
            int code = sendCommand(command);
            if (code != 125 && code != 150) {
                throw new FtpClientException("FTP 数据通道建立失败: " + command + " -> " + lastReply, null);
            }
            if (dataSocket == null) {
                dataSocket = acceptSocket.accept();
                dataSocket.setSoTimeout(readTimeout);
            }
            handler.transfer(dataSocket);
            // 半关闭数据通道输出：向服务端表明本侧数据发送结束（EOF），
            // 服务端读到 EOF 才会结束传输并回复 226；随后再读控制通道完成回复。
            try {
                dataSocket.shutdownOutput();
            } catch (IOException ignore) {
                // 连接已被对端关闭等场景下忽略，按原样等待完成回复
            }
            code = readReply();
            require(code == 226 || code == 250, "FTP 传输未完成: " + command + " -> " + lastReply);
        } catch (FtpClientException e) {
            throw e;
        } catch (Exception e) {
            throw new FtpClientException("FTP 传输失败: " + command, e);
        } finally {
            closeQuietly(dataSocket);
            closeQuietly(acceptSocket);
        }
    }

    /**
     * 从 PASV 响应解析地址并连接数据通道。
     *
     * @return 已连接的数据通道 Socket
     * @throws IOException 解析失败或连接失败
     */
    private Socket connectPassiveDataSocket() throws IOException {
        Matcher matcher = PASV_PATTERN.matcher(lastReply);
        if (!matcher.find()) {
            throw new IOException("PASV 响应无法解析: " + lastReply);
        }
        String ip = matcher.group(1).replace(',', '.');
        int dataPort = Integer.parseInt(matcher.group(2)) * 256 + Integer.parseInt(matcher.group(3));
        Socket dataSocket = new Socket();
        dataSocket.connect(new InetSocketAddress(ip, dataPort), connectTimeout);
        dataSocket.setSoTimeout(readTimeout);
        return dataSocket;
    }

    /**
     * 执行返回文本行的传输命令（NLST/LIST）。
     *
     * @param command 传输命令
     * @return 数据通道按行返回的文本列表
     */
    List<String> readDataLines(String command) {
        List<String> lines = new ArrayList<>();
        executeTransfer(command, dataSocket -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(dataSocket.getInputStream(), charset), COPY_BUFFER_SIZE)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
        });
        return lines;
    }

    /**
     * 从 LIST 响应行解析文件/目录名称。
     *
     * @param line LIST 响应行
     * @return 名称；无法解析返回 null
     */
    private static String parseListName(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        // UNIX 风格：以权限位开头，第 9 段为名称（-rw-r--r-- 1 owner group 123 Jan 1 12:00 name）
        char first = line.charAt(0);
        if (first == '-' || first == 'd' || first == 'l' || first == 'p'
                || first == 's' || first == 'b' || first == 'c') {
            String[] parts = line.split("\\s+", 9);
            return parts.length == 9 ? parts[8] : null;
        }
        // Windows/DOS 风格：日期 时间 大小(或 <DIR>) 名称
        String[] parts = line.trim().split("\\s+", 4);
        return parts.length == 4 ? parts[3] : null;
    }

    /**
     * 从 PWD/MKD 响应中提取引号包裹的路径（{@code 257 "/data" ...}）。
     *
     * @param reply 服务端回复
     * @return 路径；解析失败返回 "/"
     */
    private static String extractQuotedPath(String reply) {
        int first = reply.indexOf('"');
        int second = first >= 0 ? reply.indexOf('"', first + 1) : -1;
        return first >= 0 && second > first ? reply.substring(first + 1, second) : "/";
    }

    /**
     * 断言条件成立，否则抛出 {@link FtpClientException}。
     *
     * @param condition 条件
     * @param message   异常消息
     */
    void require(boolean condition, String message) {
        if (!condition) {
            throw new FtpClientException(message, null);
        }
    }

    /**
     * 静默关闭控制通道资源。
     */
    private void closeQuietly() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
        try {
            if (controlSocket != null) {
                controlSocket.close();
            }
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
        reader = null;
        writer = null;
        controlSocket = null;
    }

    /**
     * 静默关闭单个 Socket/ServerSocket。
     *
     * @param socket 可关闭对象，可为 null
     */
    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 将输入流拷贝到输出流。
     *
     * @param in  输入流
     * @param out 输出流
     * @throws IOException 拷贝失败
     */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[COPY_BUFFER_SIZE];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        out.flush();
    }

    /**
     * 数据通道处理器。
     *
     * @author CH
     * @since 4.0.0.51
     */
    @FunctionalInterface
    interface TransferHandler {

        /**
         * 在数据通道上执行传输。
         *
         * @param dataSocket 数据通道 Socket
         * @throws Exception 传输失败
         */
        void transfer(Socket dataSocket) throws Exception;
    }

    // ==================== Builder ====================

    /**
     * FTP 客户端构建器。
     *
     * @author CH
     * @since 4.0.0.51
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
         * 读超时时间（毫秒），默认 30000
         */
        private int readTimeout = DEFAULT_READ_TIMEOUT;

        /**
         * 文本编码，默认 UTF-8
         */
        private Charset charset = StandardCharsets.UTF_8;

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
         * @param v true 使用被动模式（PASV），false 主动模式（PORT）
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
         * 设置读超时时间（毫秒）。
         *
         * @param t 超时毫秒数
         * @return 当前构建器
         */
        public Builder readTimeout(int t) {
            this.readTimeout = t;
            return this;
        }

        /**
         * 设置文本编码（控制通道与 LIST/NLST 数据通道）。
         *
         * @param c 字符集，默认 UTF-8
         * @return 当前构建器
         */
        public Builder charset(Charset c) {
            this.charset = c;
            return this;
        }

        /**
         * 构建 FTP 客户端实例。
         *
         * @return FTP 客户端实例
         * @throws IllegalArgumentException 参数不合法时抛出
         */
        public FtpClient build() {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host 不能为空");
            }
            if (username == null || username.isBlank()) {
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
     * @since 4.0.0.51
     */
    public static class FtpClientException extends RuntimeException {

        /**
         * 创建 ftp客户端异常 实例。
         *
         * @param message 消息
         * @param cause   原始异常，可为 null
         */
        public FtpClientException(String message, Throwable cause) {
            super(cause == null ? message : message + ": " + cause.getMessage(), cause);
        }
    }
}
