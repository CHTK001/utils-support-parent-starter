package com.chua.network.support.tshark.capture;

import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledDirectorySupport;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.network.support.tshark.PacketParserService;
import com.chua.network.support.tshark.PacketRecord;
import com.chua.network.support.tshark.cli.TsharkCliProvider;
import com.chua.network.support.tshark.cli.TsharkProvisioningReport;
import com.chua.network.support.tshark.cli.TsharkSettings;
import com.chua.network.support.tshark.session.PacketConversation;
import com.chua.network.support.tshark.session.ProtocolExchange;
import com.chua.network.support.tshark.session.SessionAggregator;
import com.chua.network.support.tshark.stream.ReassembledMessage;
import com.chua.network.support.tshark.stream.TcpStreamAssembler;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 实时网卡抓包会话，实现 {@link PolledDirectory}。
 *
 * <h3>为什么是一个"轮询目录"</h3>
 * <p>抓包的数据源不是文件系统，而是外部进程的输出流，无法用
 * {@code WatchService} 事件驱动。但它同样符合"数据源变更 → 解析 → 分发事件"的形状，
 * 因此实现 {@link PolledDirectory} 以复用既有的生命周期、监听器与统计约定：
 * {@link #isDelegatedOperatingSystem()} 返回 {@code true}（由进程流驱动，
 * 不需要定时快照对比），{@link #upgrade()} 为空实现。</p>
 *
 * <h3>命令与流式解析</h3>
 * <p>实际执行的是 {@code tshark -i <网卡> -T json -x -l}：</p>
 * <ul>
 *   <li>{@code -T json} 而非 {@code -T ek} — 保持与离线解析完全相同的层结构，
 *       30 个协议还原器一行都不用改；</li>
 *   <li>{@code -l} — 逐包刷新标准输出。这是实时抓包的<b>必需项</b>：
 *       缺了它 tshark 会按块缓冲输出，读侧将长时间收不到数据；</li>
 *   <li>{@code -x} — 附带十六进制转储，还原器据此从载荷字节解析协议头。</li>
 * </ul>
 * <p>{@code -T json} 的输出是一个持续增长的 JSON 数组，
 * {@link PacketParserService.StreamReader} 用流式游标逐包取走，
 * <b>内存占用只与单包大小相关，与报文总数无关</b>，
 * 因此无需先把整个输出读进内存再解析。</p>
 *
 * <h3>三级处理链</h3>
 * <p>每个数据包依次经过：单包协议还原 → TCP 流重组 → 会话聚合。
 * 后两级可分别关闭；关闭时相应回调不再触发。</p>
 *
 * <h3>超时</h3>
 * <p>读循环的每一次等待都有上界（{@code capture.pollTimeoutMillis}），
 * 另有连续无报文上限（{@code capture.idleTimeoutMillis}，超时置
 * {@link CaptureSessionState#FAILED}）与停止宽限期（{@code capture.stopGraceMillis}，
 * 先温和终止后强杀），因此不会出现"停不下来的抓包线程"。</p>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * CaptureOptions options = CaptureOptions.builder()
 *         .interfaceId("3")
 *         .displayFilter("http or mysql")
 *         .build();
 *
 * TsharkCapturePolledDirectory session = new TsharkCapturePolledDirectory(options);
 * session.addListener(new CaptureListener() {
 *     public void onPacket(PacketRecord packet) {
 *         log.info("{} -> {}", packet.info(), packet.restoredText());
 *     }
 * });
 * session.start(env);
 * // ...
 * session.stop();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TsharkCapturePolledDirectory implements PolledDirectory {

    /**
     * 抓包参数
     */
    private final CaptureOptions options;

    /**
     * 监听器与运行状态的公共支撑件
     */
    private final PolledDirectorySupport support = new PolledDirectorySupport(getClass().getSimpleName());

    /**
     * 数据包回调监听器
     */
    private final List<CaptureListener> captureListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * TCP 流重组器
     */
    private final TcpStreamAssembler assembler;

    /**
     * 会话聚合器
     */
    private final SessionAggregator aggregator;

    /**
     * 当前状态
     */
    private final AtomicReference<CaptureSessionState> state =
            new AtomicReference<>(CaptureSessionState.IDLE);

    /**
     * 停止信号
     */
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    /**
     * 已投递的包数，用于 {@code capture.maxPackets} 截断
     */
    private final AtomicInteger delivered = new AtomicInteger();

    /**
     * tshark 进程
     */
    private volatile Process process;

    /**
     * 读循环线程
     */
    private volatile Thread readerThread;

    /**
     * 统计
     */
    private volatile CaptureStatistics statistics;

    /**
     * tshark 标准错误尾部保留的字符上限
     */
    private static final int STDERR_TAIL_CHARS = 2048;

    /**
     * tshark 标准错误尾部
     *
     * <p>tshark 打开网卡失败时只把原因写进标准错误并立刻退出（退出码非 0），
     * 而不产生任何标准输出。若把标准错误丢掉，上层只会看到"采集中但一个包都没有"，
     * 无从区分是网卡被占用、驱动缺失还是过滤条件写错。这里保留尾部若干字符，
     * 供会话以异常形式把原因抛出来。</p>
     */
    private final StringBuilder stderrTail = new StringBuilder();

    /**
     * 最近一次收到报文的时间（毫秒）
     */
    private volatile long lastPacketMillis;

    /**
     * 会话环境配置
     */
    private volatile DirectoryPollerEnvironment environment;

    /**
     * 构造抓包会话。
     *
     * @param options 抓包参数
     */
    public TsharkCapturePolledDirectory(@Nonnull CaptureOptions options) {
        this.options = options;
        this.assembler = new TcpStreamAssembler(options.maxReassembleBufferBytes(),
                options.maxStreams(), options.reassembleGapTimeoutMillis());
        this.aggregator = new SessionAggregator(options.maxConversations(),
                options.maxPacketsPerConversation(), com.chua.network.support.tshark.session
                .PacketConversation.DEFAULT_MAX_EXCHANGES);
    }

    /**
     * 列出可采集的网卡。
     *
     * @return 网卡列表
     */
    @Nonnull
    public List<CaptureInterface> interfaces() {
        return CaptureOptions.listInterfaces(options.cliConfig());
    }

    /**
     * 注册抓包回调监听器。
     *
     * @param listener 监听器
     */
    public void addCaptureListener(@Nonnull CaptureListener listener) {
        if (listener != null) {
            captureListeners.add(listener);
        }
    }

    /**
     * 移除抓包回调监听器。
     *
     * @param listener 监听器
     * @return 移除前该监听器是否已注册
     */
    public boolean removeCaptureListener(@Nonnull CaptureListener listener) {
        return captureListeners.remove(listener);
    }

    @Override
    /**
     * 是否委托操作系统
     */
    public boolean isDelegatedOperatingSystem() {
        return true;
    }

    @Override
    /**
     * 添加监听器
     */
    public void addListener(PolledListener listener) {
        support.addListener(listener);
    }

    @Override
    /**
     * Upgrade
     */
    public void upgrade() {
        // 抓包由 tshark 进程的输出流驱动，不需要定时快照对比
    }

    @Override
    /**
     * 开始
     */
    public void start(@Nonnull DirectoryPollerEnvironment environment,
                      @Nullable DirectoryPollerExecutor executor) {
        this.environment = environment;
        if (!support.markRunning()) {
            log.warn("抓包会话已在运行，忽略重复启动: {}", options.interfaceId());
            return;
        }
        if (!state.compareAndSet(CaptureSessionState.IDLE, CaptureSessionState.RUNNING)) {
            support.markStopped();
            throw new IllegalStateException("抓包会话当前状态为 " + state.get() + "，无法启动");
        }

        Path executable;
        try {
            executable = resolveTshark();
        } catch (RuntimeException e) {
            fail(e);
            throw e;
        }

        List<String> command = buildCommand(executable);
        log.info("启动网卡抓包: {}", String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        // stderr 单独消费：tshark 的告警行若并入 stdout 会污染 JSON 流
        builder.redirectErrorStream(false);
        try {
            process = builder.start();
        } catch (IOException e) {
            fail(new IllegalStateException("启动 tshark 失败: " + e.getMessage(), e));
            throw new IllegalStateException("启动 tshark 失败: " + e.getMessage(), e);
        }
        statistics = new CaptureStatistics(System.currentTimeMillis());
        lastPacketMillis = System.currentTimeMillis();
        drainStderr();

        readerThread = ThreadUtils.newThread(this::readLoop, "TsharkCapture-" + options.interfaceId());
        readerThread.setDaemon(true);
        readerThread.start();

        for (CaptureListener listener : captureListeners) {
            safe(() -> listener.onStart(this), "onStart");
        }
    }

    @Override
    /**
     * 停止
     */
    public void stop() {
        close();
    }

    @Override
    /**
     * 关闭
     */
    public void close() {
        CaptureSessionState previous = state.getAndSet(CaptureSessionState.STOPPED);
        if (previous == CaptureSessionState.STOPPED || previous == CaptureSessionState.IDLE) {
            support.markStopped();
            return;
        }
        stopping.set(true);

        Process current = process;
        if (current != null && current.isAlive()) {
            // 先温和终止，给 tshark 冲刷抓包缓冲的时间；超时再强杀。
            // 直接 destroyForcibly 会丢掉缓冲区里已抓到但未写出的包。
            current.destroy();
            try {
                if (!current.waitFor(options.stopGraceMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    log.warn("tshark 未在 {}ms 内退出，强制终止", options.stopGraceMillis());
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        Thread reader = readerThread;
        if (reader != null) {
            reader.interrupt();
        }
        flushPending();
        support.markStopped();
        log.info("网卡抓包已停止: {} 统计={}", options.interfaceId(), statistics);
        for (CaptureListener listener : captureListeners) {
            safe(() -> listener.onStop(CaptureSessionState.STOPPED), "onStop");
        }
    }

    @Override
    /**
     * 是否处于运行中
     */
    public boolean isRunning() {
        return state.get() == CaptureSessionState.RUNNING || state.get() == CaptureSessionState.PAUSED;
    }

    /**
     * 暂停投递数据包。
     *
     * <p>tshark 进程保持运行，缓冲继续累积，恢复后不会丢包。
     * 暂停期间仍会执行 {@link #evictIdle(long)} 类的回收，避免缓冲无界增长。</p>
     *
     * @return 暂停成功返回 true
     */
    public boolean pause() {
        return state.compareAndSet(CaptureSessionState.RUNNING, CaptureSessionState.PAUSED);
    }

    /**
     * 恢复投递数据包。
     *
     * @return 恢复成功返回 true
     */
    public boolean resume() {
        return state.compareAndSet(CaptureSessionState.PAUSED, CaptureSessionState.RUNNING);
    }

    /**
     * 当前状态。
     *
     * @return 状态
     */
    @Nonnull
    public CaptureSessionState state() {
        return state.get();
    }

    /**
     * 当前统计。
     *
     * @return 统计，尚未启动时返回 {@code null}
     */
    @Nullable
    public CaptureStatistics statistics() {
        return statistics;
    }

    /**
     * 当前全部会话。
     *
     * @return 会话列表
     */
    @Nonnull
    public List<PacketConversation> conversations() {
        return aggregator.conversations();
    }

    /**
     * 流重组器，用于查看丢弃与截断情况。
     *
     * @return 重组器
     */
    @Nonnull
    public TcpStreamAssembler assembler() {
        return assembler;
    }

    // ==================== 内部实现 ====================

    /**
     * 解析 tshark 可执行文件，必要时按三级策略自动装配。
     *
     * @return 可执行文件路径
     */
    @Nonnull
    private Path resolveTshark() {
        TsharkProvisioningReport report = TsharkCliProvider.getInstance().provision(options.cliConfig());
        if (!report.available()) {
            throw new IllegalStateException(report.describe());
        }
        return report.executable();
    }

    /**
     * 组装 tshark 命令行。
     *
     * @param executable tshark 可执行文件
     * @return 命令行
     */
    @Nonnull
    private List<String> buildCommand(@Nonnull Path executable) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("-i");
        command.add(options.interfaceId());
        // -T json 保持与离线解析一致的层结构；-x 附带十六进制转储供还原器解析协议头；
        // -l 逐包刷新标准输出，实时抓包必需，否则输出被块缓冲导致读侧收不到数据
        command.add("-T");
        command.add("json");
        command.add("-x");
        command.add("-l");
        if (options.promiscuous()) {
            command.add("-p");
        }
        if (options.snapLength() > 0) {
            command.add("-s");
            command.add(String.valueOf(options.snapLength()));
        }
        if (options.bufferSizeMegabytes() > 0) {
            command.add("-B");
            command.add(String.valueOf(options.bufferSizeMegabytes()));
        }
        if (options.durationSeconds() > 0) {
            command.add("-a");
            command.add("duration:" + options.durationSeconds());
        }
        Path ringFile = options.ringFile();
        if (ringFile != null) {
            command.add("-w");
            command.add(ringFile.toString());
            if (options.ringFileCount() > 0) {
                command.add("-W");
                command.add(String.valueOf(options.ringFileCount()));
            }
            if (options.ringFileSeconds() > 0) {
                command.add("-b");
                command.add("duration:" + options.ringFileSeconds());
            }
        }
        if (options.displayFilter() != null && !options.displayFilter().isBlank()) {
            command.add("-Y");
            command.add(options.displayFilter());
        }
        if (options.captureFilter() != null && !options.captureFilter().isBlank()) {
            // BPF 过滤表达式不带引号，作为独立参数交给 tshark 解析
            command.add("-f");
            command.add(options.captureFilter());
        }
        return List.copyOf(command);
    }

    /**
     * 读循环：流式解析 tshark 输出并分发。
     */
    private void readLoop() {
        Process current = process;
        long pollTimeout = TsharkSettings.seconds(TsharkSettings.KEY_CAPTURE_POLL_TIMEOUT_MILLIS,
                environment, TsharkSettings.DEFAULT_POLL_TIMEOUT_MILLIS);
        long idleTimeout = TsharkSettings.seconds(TsharkSettings.KEY_CAPTURE_IDLE_TIMEOUT_MILLIS,
                environment, TsharkSettings.DEFAULT_IDLE_TIMEOUT_MILLIS);
        try (InputStream in = current.getInputStream();
             PacketParserService.StreamReader reader = new PacketParserService.StreamReader(in)) {
            while (!stopping.get()) {
                if (current.waitFor(1, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    // 进程自行结束（时长上限、达到包数上限等）
                    int exit = current.exitValue();
                    if (delivered.get() == 0 && exit != 0) {
                        // 一个包都没产出就异常退出：几乎都是"打不开网卡"，
                        // 典型原因是抓包驱动缺失或该网卡已被另一个抓包会话占用。
                        // 不把它变成异常的话，上层只会看到"采集中但零包"
                        String detail = stderrTail();
                        fail(new IllegalStateException("tshark 未能开始采集，退出码 " + exit
                                + (detail == null ? "（无标准错误输出）" : "：" + detail), null));
                        return;
                    }
                    log.info("tshark 自行结束，退出码 {}", exit);
                    break;
                }
                List<PacketRecord> batch = reader.poll(pollTimeout);
                if (!batch.isEmpty()) {
                    lastPacketMillis = System.currentTimeMillis();
                    if (state.get() == CaptureSessionState.RUNNING) {
                        consume(batch);
                    }
                }
                statistics.setDroppedByParser(reader.droppedPackets());
                if (idleTimeout > 0 && System.currentTimeMillis() - lastPacketMillis > idleTimeout) {
                    fail(new IllegalStateException("连续 " + idleTimeout + "ms 未捕获到任何报文，判定抓包异常"));
                    return;
                }
                if (options.durationSeconds() > 0
                        && statistics.elapsedMillis() > options.durationSeconds() * 1000L) {
                    log.info("已达到采集时长上限 {}s，自动停止", options.durationSeconds());
                    break;
                }
                if (options.maxPackets() > 0 && delivered.get() >= options.maxPackets()) {
                    log.info("已达到采集包数上限 {}，自动停止", options.maxPackets());
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!stopping.get()) {
                log.debug("抓包读循环被中断");
            }
        } catch (IOException e) {
            if (!stopping.get()) {
                fail(new IllegalStateException("读取 tshark 输出失败: " + e.getMessage(), e));
            }
        } catch (RuntimeException e) {
            if (!stopping.get()) {
                fail(e);
            }
        } finally {
            // 读循环退出说明抓包已结束。close() / fail() 已先改过状态时
            // previous 不再是 RUNNING/PAUSED，这里就不会重复通知与回收。
            CaptureSessionState previous = state.getAndSet(CaptureSessionState.STOPPED);
            if (previous == CaptureSessionState.RUNNING || previous == CaptureSessionState.PAUSED) {
                flushPending();
                support.markStopped();
                log.info("网卡抓包已结束: {} 统计={}", options.interfaceId(), statistics);
                for (CaptureListener listener : captureListeners) {
                    safe(() -> listener.onStop(CaptureSessionState.STOPPED), "onStop");
                }
            }
        }
    }

    /**
     * 处理一批数据包。
     *
     * @param batch 数据包批次
     */
    private void consume(@Nonnull List<PacketRecord> batch) {
        List<PacketRecord> capped = new ArrayList<>(batch.size());
        for (PacketRecord record : batch) {
            delivered.incrementAndGet();
            statistics.addPacket(record.length() == null ? 0 : record.length());
            if (options.reassemble()) {
                for (ReassembledMessage message : assembler.accept(record)) {
                    statistics.addReassembledMessage();
                    deliverReassembled(message);
                }
            }
            if (options.aggregateSessions()) {
                for (ProtocolExchange exchange : aggregator.accept(record)) {
                    deliverExchange(exchange);
                }
            }
            capped.add(trim(record));
        }
        if (options.reassemble() || options.aggregateSessions()) {
            for (ReassembledMessage message : assembler.evictIdle(System.currentTimeMillis())) {
                statistics.addReassembledMessage();
                deliverReassembled(message);
            }
            statistics.setConversations(aggregator.conversationCount());
            statistics.setDroppedByAssembler(assembler.droppedBytes());
            statistics.setDroppedByAggregator(aggregator.evictedConversations());
        }
        for (CaptureListener listener : captureListeners) {
            safe(() -> listener.onPackets(capped), "onPackets");
        }
    }

    /**
     * 投递重组消息，并对重组后的<b>完整字节</b>再做一次协议还原。
     *
     * <p>这一步是重组存在的意义：单包还原看到的是被 TCP 分段切碎的片段，
     * 拿不到完整的 HTTP 正文、SQL 语句或 Redis 事务；
     * 重组后按字节重新嗅探，才能得到业务可读的内容。</p>
     *
     * @param message 重组消息
     */
    private void deliverReassembled(@Nonnull ReassembledMessage message) {
        String restored = message.truncated()
                ? null
                : PacketParserService.restoreBytesAsText(message.payload());
        if (message.truncated()) {
            log.debug("重组消息被截断，跳过协议还原: {}", message);
        } else if (restored != null) {
            log.debug("重组后还原成功: {} -> {}", message, firstLine(restored));
        }
        for (CaptureListener listener : captureListeners) {
            safe(() -> listener.onReassembled(message, restored), "onReassembled");
        }
    }

    /**
     * 取文本首行，避免多行还原结果把日志撑爆。
     *
     * @param text 文本
     * @return 首行
     */
    @Nonnull
    private static String firstLine(@Nonnull String text) {
        int index = text.indexOf('\n');
        String line = index < 0 ? text : text.substring(0, index);
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    /**
     * 投递请求响应对。
     *
     * @param exchange 请求响应对
     */
    private void deliverExchange(@Nonnull ProtocolExchange exchange) {
        for (CaptureListener listener : captureListeners) {
            safe(() -> listener.onExchange(exchange), "onExchange");
        }
    }

    /**
     * 按配置裁剪单包的原始 JSON，抑制高流量下的内存占用。
     *
     * @param record 数据包
     * @return 裁剪后的数据包
     */
    @Nonnull
    private PacketRecord trim(@Nonnull PacketRecord record) {
        String raw = record.rawData();
        if (raw == null || raw.length() <= options.maxRawJsonBytes()) {
            return record;
        }
        return new PacketRecord(record.sourceIp(), record.destinationIp(), record.sourcePort(),
                record.destinationPort(), record.protocol(), record.length(), record.info(),
                null, record.lifecycleJson(), record.restoredText(), record.meta(),
                record.payload(), record.restoredDetails());
    }

    /**
     * 停止时把缓冲区中未排出的字节交出去。
     */
    private void flushPending() {
        for (ReassembledMessage message : assembler.flushAll(System.currentTimeMillis())) {
            deliverReassembled(message);
        }
        if (options.aggregateSessions()) {
            for (PacketConversation conversation : aggregator.conversations()) {
                for (CaptureListener listener : captureListeners) {
                    safe(() -> listener.onSessions(List.of(conversation)), "onSessions");
                }
            }
        }
    }

    /**
     * 消费 tshark 的标准错误，避免管道写满导致进程阻塞。
     *
     * <p>不消费的话，tshark 写满 stderr 管道后会永久阻塞，
     * 表现为"抓包进程在跑但一个包都不出"。</p>
     */
    private void drainStderr() {
        ThreadUtils.newThread(() -> {
            try (InputStream error = process.getErrorStream();
                 java.io.BufferedReader reader = new java.io.BufferedReader(
                         new java.io.InputStreamReader(error, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[tshark] {}", line);
                    CaptureStatistics current = statistics;
                    if (current != null) {
                        current.addDroppedByTshark(parseDroppedPackets(line));
                    }
                    appendStderrTail(line);
                }
            } catch (IOException e) {
                log.debug("读取 tshark 标准错误失败: {}", e.getMessage());
            }
        }, "TsharkCapture-stderr").start();
    }

    /**
     * 追加一行标准错误到尾部缓冲，超出上限时丢弃最旧的内容。
     *
     * @param line 输出行
     */
    private void appendStderrTail(@Nonnull String line) {
        synchronized (stderrTail) {
            stderrTail.append(line).append('\n');
            if (stderrTail.length() > STDERR_TAIL_CHARS) {
                stderrTail.delete(0, stderrTail.length() - STDERR_TAIL_CHARS);
            }
        }
    }

    /**
     * 读取标准错误尾部。
     *
     * @return 尾部文本，无内容返回 {@code null}
     */
    @Nullable
    private String stderrTail() {
        synchronized (stderrTail) {
            String text = stderrTail.toString().trim();
            return text.isEmpty() ? null : text;
        }
    }

    /**
     * 标记会话异常终止。
     *
     * @param error 异常
     */
    private void fail(@Nonnull Throwable error) {
        if (state.getAndSet(CaptureSessionState.FAILED) == CaptureSessionState.FAILED) {
            return;
        }
        log.error("网卡抓包异常终止: {}", options.interfaceId(), error);
        stopping.set(true);
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroyForcibly();
        }
        support.fireError(options.interfaceId(), null, error);
        for (CaptureListener listener : captureListeners) {
            safe(() -> listener.onError(error), "onError");
        }
    }

    /**
     * 执行监听器回调并隔离异常。
     *
     * @param action 回调
     * @param name   回调名，仅用于日志
     */
    private void safe(@Nonnull Runnable action, @Nonnull String name) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("抓包监听器 {} 回调异常", name, e);
            if (statistics != null) {
                statistics.addParseFailure();
            }
        }
    }

    /**
     * 读取抓包目录中已落盘的环形缓冲文件路径。
     *
     * @return 环形缓冲文件路径，未启用落盘返回 {@code null}
     */
    @Nullable
    public Path ringFile() {
        return options.ringFile();
    }

    /**
     * 读取已交付的包数。
     *
     * @return 包数
     */
    public int deliveredPackets() {
        return delivered.get();
    }

    /**
     * 读取抓包参数。
     *
     * @return 抓包参数
     */
    @Nonnull
    public CaptureOptions options() {
        return options;
    }

    /**
     * 解析 tshark 输出中的抓包层丢包统计。
     *
     * <p>tshark 把丢包数放在 stderr 的汇总行里，形如
     * {@code % Agcbs: 1234  5678 ...} 或中文版的"丢弃"。抓包层丢包说明
     * 抓包缓冲跟不上，是唯一能反映"本机性能是否够用"的指标。</p>
     *
     * @param line tshark 输出行
     * @return 丢包数，未能解析返回 0
     */
    public static long parseDroppedPackets(@Nonnull String line) {
        int marker = line.indexOf('%');
        if (marker < 0) {
            return 0L;
        }
        for (String token : line.substring(marker + 1).trim().split("\\s+")) {
            if (token.matches("\\d+")) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /**
     * 把回调包装为 {@link Consumer}，供需要消费式接口的场景复用。
     *
     * @param consumer 消费者
     * @return 监听器
     */
    @Nonnull
    public static CaptureListener ofPackets(@Nonnull Consumer<PacketRecord> consumer) {
        return new CaptureListener() {
            @Override
            public void onPacket(@Nonnull PacketRecord packet) {
                consumer.accept(packet);
            }
        };
    }
}
