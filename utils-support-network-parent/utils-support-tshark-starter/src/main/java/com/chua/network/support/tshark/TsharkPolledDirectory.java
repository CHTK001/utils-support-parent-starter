package com.chua.network.support.tshark;

import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledDirectorySupport;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.lang.directory.executor.VirtualThreadPollerExecutor;
import com.chua.network.support.tshark.cli.TsharkCliConfig;
import com.chua.network.support.tshark.cli.TsharkCliProvider;
import com.chua.network.support.tshark.cli.TsharkSettings;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * tshark 抓包文件轮询目录。
 *
 * <p>监听抓包输出目录，新文件出现或被修改时调用 tshark 把抓包文件解析为 JSON，
 * 并交由 {@link PacketParserService} 解析为 {@link PacketRecord} 列表（含协议还原），
 * 通过 {@link Consumer} 回调给业务方。</p>
 *
 * <h2>与实时网卡采集的分工</h2>
 * <p>本类面向<b>离线文件</b>：抓包已落盘，按需批量解析。
 * 需要"边抓边还原"请用
 * {@link com.chua.network.support.tshark.capture.TsharkCapturePolledDirectory}，
 * 它从网卡实时流式解析，并附带 TCP 流重组与会话聚合。</p>
 *
 * <h2>相对早期实现的三处修正</h2>
 * <ul>
 *   <li><b>tshark 可执行文件自动装配</b> — 早期实现只认 PATH 上的 {@code tshark}，
 *       找不到就抛异常；现在委托 {@link TsharkCliProvider} 走
 *       "定位 → 包管理器 → 便携版下载"三级策略。</li>
 *   <li><b>流式解析</b> — 早期实现把 tshark 的全部输出读进一个
 *       {@code StringBuilder}（上限 64MB）再整体解析，峰值内存约为 JSON 文本的两倍；
 *       现在用 {@link PacketParserService#parseAll(java.io.InputStream)} 逐包流式读取，
 *       内存只与单包大小相关。逐包 JSON 往返序列化也一并去掉。</li>
 *   <li><b>订阅过滤</b> — 早期实现无视 {@link DirectoryPollerEnvironment} 的事件订阅，
 *       只订阅 {@code DELETE} 的监听器也会收到 {@code CREATE}；
 *       现在统一走 {@link PolledDirectorySupport}，与其他实现行为一致。</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * TsharkPolledDirectory poller = new TsharkPolledDirectory("/var/captures");
 * poller.setPacketListener(records -> records.forEach(r -> log.info("parsed: {}", r.info())));
 * poller.addListener(new SimplePolledListener());
 *
 * DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(
 *     Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE), 5, TimeUnit.SECONDS);
 * poller.start(env);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TsharkPolledDirectory implements PolledDirectory {

    /**
     * 被识别的抓包文件扩展名
     *
     * <p>早期只认 {@code .pcap}，漏掉了 pcapng——
     * 而 pcapng 才是现代抓包工具（含 Wireshark 4.x 默认）的输出格式。</p>
     */
    private static final List<String> CAPTURE_EXTENSIONS = List.of(".pcap", ".pcapng");

    /**
     * 单次 tshark 解析的最长等待时间（毫秒），未配置 {@code tshark.execTimeout.seconds} 时使用
     */
    private static final long TSHARK_TIMEOUT_MILLIS = 120_000L;

    /**
     * 读取抓包文件时使用的显示过滤器配置键
     */
    private static final String KEY_READ_FILTER = "tshark.readFilter";

    /**
     * 被监听的目录路径
     */
    private final String listenPath;

    /**
     * 文件名 -&gt; 最后修改时间戳（毫秒）的快照缓存
     */
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    /**
     * 监听器与运行状态的公共支撑件
     */
    private final PolledDirectorySupport support = new PolledDirectorySupport(getClass().getSimpleName());

    /**
     * 数据包记录回调消费者
     */
    private final AtomicReference<Consumer<List<PacketRecord>>> packetListener = new AtomicReference<>();

    /**
     * tshark 命令行配置
     */
    private final AtomicReference<TsharkCliConfig> cliConfig =
            new AtomicReference<>(TsharkCliConfig.defaults());

    /**
     * 调用方是否显式提供了命令行配置
     *
     * <p>显式配置优先于 {@link DirectoryPollerEnvironment} 中的 {@code tshark.*} 属性：
     * 显式设置意味着"这份配置我说了算"，若仍被环境属性逐项覆盖，
     * 调用方就失去了可预测性——例如 {@code autoInstall(false)} 会被环境的默认值
     * 悄悄改回 true，进而在完全没装 tshark 的机器上触发一次 100MB 下载。</p>
     */
    private volatile boolean explicitConfigProvided;

    /**
     * 环境配置
     */
    private DirectoryPollerEnvironment environment;

    /**
     * 已经投递过数据包的文件名
     *
     * <p>与 {@link #cache} 分开维护：{@code cache} 记录"文件存在及其修改时间"，
     * 在 {@code start()} 时就会写入已有文件；若只用它判断是否需要处理，
     * 启动前就存在的文件将永远不会被解析。这里单独记录"是否已投递"，
     * 使两者职责分离：文件状态与是否已处理互不干扰。</p>
     */
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    /**
     * 是否处理启动前已存在的抓包文件
     */
    private volatile boolean processExisting;

    /**
     * 当前运行中的轮询执行器
     */
    private DirectoryPollerExecutor executor;

    /**
     * 构造 tshark 轮询目录监听器。
     *
     * @param listenPath 被监听的目录路径
     */
    public TsharkPolledDirectory(String listenPath) {
        this.listenPath = listenPath;
    }

    /**
     * 设置数据包记录回调。
     *
     * <p>新文件或被修改的文件会通过 TShark 解析，结果通过此回调暴露。</p>
     * @param listener 解析结果消费者
     */
    public void setPacketListener(Consumer<List<PacketRecord>> listener) {
        this.packetListener.set(listener);
    }

    /**
     * 设置 tshark 可执行文件路径。
     *
     * <p>已安装 tshark 时可传入完整路径如
     * {@code "C:/Program Files/Wireshark/tshark.exe"}。
     * 未安装时留空即可，{@link TsharkCliProvider} 会按三级策略自动装配，
     * 无需运维手工安装。</p>
     * @param binary tshark 可执行文件路径
     */
    public void setTsharkBinary(String binary) {
        if (binary != null && !binary.isBlank()) {
            TsharkCliConfig current = cliConfig.get();
            this.cliConfig.set(TsharkCliConfig.builder()
                    .from(current)
                    .binary(binary)
                    .build());
            this.explicitConfigProvided = true;
        }
    }

    /**
     * 设置 tshark 命令行配置。
     *
     * @param config 命令行配置
     */
    public void setCliConfig(@Nonnull TsharkCliConfig config) {
        this.cliConfig.set(config);
        this.explicitConfigProvided = true;
    }

    /**
     * 获取当前生效的 tshark 命令行配置。
     *
     * <p>{@link #start(DirectoryPollerEnvironment, DirectoryPollerExecutor)} 会在
     * 环境配置存在时用其中的 {@code tshark.*} 属性刷新配置（但保留显式指定的
     * 可执行文件路径），因此构造时设置的配置不一定等于最终生效的配置。
     * 本方法返回最终生效的那一份，供诊断与测试断言使用。</p>
     *
     * @return 生效的命令行配置
     */
    @Nonnull
    public TsharkCliConfig effectiveCliConfig() {
        return cliConfig.get();
    }

    /**
     * 设置是否处理启动前已存在的抓包文件。
     *
     * <p>{@code start()} 会把目录中已有文件写入初始快照，因此默认<b>不</b>处理它们
     * ——这符合"只监听增量"的常规语义。但使用者把目录指向一个已积累大量 pcap 的
     * 历史目录时，会发现一个包都拿不到，且没有任何提示。</p>
     *
     * <p>置为 {@code true} 后，首次 {@link #upgrade()} 会把已有但未投递过的文件
     * 当作新文件处理（触发一次 {@link WatcherEvent#CREATE} 并解析）。</p>
     *
     * @param processExisting 是否处理已有文件
     */
    public void setProcessExisting(boolean processExisting) {
        this.processExisting = processExisting;
    }

    /**
     * 当前是否处理启动前已存在的抓包文件。
     *
     * @return 处理已有文件返回 true
     */
    public boolean processExisting() {
        return processExisting;
    }

    @Override
    /**
     * 添加监听器
     */
    public void addListener(PolledListener listener) {
        support.addListener(listener);
    }

    /**
     * 移除监听器。
     *
     * @param listener 监听器
     * @return 移除前该监听器是否已注册
     */
    public boolean removeListener(PolledListener listener) {
        return support.removeListener(listener);
    }

    @Override
    /**
     * 开始
     */
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        if (!support.markRunning()) {
            log.warn("TShark 轮询目录已在运行，忽略重复启动: {}", listenPath);
            return;
        }
        this.environment = environment;
        if (environment != null && !explicitConfigProvided) {
            // 未显式配置时才用环境配置兜底；显式配置优先（见 explicitConfigProvided 注释）
            this.cliConfig.set(TsharkCliConfig.from(environment));
        }

        ensureDirExists(listenPath);

        // 初始化缓存：扫描当前已有文件
        for (String name : captureFiles()) {
            cache.put(name, lastModified(name));
        }

        // 委托给默认执行器
        if (executor == null) {
            executor = new VirtualThreadPollerExecutor(this, environment);
        }
        this.executor = executor;
        executor.start();
        log.info("TShark 轮询目录已启动: {}", listenPath);
    }

    @Override
    /**
     * Upgrade
     */
    public void upgrade() {
        File dir = new File(listenPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }

        Map<String, Long> currentSnap = new HashMap<>();
        for (String name : captureFiles()) {
            currentSnap.put(name, lastModified(name));
        }

        // 检测新增与修改
        for (Map.Entry<String, Long> entry : currentSnap.entrySet()) {
            String name = entry.getKey();
            Long previous = cache.get(name);
            boolean alreadyDelivered = processed.contains(name);
            if (previous == null) {
                cache.put(name, entry.getValue());
                support.fire(listenPath, WatcherEvent.CREATE, name, environment,
                        observer -> observer.setFileSize(sizeOf(name)));
                handleNewOrModified(name);
            } else if (!alreadyDelivered && processExisting) {
                // 启动前就存在、且开启"处理已有文件"：作为新文件投递一次，
                // 避免把历史目录指向这里时一个包都拿不到
                cache.put(name, entry.getValue());
                log.info("处理启动前已存在的抓包文件: {}", name);
                support.fire(listenPath, WatcherEvent.CREATE, name, environment,
                        observer -> observer.setFileSize(sizeOf(name)));
                handleNewOrModified(name);
            } else if (!entry.getValue().equals(previous)) {
                cache.put(name, entry.getValue());
                support.fire(listenPath, WatcherEvent.MODIFY, name, environment,
                        observer -> observer.setFileSize(sizeOf(name)));
                handleNewOrModified(name);
            }
        }

        // 检测删除
        for (String name : new HashSet<>(cache.keySet())) {
            if (!currentSnap.containsKey(name)) {
                cache.remove(name);
                processed.remove(name);
                support.fire(listenPath, WatcherEvent.DELETE, name, environment);
            }
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
        support.markStopped();
        if (executor != null) {
            executor.close();
        }
        cache.clear();
        processed.clear();
        support.clearListeners();
        log.info("TShark 轮询目录已停止: {}", listenPath);
    }

    @Override
    /**
     * 是否处于运行中
     */
    public boolean isRunning() {
        return support.isRunning();
    }

    // ==================== 私有方法 ====================

    /**
     * 列出目录下所有抓包文件。
     *
     * @return 文件名集合
     */
    @Nonnull
    private Set<String> captureFiles() {
        Set<String> names = new HashSet<>();
        File[] files = new File(listenPath).listFiles();
        if (files == null) {
            return names;
        }
        for (File file : files) {
            if (file.isFile() && isCaptureFile(file.getName())) {
                names.add(file.getName());
            }
        }
        return names;
    }

    /**
     * 判断文件名是否为受支持的抓包文件。
     *
     * @param name 文件名
     * @return 是抓包文件返回 true
     */
    private boolean isCaptureFile(@Nonnull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : CAPTURE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取文件的最后修改时间。
     *
     * @param name 文件名
     * @return 毫秒时间戳
     */
    private long lastModified(@Nonnull String name) {
        return new File(listenPath, name).lastModified();
    }

    /**
     * 取文件大小。
     *
     * @param name 文件名
     * @return 字节数
     */
    private long sizeOf(@Nonnull String name) {
        return new File(listenPath, name).length();
    }

    /**
     * 确保被监听的目录存在（不存在则创建）。
     * <p>确保被监听的目录存在（不存在则创建）。 @param path 目录路径 */
    private void ensureDirExists(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                log.info("创建监听目录: {}", path);
            } else {
                log.warn("无法创建监听目录: {}", path);
            }
        }
    }

    /**
     * 处理新增或修改的抓包文件。
     *
     * <p>调用 TShark 解析，逐包转换为 {@link PacketRecord}，
     * 触发 数据包监听器 回调（即使解析失败也会以空列表回调一次）。</p>
     * @param fileName 文件名
     */
    private void handleNewOrModified(String fileName) {
        List<PacketRecord> records = List.of();
        try {
            records = parsePcapFile(fileName);
        } catch (Exception e) {
            log.warn("解析抓包文件失败: {}", fileName, e);
            support.fireError(listenPath, fileName, e);
        } finally {
            // 无论解析成功与否都记为已投递：否则一个损坏的文件会在每一轮
            // upgrade() 里被反复重试，把 tshark 拉起来一次又一次
            processed.add(fileName);
        }
        Consumer<List<PacketRecord>> callback = packetListener.get();
        if (callback == null) {
            return;
        }
        try {
            callback.accept(records);
        } catch (Exception e) {
            log.error("packetListener 回调异常: {}", fileName, e);
        }
    }

    /**
     * 调用 tshark 解析抓包文件，逐包转换为 {@link PacketRecord}。
     *
     * <p>用流式游标逐包读取 tshark 输出，内存占用与文件大小无关。</p>
     *
     * @param fileName 抓包文件名
     * @return 解析出的数据包记录列表
     * @throws Exception 解析失败
     */
    private List<PacketRecord> parsePcapFile(String fileName) throws Exception {
        Path capture = new File(listenPath, fileName).toPath();
        TsharkCliConfig config = cliConfig.get();
        Path executable = TsharkCliProvider.getInstance().require(config);

        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("-r");
        command.add(capture.toAbsolutePath().toString());
        command.add("-T");
        command.add("json");
        command.add("-x");
        String readFilter = readFilter();
        if (readFilter != null) {
            command.add("-Y");
            command.add(readFilter);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        // stderr 不能并入 stdout：tshark 的告警行会混进 JSON 数组使整个文件解析失败
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = processBuilder.start();
        long timeoutMillis = config.execTimeoutSeconds() > 0
                ? config.execTimeoutSeconds() * 1000L
                : TSHARK_TIMEOUT_MILLIS;
        List<PacketRecord> records;
        try {
            records = PacketParserService.parseAll(process.getInputStream());
        } finally {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("tshark 解析超时(" + timeoutMillis + "ms): " + fileName);
            }
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("tshark 退出码 " + process.exitValue() + ": " + fileName);
        }
        log.debug("解析抓包文件 {} 完成，共 {} 包", fileName, records.size());
        return records;
    }

    /**
     * 读取抓包文件时使用的显示过滤器。
     *
     * @return 过滤器表达式，未配置返回 {@code null}
     */
    @Nullable
    private String readFilter() {
        return TsharkSettings.string(KEY_READ_FILTER, environment, null);
    }

    /**
     * 获取当前快照的文件名集合。
     * @return 当前缓存的文件名集合
     */
    public Set<String> snapshot() {
        return new HashSet<>(cache.keySet());
    }
}
