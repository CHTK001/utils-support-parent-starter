package com.chua.network.support.tshark.capture;

import com.chua.network.support.tshark.cli.TsharkCliConfig;
import com.chua.network.support.tshark.cli.TsharkSettings;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 实时网卡抓包参数。
 *
 * <p>本类只描述"怎么抓"，不含任何状态；执行由
 * {@link TsharkCapturePolledDirectory} 负责。这样参数可以自由组合、复用与序列化。</p>
 *
 * <h3>超时参数</h3>
 * <p>抓包是长驻任务，超时设计与其他场景不同，分三类：</p>
 * <ul>
 *   <li>{@link #pollTimeoutMillis()} — 读循环单次等待新数据的时长。
 *       决定暂停/停止的响应延迟，不能大到让停止操作看起来"卡住"。</li>
 *   <li>{@link #idleTimeoutMillis()} — 连续无任何报文的上限，超时视为网卡或进程异常，
 *       自动停止并置为 {@link CaptureSessionState#FAILED}。0 表示不检测。</li>
 *   <li>{@link #stopGraceMillis()} — 停止时先温和终止（{@code destroy()}），
 *       超时再强杀（{@code destroyForcibly()}）的等待时长。
 *       tshark 需要时间冲刷抓包缓冲，太短会丢尾包。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CaptureOptions {

    /**
     * 采集网卡编号，{@code -i} 的取值
     */
    private final String interfaceId;

    /**
     * tshark 显示过滤器，如 {@code http || mysql}
     */
    private final String displayFilter;

    /**
     * BPF 抓包过滤器，如 {@code tcp port 3306}
     */
    private final String captureFilter;

    /**
     * 每包捕获字节数上限（snaplen）
     */
    private final int snapLength;

    /**
     * 是否开启混杂模式
     */
    private final boolean promiscuous;

    /**
     * 抓包缓冲大小（MB），0 表示用 tshark 默认
     */
    private final int bufferSizeMegabytes;

    /**
     * 环形缓冲文件路径，设置后启用落盘
     */
    private final Path ringFile;

    /**
     * 环形缓冲文件数量
     */
    private final int ringFileCount;

    /**
     * 单文件写满后的轮转间隔（秒）
     */
    private final int ringFileSeconds;

    /**
     * 停止等待的宽限期（毫秒）
     */
    private final long stopGraceMillis;

    /**
     * 采集时长上限（秒），0 表示不限
     */
    private final int durationSeconds;

    /**
     * 最多采集的数据包数，0 表示不限
     */
    private final int maxPackets;

    /**
     * 单包解析结果的最大字节数，超过则只保留元数据不保留原始 JSON
     */
    private final int maxRawJsonBytes;

    /**
     * 是否启用 TCP 流重组
     */
    private final boolean reassemble;

    /**
     * 单方向重组缓冲上限（字节）
     */
    private final int maxReassembleBufferBytes;

    /**
     * 重组空洞等待上限（毫秒）
     */
    private final long reassembleGapTimeoutMillis;

    /**
     * 并发重组流上限
     */
    private final int maxStreams;

    /**
     * 是否启用会话聚合
     */
    private final boolean aggregateSessions;

    /**
     * 并发会话上限
     */
    private final int maxConversations;

    /**
     * 每会话包明细上限
     */
    private final int maxPacketsPerConversation;

    /**
     * tshark 命令行配置
     */
    private final TsharkCliConfig cliConfig;

    /**
     * 使用构建器构造。
     *
     * @param builder 构建器
     */
    private CaptureOptions(Builder builder) {
        this.interfaceId = builder.interfaceId;
        this.displayFilter = builder.displayFilter;
        this.captureFilter = builder.captureFilter;
        this.snapLength = builder.snapLength;
        this.promiscuous = builder.promiscuous;
        this.bufferSizeMegabytes = builder.bufferSizeMegabytes;
        this.ringFile = builder.ringFile;
        this.ringFileCount = builder.ringFileCount;
        this.ringFileSeconds = builder.ringFileSeconds;
        this.stopGraceMillis = builder.stopGraceMillis;
        this.durationSeconds = builder.durationSeconds;
        this.maxPackets = builder.maxPackets;
        this.maxRawJsonBytes = builder.maxRawJsonBytes;
        this.reassemble = builder.reassemble;
        this.maxReassembleBufferBytes = builder.maxReassembleBufferBytes;
        this.reassembleGapTimeoutMillis = builder.reassembleGapTimeoutMillis;
        this.maxStreams = builder.maxStreams;
        this.aggregateSessions = builder.aggregateSessions;
        this.maxConversations = builder.maxConversations;
        this.maxPacketsPerConversation = builder.maxPacketsPerConversation;
        this.cliConfig = builder.cliConfig;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 列出可采集的网卡。
     *
     * @param cliConfig tshark 命令行配置
     * @return 网卡列表
     */
    @Nonnull
    public static java.util.List<CaptureInterface> listInterfaces(@Nonnull TsharkCliConfig cliConfig) {
        java.util.List<CaptureInterface> interfaces = new java.util.ArrayList<>();
        for (String line : com.chua.network.support.tshark.cli.TsharkCliProvider.getInstance()
                .listInterfaces(cliConfig)) {
            CaptureInterface parsed = CaptureInterface.parse(line);
            if (parsed != null) {
                interfaces.add(parsed);
            }
        }
        return java.util.List.copyOf(interfaces);
    }

    /**
     * 采集网卡编号。
     *
     * @return 网卡编号
     */
    @Nonnull
    public String interfaceId() {
        return interfaceId;
    }

    /**
     * 显示过滤器。
     *
     * @return 过滤器表达式，未设置返回 {@code null}
     */
    @Nullable
    public String displayFilter() {
        return displayFilter;
    }

    /**
     * BPF 抓包过滤器。
     *
     * @return 过滤器表达式，未设置返回 {@code null}
     */
    @Nullable
    public String captureFilter() {
        return captureFilter;
    }

    /**
     * 每包捕获字节数上限。
     *
     * @return snaplen
     */
    public int snapLength() {
        return snapLength;
    }

    /**
     * 是否开启混杂模式。
     *
     * @return 开启返回 true
     */
    public boolean promiscuous() {
        return promiscuous;
    }

    /**
     * 抓包缓冲大小。
     *
     * @return 兆字节数，0 表示用默认值
     */
    public int bufferSizeMegabytes() {
        return bufferSizeMegabytes;
    }

    /**
     * 环形缓冲文件路径。
     *
     * @return 路径，未启用落盘返回 {@code null}
     */
    @Nullable
    public Path ringFile() {
        return ringFile;
    }

    /**
     * 环形缓冲文件数量。
     *
     * @return 文件数
     */
    public int ringFileCount() {
        return ringFileCount;
    }

    /**
     * 环形缓冲轮转间隔。
     *
     * @return 秒数
     */
    public int ringFileSeconds() {
        return ringFileSeconds;
    }

    /**
     * 停止等待的宽限期。
     *
     * @return 毫秒数
     */
    public long stopGraceMillis() {
        return stopGraceMillis;
    }

    /**
     * 采集时长上限。
     *
     * @return 秒数，0 表示不限
     */
    public int durationSeconds() {
        return durationSeconds;
    }

    /**
     * 最多采集的数据包数。
     *
     * @return 包数，0 表示不限
     */
    public int maxPackets() {
        return maxPackets;
    }

    /**
     * 单包解析结果的最大字节数。
     *
     * @return 字节数
     */
    public int maxRawJsonBytes() {
        return maxRawJsonBytes;
    }

    /**
     * 是否启用 TCP 流重组。
     *
     * @return 启用返回 true
     */
    public boolean reassemble() {
        return reassemble;
    }

    /**
     * 单方向重组缓冲上限。
     *
     * @return 字节数
     */
    public int maxReassembleBufferBytes() {
        return maxReassembleBufferBytes;
    }

    /**
     * 重组空洞等待上限。
     *
     * @return 毫秒数
     */
    public long reassembleGapTimeoutMillis() {
        return reassembleGapTimeoutMillis;
    }

    /**
     * 并发重组流上限。
     *
     * @return 流数
     */
    public int maxStreams() {
        return maxStreams;
    }

    /**
     * 是否启用会话聚合。
     *
     * @return 启用返回 true
     */
    public boolean aggregateSessions() {
        return aggregateSessions;
    }

    /**
     * 并发会话上限。
     *
     * @return 会话数
     */
    public int maxConversations() {
        return maxConversations;
    }

    /**
     * 每会话包明细上限。
     *
     * @return 包数
     */
    public int maxPacketsPerConversation() {
        return maxPacketsPerConversation;
    }

    /**
     * tshark 命令行配置。
     *
     * @return 命令行配置
     */
    @Nonnull
    public TsharkCliConfig cliConfig() {
        return cliConfig;
    }

    @Override
    public String toString() {
        return "CaptureOptions{interface=" + interfaceId
                + ", displayFilter=" + displayFilter
                + ", captureFilter=" + captureFilter
                + ", snapLen=" + snapLength
                + ", promiscuous=" + promiscuous
                + ", buffer=" + bufferSizeMegabytes + "MB"
                + ", ring=" + ringFile
                + ", reassemble=" + reassemble
                + ", aggregateSessions=" + aggregateSessions
                + '}';
    }

    /**
     * {@link CaptureOptions} 构建器。
     */
    public static final class Builder {

        /**
         * 采集网卡编号
         */
        private String interfaceId = "1";
        /**
         * 显示过滤器
         */
        private String displayFilter;
        /**
         * BPF 抓包过滤器
         */
        private String captureFilter;
        /**
         * snaplen
         */
        private int snapLength = 0;
        /**
         * 混杂模式
         */
        private boolean promiscuous = true;
        /**
         * 抓包缓冲大小
         */
        private int bufferSizeMegabytes = 32;
        /**
         * 环形缓冲文件
         */
        private Path ringFile;
        /**
         * 环形缓冲文件数
         */
        private int ringFileCount = 0;
        /**
         * 环形缓冲轮转间隔
         */
        private int ringFileSeconds = 0;
        /**
         * 停止宽限期
         */
        private long stopGraceMillis = 5_000L;
        /**
         * 采集时长上限
         */
        private int durationSeconds = 0;
        /**
         * 最多包数
         */
        private int maxPackets = 0;
        /**
         * 单包原始 JSON 上限
         */
        private int maxRawJsonBytes = 256 * 1024;
        /**
         * 是否启用重组
         */
        private boolean reassemble = true;
        /**
         * 单方向重组缓冲上限
         */
        private int maxReassembleBufferBytes = com.chua.network.support.tshark.stream.TcpStreamAssembler
                .DEFAULT_MAX_BUFFERED_BYTES;
        /**
         * 重组空洞等待上限
         */
        private long reassembleGapTimeoutMillis =
                com.chua.network.support.tshark.stream.TcpStreamAssembler.DEFAULT_GAP_TIMEOUT_MILLIS;
        /**
         * 并发重组流上限
         */
        private int maxStreams =
                com.chua.network.support.tshark.stream.TcpStreamAssembler.DEFAULT_MAX_STREAMS;
        /**
         * 是否启用会话聚合
         */
        private boolean aggregateSessions = true;
        /**
         * 并发会话上限
         */
        private int maxConversations =
                com.chua.network.support.tshark.session.SessionAggregator.DEFAULT_MAX_CONVERSATIONS;
        /**
         * 每会话包明细上限
         */
        private int maxPacketsPerConversation =
                com.chua.network.support.tshark.session.PacketConversation.DEFAULT_MAX_PACKETS;
        /**
         * tshark 命令行配置
         */
        private TsharkCliConfig cliConfig = TsharkCliConfig.defaults();

        /**
         * 设置采集网卡编号。
         *
         * @param interfaceId 网卡编号，取自 {@code tshark -D}
         * @return this
         */
        @Nonnull
        public Builder interfaceId(@Nonnull String interfaceId) {
            this.interfaceId = interfaceId;
            return this;
        }

        /**
         * 设置 tshark 显示过滤器。
         *
         * <p>显示过滤器在协议解析<b>之后</b>生效，能减少后续处理量，
         * 代价是 tshark 仍需解析全部报文。抓包层过滤请用
         * {@link #captureFilter(String)}。</p>
         *
         * @param displayFilter 过滤器表达式，如 {@code http || mysql}
         * @return this
         */
        @Nonnull
        public Builder displayFilter(@Nullable String displayFilter) {
            this.displayFilter = displayFilter;
            return this;
        }

        /**
         * 设置 BPF 抓包过滤器。
         *
         * @param captureFilter 过滤器表达式，如 {@code tcp port 3306}
         * @return this
         */
        @Nonnull
        public Builder captureFilter(@Nullable String captureFilter) {
            this.captureFilter = captureFilter;
            return this;
        }

        /**
         * 设置每包捕获字节数上限。
         *
         * @param snapLength 字节数，0 表示不限制
         * @return this
         */
        @Nonnull
        public Builder snapLength(int snapLength) {
            this.snapLength = snapLength;
            return this;
        }

        /**
         * 设置是否开启混杂模式。
         *
         * @param promiscuous true 开启
         * @return this
         */
        @Nonnull
        public Builder promiscuous(boolean promiscuous) {
            this.promiscuous = promiscuous;
            return this;
        }

        /**
         * 设置抓包缓冲大小。
         *
         * @param megabytes 兆字节数，0 表示用 tshark 默认
         * @return this
         */
        @Nonnull
        public Builder bufferSizeMegabytes(int megabytes) {
            this.bufferSizeMegabytes = megabytes;
            return this;
        }

        /**
         * 启用环形缓冲落盘。
         *
         * @param ringFile    缓冲文件路径前缀
         * @param fileCount   文件数量
         * @param rotateSeconds 单文件写满后的轮转间隔（秒）
         * @return this
         */
        @Nonnull
        public Builder ringFile(@Nullable Path ringFile, int fileCount, int rotateSeconds) {
            this.ringFile = ringFile;
            this.ringFileCount = fileCount;
            this.ringFileSeconds = rotateSeconds;
            return this;
        }

        /**
         * 设置停止等待的宽限期。
         *
         * @param millis 毫秒数
         * @return this
         */
        @Nonnull
        public Builder stopGraceMillis(long millis) {
            this.stopGraceMillis = millis;
            return this;
        }

        /**
         * 设置采集时长上限。
         *
         * @param seconds 秒数，0 表示不限
         * @return this
         */
        @Nonnull
        public Builder durationSeconds(int seconds) {
            this.durationSeconds = seconds;
            return this;
        }

        /**
         * 设置最多采集的数据包数。
         *
         * @param maxPackets 包数，0 表示不限
         * @return this
         */
        @Nonnull
        public Builder maxPackets(int maxPackets) {
            this.maxPackets = maxPackets;
            return this;
        }

        /**
         * 设置单包原始 JSON 的保留上限。
         *
         * @param bytes 字节数，超过则只保留元数据
         * @return this
         */
        @Nonnull
        public Builder maxRawJsonBytes(int bytes) {
            this.maxRawJsonBytes = bytes;
            return this;
        }

        /**
         * 设置是否启用 TCP 流重组。
         *
         * @param reassemble true 启用
         * @return this
         */
        @Nonnull
        public Builder reassemble(boolean reassemble) {
            this.reassemble = reassemble;
            return this;
        }

        /**
         * 设置重组相关上界。
         *
         * @param maxBufferBytes 单方向缓冲上限（字节）
         * @param gapTimeoutMillis 空洞等待上限（毫秒）
         * @param maxStreams 并发流上限
         * @return this
         */
        @Nonnull
        public Builder reassemblyLimits(int maxBufferBytes, long gapTimeoutMillis, int maxStreams) {
            this.maxReassembleBufferBytes = maxBufferBytes;
            this.reassembleGapTimeoutMillis = gapTimeoutMillis;
            this.maxStreams = maxStreams;
            return this;
        }

        /**
         * 设置是否启用会话聚合。
         *
         * @param aggregateSessions true 启用
         * @return this
         */
        @Nonnull
        public Builder aggregateSessions(boolean aggregateSessions) {
            this.aggregateSessions = aggregateSessions;
            return this;
        }

        /**
         * 设置会话聚合相关上界。
         *
         * @param maxConversations          并发会话上限
         * @param maxPacketsPerConversation 每会话包明细上限
         * @return this
         */
        @Nonnull
        public Builder sessionLimits(int maxConversations, int maxPacketsPerConversation) {
            this.maxConversations = maxConversations;
            this.maxPacketsPerConversation = maxPacketsPerConversation;
            return this;
        }

        /**
         * 设置 tshark 命令行配置。
         *
         * @param cliConfig 命令行配置
         * @return this
         */
        @Nonnull
        public Builder cliConfig(@Nonnull TsharkCliConfig cliConfig) {
            this.cliConfig = cliConfig;
            return this;
        }

        /**
         * 从轮询目录环境配置派生抓包参数。
         *
         * <p>读取的键：{@code capture.interface}、{@code capture.displayFilter}、
         * {@code capture.filter}、{@code capture.snapLen}、{@code capture.promiscuous}、
         * {@code capture.bufferMb}、{@code capture.ringFile}、{@code capture.ringFileCount}、
         * {@code capture.ringFileSeconds}、{@code capture.durationSeconds}、
         * {@code capture.maxPackets}、{@code capture.pollTimeoutMillis}、
         * {@code capture.idleTimeoutMillis}、{@code capture.stopGraceMillis}。</p>
         *
         * @param environment 环境配置，可为空
         * @return 构建器
         */
        @Nonnull
        public Builder from(@Nullable DirectoryPollerEnvironment environment) {
            if (environment == null) {
                return this;
            }
            this.interfaceId = TsharkSettings.string("capture.interface", environment, this.interfaceId);
            this.displayFilter = TsharkSettings.string("capture.displayFilter", environment, this.displayFilter);
            this.captureFilter = TsharkSettings.string("capture.filter", environment, this.captureFilter);
            this.snapLength = (int) TsharkSettings.seconds("capture.snapLen", environment, this.snapLength);
            this.promiscuous = TsharkSettings.bool("capture.promiscuous", environment, this.promiscuous);
            this.bufferSizeMegabytes = (int) TsharkSettings.seconds("capture.bufferMb", environment,
                    this.bufferSizeMegabytes);
            this.ringFile = resolveRingFile(environment, this.ringFile);
            this.ringFileCount = (int) TsharkSettings.seconds("capture.ringFileCount", environment,
                    this.ringFileCount);
            this.ringFileSeconds = (int) TsharkSettings.seconds("capture.ringFileSeconds", environment,
                    this.ringFileSeconds);
            this.durationSeconds = (int) TsharkSettings.seconds("capture.durationSeconds", environment,
                    this.durationSeconds);
            this.maxPackets = (int) TsharkSettings.seconds("capture.maxPackets", environment, this.maxPackets);
            this.stopGraceMillis = TsharkSettings.seconds("capture.stopGraceMillis", environment,
                    this.stopGraceMillis);
            this.cliConfig = TsharkCliConfig.builder()
                    .from(TsharkCliConfig.from(environment))
                    // 显式配置整体覆盖环境属性，避免调用方设的字段被逐项冲掉
                    .from(this.cliConfig)
                    .build();
            return this;
        }

        /**
         * 解析环形缓冲文件路径。
         *
         * @param environment 环境配置
         * @param current     当前值
         * @return 路径，未配置返回当前值
         */
        @Nullable
        private Path resolveRingFile(@Nonnull DirectoryPollerEnvironment environment, @Nullable Path current) {
            String configured = TsharkSettings.string("capture.ringFile", environment, null);
            return configured == null ? current : Paths.get(configured);
        }

        /**
         * 构建抓包参数。
         *
         * @return 抓包参数
         * @throws IllegalArgumentException 网卡编号为空时抛出
         */
        @Nonnull
        public CaptureOptions build() {
            if (interfaceId == null || interfaceId.isBlank()) {
                throw new IllegalArgumentException("采集网卡编号不能为空，请先用 tshark -D 枚举");
            }
            return new CaptureOptions(this);
        }
    }
}
