package com.chua.common.support.network.filepush;

import java.nio.file.Path;
import java.util.List;

/**
 * 目录推送（文件同步）配置。
 *
 * <p>客户端与服务端共享的运行时参数。除 {@link #sourceDir} 为客户端独有、
 * {@link #targetDir} 为服务端独有外，其余参数两端一致。</p>
 *
 * <p>所有线程数与分片参数均有 0/负数 保护：0 或负数时自动回退到默认值，
 * 默认值基于 CPU 核数计算，单机即可跑满带宽。</p>
 *
 * <p><b>链式装配</b>：全部 setter 返回 {@code this}，
 * {@link #loadFromSystemProperties(FilePushConfig)} 也返回入参配置本身，
 * 因此服务端与客户端参数都能一行装配完：</p>
 * <pre>
 * // 服务端：接收目录 + 端口 + 允许清理
 * FilePushConfig serverCfg = FilePushConfig.defaults()
 *         .setTargetDir(Path.of("/data/received"))
 *         .setHost("0.0.0.0").setPort(9777)
 *         .setCleanup(true);
 *
 * // 客户端：先吃系统属性，再链式覆盖并开启增量
 * FilePushConfig clientCfg = FilePushConfig.loadFromSystemProperties(FilePushConfig.defaults())
 *         .setSourceDir(Path.of("/data/app"))
 *         .setPort(9777).setChunkSize(4 * 1024 * 1024)
 *         .setIncremental(true).setExcludes(List.of("log", "tmp/"));
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FilePushConfig {

    /** 默认监听主机 */
    public static final String DEFAULT_HOST = "0.0.0.0";

    /** 默认监听端口 */
    public static final int DEFAULT_PORT = 9777;

    /** 默认分片大小（字节），1 MB */
    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;

    /** 目录推送协议魔数，占 4 字节，防止误连到其他 TCP 服务 */
    public static final int MAGIC = 0x46505553;

    /** 协议版本号，占 1 字节 */
    public static final int PROTOCOL_VERSION = 1;

    /** 消息类型：开始传输一个文件 */
    public static final byte MSG_BEGIN = 0x01;

    /** 消息类型：文件数据分片 */
    public static final byte MSG_CHUNK = 0x02;

    /** 消息类型：单个文件传输完成 */
    public static final byte MSG_END = 0x03;

    /** 消息类型：全部文件传输完成（该会话） */
    public static final byte MSG_DONE = 0x04;

    /** 消息类型：ACK（服务端 → 客户端） */
    public static final byte MSG_ACK = 0x10;

    /** 消息类型：NACK / 错误（服务端 → 客户端） */
    public static final byte MSG_ERROR = 0x11;

    /** 消息类型：会话清理指令（客户端 → 服务端，删除目标目录中本地已不存在的文件） */
    public static final byte MSG_CLEANUP = 0x12;

    /** 消息类型：索取目标目录现有文件清单（客户端 → 服务端，用于增量同步） */
    public static final byte MSG_MANIFEST = 0x13;

    /** 消息类型：目标目录文件清单响应（服务端 → 客户端） */
    public static final byte MSG_MANIFEST_RESP = 0x14;

    /** 文件总数，占 4 字节 */
    private int fileCount = -1;

    /** 源目录（客户端推送目录） */
    private Path sourceDir;

    /** 目标目录（服务端接收目录） */
    private Path targetDir;

    /** 监听/连接主机 */
    private String host = DEFAULT_HOST;

    /** 监听/连接端口 */
    private int port = DEFAULT_PORT;

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 10_000;

    /** 读超时（毫秒），覆盖最慢分片的传输等待 */
    private int readTimeoutMs = 60_000;

    /** 分片大小（字节），0/负数 回退默认 1 MB */
    private int chunkSize = DEFAULT_CHUNK_SIZE;

    /** 客户端并发文件数（默认 CPU × 4，上限 256） */
    private int clientFileParallelism = 0;

    /** 服务端并发连接数（默认 CPU × 4，上限 256） */
    private int serverConnectionParallelism = 0;

    /** 服务端写盘并发数（默认 CPU，IO 型） */
    private int serverWriteParallelism = 0;

    /** 客户端 IO 缓冲大小（字节），0/负数 回退 64 KB */
    private int ioBufferSize = 0;

    /** 是否清理目标目录中本次未推送的旧文件 */
    private boolean cleanup;

    /**
     * 是否启用增量同步。
     *
     * <p>开启后客户端先索取服务端目标目录清单，跳过 size 与 mtime 均未变化的文件。
     * 依赖服务端在落盘后保留源文件的 mtime。</p>
     */
    private boolean incremental;

    /** 排除模式列表（子串匹配，如 {@code log}、{@code tmp/}），空表示不排除 */
    private List<String> excludes;

    /** 包含模式列表（子串匹配），空表示全部包含 */
    private List<String> includes;

    /**
     * 归一化分片大小。
     *
     * @return 有效分片大小（字节）
     */
    public int effectiveChunkSize() {
        return chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
    }

    /**
     * 归一化 IO 缓冲大小。
     *
     * @return 有效缓冲大小（字节）
     */
    public int effectiveIoBufferSize() {
        return ioBufferSize > 0 ? ioBufferSize : 64 * 1024;
    }

    /**
     * 归一化客户端并发文件数。
     *
     * @return 有效并发数
     */
    public int effectiveClientParallelism() {
        int p = clientFileParallelism;
        if (p <= 0) {
            p = Math.min(256, Runtime.getRuntime().availableProcessors() * 4);
        }
        return p;
    }

    /**
     * 归一化服务端并发连接数。
     *
     * @return 有效并发数
     */
    public int effectiveServerParallelism() {
        int p = serverConnectionParallelism;
        if (p <= 0) {
            p = Math.min(256, Runtime.getRuntime().availableProcessors() * 4);
        }
        return p;
    }

    /**
     * 归一化服务端写盘并发数。
     *
     * @return 有效并发数
     */
    public int effectiveWriteParallelism() {
        int p = serverWriteParallelism;
        if (p <= 0) {
            p = Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        return p;
    }

    /**
     * 从系统属性加载配置。
     *
     * <p>属性前缀 {@code filepush.}，支持：</p>
     * <ul>
     *   <li>{@code filepush.host} / {@code filepush.port}</li>
     *   <li>{@code filepush.source-dir} / {@code filepush.target-dir}</li>
     *   <li>{@code filepush.chunk-size} / {@code filepush.io-buffer-size}</li>
     *   <li>{@code filepush.client-parallelism} / {@code filepush.server-parallelism}</li>
     *   <li>{@code filepush.cleanup} / {@code filepush.incremental}</li>
     *   <li>{@code filepush.excludes} / {@code filepush.includes}（逗号分隔的子串模式）</li>
     * </ul>
     *
     * @param config 待填充的配置
     * @return 传入的配置本身，便于继续链式装配
     */
    public static FilePushConfig loadFromSystemProperties(FilePushConfig config) {
        String sourceDir = System.getProperty("filepush.source-dir");
        if (sourceDir != null && !sourceDir.isBlank()) {
            config.setSourceDir(Path.of(sourceDir));
        }
        String targetDir = System.getProperty("filepush.target-dir");
        if (targetDir != null && !targetDir.isBlank()) {
            config.setTargetDir(Path.of(targetDir));
        }
        String host = System.getProperty("filepush.host");
        if (host != null && !host.isBlank()) {
            config.setHost(host);
        }
        String port = System.getProperty("filepush.port");
        if (port != null && !port.isBlank()) {
            config.setPort(Integer.parseInt(port.trim()));
        }
        String chunkSize = System.getProperty("filepush.chunk-size");
        if (chunkSize != null && !chunkSize.isBlank()) {
            config.setChunkSize(Integer.parseInt(chunkSize.trim()));
        }
        String ioBuffer = System.getProperty("filepush.io-buffer-size");
        if (ioBuffer != null && !ioBuffer.isBlank()) {
            config.setIoBufferSize(Integer.parseInt(ioBuffer.trim()));
        }
        String clientP = System.getProperty("filepush.client-parallelism");
        if (clientP != null && !clientP.isBlank()) {
            config.setClientFileParallelism(Integer.parseInt(clientP.trim()));
        }
        String serverP = System.getProperty("filepush.server-parallelism");
        if (serverP != null && !serverP.isBlank()) {
            config.setServerConnectionParallelism(Integer.parseInt(serverP.trim()));
        }
        String writeP = System.getProperty("filepush.server-write-parallelism");
        if (writeP != null && !writeP.isBlank()) {
            config.setServerWriteParallelism(Integer.parseInt(writeP.trim()));
        }
        String cleanup = System.getProperty("filepush.cleanup");
        if (cleanup != null && !cleanup.isBlank()) {
            config.setCleanup(Boolean.parseBoolean(cleanup.trim()));
        }
        String incremental = System.getProperty("filepush.incremental");
        if (incremental != null && !incremental.isBlank()) {
            config.setIncremental(Boolean.parseBoolean(incremental.trim()));
        }
        List<String> excludes = splitPatterns(System.getProperty("filepush.excludes"));
        if (excludes != null) {
            config.setExcludes(excludes);
        }
        List<String> includes = splitPatterns(System.getProperty("filepush.includes"));
        if (includes != null) {
            config.setIncludes(includes);
        }
        return config;
    }

    /**
     * 解析逗号分隔的模式串。
     *
     * @param raw 原始属性值
     * @return 去空白后的模式列表；入参为空或无有效项时返回 null
     */
    private static List<String> splitPatterns(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> list = java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return list.isEmpty() ? null : list;
    }

    /**
     * 基于配置的默认值创建新的配置实例。
     *
     * @return 新配置
     */
    public static FilePushConfig defaults() {
        return new FilePushConfig();
    }

    /** @return 文件总数 */
    public int getFileCount() {
        return fileCount;
    }

    /**
     * @param fileCount 文件总数
     * @return this
     */
    public FilePushConfig setFileCount(int fileCount) {
        this.fileCount = fileCount;
        return this;
    }

    /** @return 源目录 */
    public Path getSourceDir() {
        return sourceDir;
    }

    /**
     * @param sourceDir 源目录
     * @return this
     */
    public FilePushConfig setSourceDir(Path sourceDir) {
        this.sourceDir = sourceDir;
        return this;
    }

    /** @return 目标目录 */
    public Path getTargetDir() {
        return targetDir;
    }

    /**
     * @param targetDir 目标目录
     * @return this
     */
    public FilePushConfig setTargetDir(Path targetDir) {
        this.targetDir = targetDir;
        return this;
    }

    /** @return 主机 */
    public String getHost() {
        return host;
    }

    /**
     * @param host 主机
     * @return this
     */
    public FilePushConfig setHost(String host) {
        this.host = host;
        return this;
    }

    /** @return 端口 */
    public int getPort() {
        return port;
    }

    /**
     * @param port 端口
     * @return this
     */
    public FilePushConfig setPort(int port) {
        this.port = port;
        return this;
    }

    /** @return 连接超时（毫秒） */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * @param connectTimeoutMs 连接超时（毫秒）
     * @return this
     */
    public FilePushConfig setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        return this;
    }

    /** @return 读超时（毫秒） */
    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * @param readTimeoutMs 读超时（毫秒）
     * @return this
     */
    public FilePushConfig setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
        return this;
    }

    /** @return 分片大小 */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * @param chunkSize 分片大小
     * @return this
     */
    public FilePushConfig setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
        return this;
    }

    /** @return 客户端并发文件数 */
    public int getClientFileParallelism() {
        return clientFileParallelism;
    }

    /**
     * @param clientFileParallelism 客户端并发文件数
     * @return this
     */
    public FilePushConfig setClientFileParallelism(int clientFileParallelism) {
        this.clientFileParallelism = clientFileParallelism;
        return this;
    }

    /** @return 服务端并发连接数 */
    public int getServerConnectionParallelism() {
        return serverConnectionParallelism;
    }

    /**
     * @param serverConnectionParallelism 服务端并发连接数
     * @return this
     */
    public FilePushConfig setServerConnectionParallelism(int serverConnectionParallelism) {
        this.serverConnectionParallelism = serverConnectionParallelism;
        return this;
    }

    /** @return 服务端写盘并发数 */
    public int getServerWriteParallelism() {
        return serverWriteParallelism;
    }

    /**
     * @param serverWriteParallelism 服务端写盘并发数
     * @return this
     */
    public FilePushConfig setServerWriteParallelism(int serverWriteParallelism) {
        this.serverWriteParallelism = serverWriteParallelism;
        return this;
    }

    /** @return 客户端 IO 缓冲大小 */
    public int getIoBufferSize() {
        return ioBufferSize;
    }

    /**
     * @param ioBufferSize 客户端 IO 缓冲大小
     * @return this
     */
    public FilePushConfig setIoBufferSize(int ioBufferSize) {
        this.ioBufferSize = ioBufferSize;
        return this;
    }

    /** @return 是否清理旧文件 */
    public boolean isCleanup() {
        return cleanup;
    }

    /**
     * @param cleanup 是否清理旧文件
     * @return this
     */
    public FilePushConfig setCleanup(boolean cleanup) {
        this.cleanup = cleanup;
        return this;
    }

    /** @return 是否启用增量同步 */
    public boolean isIncremental() {
        return incremental;
    }

    /**
     * @param incremental 是否启用增量同步
     * @return this
     */
    public FilePushConfig setIncremental(boolean incremental) {
        this.incremental = incremental;
        return this;
    }

    /** @return 排除模式列表 */
    public List<String> getExcludes() {
        return excludes;
    }

    /**
     * @param excludes 排除模式列表
     * @return this
     */
    public FilePushConfig setExcludes(List<String> excludes) {
        this.excludes = excludes;
        return this;
    }

    /** @return 包含模式列表 */
    public List<String> getIncludes() {
        return includes;
    }

    /**
     * @param includes 包含模式列表
     * @return this
     */
    public FilePushConfig setIncludes(List<String> includes) {
        this.includes = includes;
        return this;
    }

    @Override
    public String toString() {
        return "FilePushConfig{host='" + host + "', port=" + port
                + ", sourceDir=" + sourceDir + ", targetDir=" + targetDir
                + ", chunkSize=" + effectiveChunkSize()
                + ", clientParallelism=" + effectiveClientParallelism()
                + ", serverParallelism=" + effectiveServerParallelism()
                + ", cleanup=" + cleanup
                + ", incremental=" + incremental + '}';
    }
}
