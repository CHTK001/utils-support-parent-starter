package com.chua.common.support.network.filepush;

import com.chua.common.support.utils.ThreadUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.chua.common.support.network.filepush.FilePushConfig.MAGIC;
import static com.chua.common.support.network.filepush.FilePushConfig.PROTOCOL_VERSION;
import static com.chua.common.support.utils.ThreadUtils.newVirtualThreadPerTaskExecutor;

/**
 * 目录推送客户端：扫描本地目录，将全部文件高并发推送到服务端。
 *
 * <p><b>并发模型</b>（对应配置 {@link FilePushConfig}）：</p>
 * <ul>
 *   <li><b>文件级</b> — 每个文件一条独立 TCP 连接，由虚拟线程池并发执行
 *       （默认 CPU × 4，上限 256），通过 {@link Semaphore} 限流避免压垮服务端。</li>
 *   <li><b>文件内分片</b> — 单文件按 {@code chunkSize}（默认 1 MB）切分为多帧，
 *       连接内按序传输（保持分片顺序，服务端按序落盘）。</li>
 * </ul>
 *
 * <p><b>协议</b>与 {@link FilePushServer} 一致：连接握手（MAGIC + VERSION + fileCount=1）
 * → BEGIN（文件元数据）→ 若干 CHUNK → END → 等待 ACK 后关闭连接。</p>
 *
 * <p><b>推送流程</b>：</p>
 * <pre>
 * push()
 *   ├── 扫描 sourceDir，按 includes/excludes 过滤，生成文件清单
 *   ├── 若 config.incremental=true：开一条控制连接索取服务端清单（MANIFEST），
 *   │     跳过 size 与 mtime 均未变化的文件
 *   ├── 并发（clientFileParallelism）每条连接推送一个文件
 *   │     握手 → BEGIN(meta) → CHUNK(idx, data) × N → END → ACK
 *   ├── 若 config.cleanup=true：发一条 CLEANUP 控制连接，携带<b>完整</b>扫描清单
 *   │     （含增量跳过的文件），服务端删除清单之外的旧文件
 *   └── 输出统计（文件数、字节数、跳过数、错误数、耗时、吞吐）
 * </pre>
 *
 * <p>单文件推送失败不中断整体任务，计入错误统计后继续；返回的
 * {@link PushResult} 携带成功/失败明细。</p>
 *
 * <p><b>链式用法</b>（须在 {@link #push()} 之前调用）。客户端的同步目录由
 * {@link #sourceDir(Path)} 设定，与服务端的 {@code targetDir} 相对应：</p>
 * <pre>
 * try (FilePushClient client = FilePushClient.create()
 *         .sourceDir("/data/app").host("127.0.0.1").port(9777)
 *         .chunkSize(4 * 1024 * 1024).parallelism(64)
 *         .excludes("log", "tmp/").incremental(true)
 *         .onFile(r -&gt; System.out.println((r.success() ? "OK  " : "FAIL") + " " + r.relativePath()))) {
 *     PushResult result = client.push();
 * }
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FilePushClient implements AutoCloseable {

    /** SLF4J 日志（手写，避免 Lombok 注解处理器缺失时编译失败） */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FilePushClient.class);

    /** 客户端配置 */
    private final FilePushConfig config;

    /** 连接虚拟线程池 */
    private final ExecutorService executor;

    /** 文件并发限流（parallelism() 可在 push() 前按新并发数重建，故非 final） */
    private volatile Semaphore fileLimiter;

    /** 统计：成功推送的文件数 */
    private final AtomicLong filesPushed = new AtomicLong();

    /** 统计：成功推送的总字节数 */
    private final AtomicLong bytesPushed = new AtomicLong();

    /** 单文件完成回调（onFile() 可在 push() 前设置，故非 final） */
    private volatile Consumer<FileTaskResult> progressListener;

    /** 运行标记 */
    private volatile boolean closed;

    /**
     * 创建客户端实例。
     *
     * @param config 客户端配置（sourceDir 必填，host/port 指向服务端）
     */
    public FilePushClient(FilePushConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        this.config = config;
        this.executor = newVirtualThreadPerTaskExecutor();
        this.fileLimiter = new Semaphore(Math.max(1, config.effectiveClientParallelism()));
    }

    /**
     * 从系统属性（{@code filepush.*}）加载配置并创建实例（便捷方法）。
     *
     * @return 客户端实例
     */
    public static FilePushClient create() {
        return new FilePushClient(FilePushConfig.loadFromSystemProperties(FilePushConfig.defaults()));
    }

    /**
     * 设置同步目录（客户端待推送的本地根目录），链式调用。
     *
     * <p>与服务端 {@code targetDir(Path)} 相对应：客户端从该目录扫描文件，
     * 服务端按相同相对路径落盘。必须在 {@link #push()} 之前调用。</p>
     *
     * @param sourceDir 同步目录（源）
     * @return this
     */
    public FilePushClient sourceDir(Path sourceDir) {
        config.setSourceDir(sourceDir);
        return this;
    }

    /**
     * 设置待推送源目录（字符串路径重载），链式调用。
     *
     * @param sourceDir 源目录路径
     * @return this
     */
    public FilePushClient sourceDir(String sourceDir) {
        if (sourceDir == null || sourceDir.isBlank()) {
            throw new IllegalArgumentException("sourceDir 不能为空");
        }
        return sourceDir(Path.of(sourceDir.trim()));
    }

    /**
     * 设置服务端地址，链式调用。
     *
     * @param host 服务端地址
     * @return this
     */
    public FilePushClient host(String host) {
        config.setHost(host);
        return this;
    }

    /**
     * 设置服务端端口，链式调用。
     *
     * @param port 服务端端口
     * @return this
     */
    public FilePushClient port(int port) {
        config.setPort(port);
        return this;
    }

    /**
     * 设置分片大小，链式调用；须与服务端一致。
     *
     * @param chunkSize 分片字节数
     * @return this
     */
    public FilePushClient chunkSize(int chunkSize) {
        config.setChunkSize(chunkSize);
        return this;
    }

    /**
     * 设置并发推送文件数，链式调用。
     *
     * <p>构造时已按配置建好限流信号量，此处一并按新并发数重建，
     * 否则构造后设置并发数不会生效。</p>
     *
     * @param parallelism 并发文件数，小于等于 0 回退默认（CPU × 4，上限 256）
     * @return this
     */
    public FilePushClient parallelism(int parallelism) {
        config.setClientFileParallelism(parallelism);
        this.fileLimiter = new Semaphore(Math.max(1, config.effectiveClientParallelism()));
        return this;
    }

    /**
     * 设置是否增量推送，链式调用。
     *
     * @param incremental 是否跳过服务端 size 与 mtime 均未变化的文件
     * @return this
     */
    public FilePushClient incremental(boolean incremental) {
        config.setIncremental(incremental);
        return this;
    }

    /**
     * 设置推送后是否请求服务端清理旧文件，链式调用。
     *
     * @param cleanup 是否清理
     * @return this
     */
    public FilePushClient cleanup(boolean cleanup) {
        config.setCleanup(cleanup);
        return this;
    }

    /**
     * 设置排除模式，链式调用。
     *
     * @param excludes 排除模式列表（子串匹配）
     * @return this
     */
    public FilePushClient excludes(List<String> excludes) {
        config.setExcludes(excludes);
        return this;
    }

    /**
     * 设置排除模式（可变参数重载），链式调用。
     *
     * @param excludes 排除模式（子串匹配）
     * @return this
     */
    public FilePushClient excludes(String... excludes) {
        return excludes(new ArrayList<>(Arrays.asList(excludes)));
    }

    /**
     * 设置包含模式，链式调用。
     *
     * @param includes 包含模式列表（子串匹配）
     * @return this
     */
    public FilePushClient includes(List<String> includes) {
        config.setIncludes(includes);
        return this;
    }

    /**
     * 设置包含模式（可变参数重载），链式调用。
     *
     * @param includes 包含模式（子串匹配）
     * @return this
     */
    public FilePushClient includes(String... includes) {
        return includes(new ArrayList<>(Arrays.asList(includes)));
    }

    /**
     * 设置单文件完成回调，链式调用。
     *
     * <p>每个文件推送结束（成功或失败）后在推送该文件的线程上回调一次，可用于打印实时进度。
     * 增量模式下被跳过的文件不产生任务，因此不会回调。</p>
     *
     * <p>回调抛出的异常会被吞掉并记 warn，不影响整体推送。</p>
     *
     * @param listener 单文件结果回调，传 null 表示取消
     * @return this
     */
    public FilePushClient onFile(Consumer<FileTaskResult> listener) {
        this.progressListener = listener;
        return this;
    }

    /**
     * 获取底层配置，用于链式方法未覆盖的参数。
     *
     * @return 客户端配置
     */
    public FilePushConfig config() {
        return config;
    }

    /**
     * 推送源目录到服务端。
     *
     * @return 推送结果（含成功/失败明细）
     * @throws IOException 源目录不可读
     */
    public PushResult push() throws IOException {
        Path sourceDir = config.getSourceDir();
        if (sourceDir == null) {
            throw new IllegalArgumentException("sourceDir 未配置，无法推送");
        }
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("源目录不存在或不可访问: " + sourceDir.toAbsolutePath());
        }
        long startTime = System.nanoTime();
        List<Path> files = scanFiles(sourceDir);

        // 增量同步：跳过服务端已存在且 size + mtime 均未变化的文件
        long skipped = 0;
        List<Path> pending = files;
        if (config.isIncremental()) {
            Map<String, long[]> remote = fetchServerManifest();
            pending = new ArrayList<>(files.size());
            for (Path file : files) {
                if (isUnchanged(sourceDir, file, remote)) {
                    skipped++;
                } else {
                    pending.add(file);
                }
            }
        }

        log.info("FilePushClient 开始推送 {} → {}:{}，共 {} 个文件，待推 {} 个，跳过 {} 个，并发 {}，分片 {}KB",
                sourceDir.toAbsolutePath(), config.getHost(), config.getPort(),
                files.size(), pending.size(), skipped,
                config.effectiveClientParallelism(), config.effectiveChunkSize() / 1024);

        List<CompletableFuture<FileTaskResult>> futures = new ArrayList<>(pending.size());
        for (Path file : pending) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                FileTaskResult result = pushFile(sourceDir, file);
                notifyProgress(result);
                return result;
            }, executor));
        }

        // 等待所有文件推送完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 可选：清理服务端旧文件。清单必须是扫描到的全部文件（含增量跳过的），
        // 否则增量模式下未变更文件会被服务端当作"旧文件"删除
        if (config.isCleanup()) {
            try {
                sendCleanup(relativePaths(sourceDir, files));
                log.info("已请求服务端清理旧文件");
            } catch (IOException e) {
                log.warn("清理指令发送失败: {}", e.getMessage());
            }
        }
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        List<FileTaskResult> results = futures.stream().map(CompletableFuture::join).toList();
        long success = results.stream().filter(t -> t.success()).count();
        long failed = results.size() - success;
        double mbs = elapsedMs > 0
                ? (bytesPushed.get() / 1024.0 / 1024.0) / (elapsedMs / 1000.0)
                : 0;
        log.info("FilePushClient 推送完成：成功 {} 个文件 / {} 字节，跳过 {} 个，失败 {} 个，"
                        + "耗时 {} ms，吞吐 {} MB/s",
                success, bytesPushed.get(), skipped, failed, elapsedMs, String.format("%.2f", mbs));
        return new PushResult(results, success, failed, skipped, elapsedMs, mbs);
    }

    /**
     * 触发单文件完成回调。回调异常不得影响推送，故吞掉并记 warn。
     *
     * @param result 单文件任务结果
     */
    private void notifyProgress(FileTaskResult result) {
        Consumer<FileTaskResult> listener = progressListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(result);
        } catch (RuntimeException e) {
            log.warn("进度回调异常（已忽略）: {}", e.toString());
        }
    }

    /**
     * 计算相对路径清单（/ 分隔），用于 CLEANUP 指令。
     *
     * @param sourceDir 源目录
     * @param files     文件列表
     * @return 相对路径列表
     */
    private List<String> relativePaths(Path sourceDir, List<Path> files) {
        List<String> paths = new ArrayList<>(files.size());
        for (Path file : files) {
            paths.add(sourceDir.relativize(file).toString().replace('\\', '/'));
        }
        return paths;
    }

    /**
     * 判断文件是否与服务端清单记录一致（size 与 mtime 均相同）。
     *
     * @param sourceDir 源目录
     * @param file      待判断文件
     * @param remote    服务端清单：相对路径 → {size, mtimeMillis}
     * @return true 表示未变化，可跳过
     */
    private boolean isUnchanged(Path sourceDir, Path file, Map<String, long[]> remote) {
        String rel = sourceDir.relativize(file).toString().replace('\\', '/');
        long[] record = remote.get(rel);
        if (record == null) {
            return false;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return record[0] == attrs.size()
                    && record[1] == attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            // 属性读不到就视为已变化：宁可重推，不可漏推
            return false;
        }
    }

    /**
     * 索取服务端目标目录的现有文件清单（控制连接，fileCount=0）。
     *
     * @return 相对路径 → {size, mtimeMillis}
     * @throws IOException 连接或协议失败
     */
    private Map<String, long[]> fetchServerManifest() throws IOException {
        try (Socket socket = openSocket()) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), config.effectiveIoBufferSize()));
            out.writeInt(MAGIC);
            out.writeByte(PROTOCOL_VERSION);
            out.writeInt(0);
            out.flush();
            int ack = in.readUnsignedByte();
            if (ack != FilePushConfig.MSG_ACK) {
                throw new IOException("清单握手失败，服务端回 0x" + Integer.toHexString(ack));
            }
            out.writeByte(FilePushConfig.MSG_MANIFEST);
            out.flush();
            int resp = in.readUnsignedByte();
            if (resp != FilePushConfig.MSG_MANIFEST_RESP) {
                throw new IOException("期望清单响应但收到 0x" + Integer.toHexString(resp));
            }
            int count = in.readInt();
            Map<String, long[]> manifest = new HashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                manifest.put(new String(bytes, StandardCharsets.UTF_8),
                        new long[]{in.readLong(), in.readLong()});
            }
            log.debug("服务端清单 {} 个文件", count);
            return manifest;
        }
    }

    /**
     * 扫描源目录下的全部文件（排除 excludes 模式）。
     *
     * @param sourceDir 源目录
     * @return 文件路径列表
     * @throws IOException 扫描失败
     */
    private List<Path> scanFiles(Path sourceDir) throws IOException {
        List<String> excludes = config.getExcludes();
        List<String> includes = config.getIncludes();
        final List<Path> result = new ArrayList<>();
        try (var walk = Files.walk(sourceDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> matchesIncludes(sourceDir, p, includes))
                    .filter(p -> !matchesExcludes(sourceDir, p, excludes))
                    .forEach(result::add);
        }
        return result;
    }

    /** 判断相对路径是否匹配包含模式（空模式列表 = 全部匹配） */
    private boolean matchesIncludes(Path sourceDir, Path file, List<String> includes) {
        if (includes == null || includes.isEmpty()) {
            return true;
        }
        String rel = sourceDir.relativize(file).toString().replace('\\', '/');
        for (String pattern : includes) {
            if (rel.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 判断相对路径是否匹配排除模式 */
    private boolean matchesExcludes(Path sourceDir, Path file, List<String> excludes) {
        if (excludes == null || excludes.isEmpty()) {
            return false;
        }
        String rel = sourceDir.relativize(file).toString().replace('\\', '/');
        for (String pattern : excludes) {
            if (rel.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 推送单个文件到服务端。
     *
     * @param sourceDir 源目录（用于计算相对路径）
     * @param file 文件绝对路径
     * @return 单文件任务结果
     */
    private FileTaskResult pushFile(Path sourceDir, Path file) {
        String relativePath = sourceDir.relativize(file).toString().replace('\\', '/');
        boolean acquired = false;
        try {
            fileLimiter.acquire();
            acquired = true;
            long fileSize = Files.size(file);
            long mtimeMillis = Files.getLastModifiedTime(file).toMillis();
            int chunkSize = config.effectiveChunkSize();
            int lastChunk = fileSize == 0 ? 0 : (int) (fileSize - 1) / chunkSize;

            try (Socket socket = openSocket()) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream(), config.effectiveIoBufferSize()));

                // 握手
                out.writeInt(MAGIC);
                out.writeByte(PROTOCOL_VERSION);
                out.writeInt(1);
                out.flush();
                int ack = in.readUnsignedByte();
                if (ack != FilePushConfig.MSG_ACK) {
                    throw new IOException("握手失败，服务端回 0x" + Integer.toHexString(ack));
                }

                // BEGIN：meta = relativePath \0 fileSize \0 lastChunk \0 mtimeMillis \0
                String meta = relativePath + "\0" + fileSize + "\0" + lastChunk + "\0"
                        + mtimeMillis + "\0";
                byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);
                out.writeByte(FilePushConfig.MSG_BEGIN);
                out.writeInt(metaBytes.length);
                out.write(metaBytes);

                // CHUNK 分片（连接内按序）
                int ioBuffer = config.effectiveIoBufferSize();
                int chunkIndex = 0;
                try (InputStream fis = new BufferedInputStream(Files.newInputStream(file), ioBuffer)) {
                    int total = 0;
                    while (total < fileSize) {
                        int remaining = (int) Math.min(chunkSize, fileSize - total);
                        int read = 0;
                        byte[] chunkData = new byte[remaining];
                        while (read < remaining) {
                            int n = fis.read(chunkData, read, remaining - read);
                            if (n == -1) {
                                throw new IOException("源文件读取意外结束: " + relativePath
                                        + "（期望 " + fileSize + " 字节）");
                            }
                            read += n;
                        }
                        out.writeByte(FilePushConfig.MSG_CHUNK);
                        out.writeInt(chunkIndex);
                        out.writeInt(remaining);
                        out.write(chunkData);
                        chunkIndex++;
                        total += remaining;
                    }
                }
                // END
                out.writeByte(FilePushConfig.MSG_END);
                out.flush();

                // 等待 ACK
                int resp = in.readUnsignedByte();
                if (resp == FilePushConfig.MSG_ERROR) {
                    int errLen = in.readInt();
                    byte[] errBytes = new byte[errLen];
                    in.readFully(errBytes);
                    String errMsg = new String(errBytes, StandardCharsets.UTF_8);
                    throw new IOException("服务端拒绝文件 " + relativePath + ": " + errMsg);
                }
                if (resp != FilePushConfig.MSG_ACK) {
                    throw new IOException("文件 " + relativePath + " 等待 ACK 失败（回 0x"
                            + Integer.toHexString(resp) + "）");
                }

                out.flush();
                filesPushed.incrementAndGet();
                bytesPushed.addAndGet(fileSize);
                log.debug("文件推送完成 {} ({} 字节, {} 分片)", relativePath, fileSize, lastChunk + 1);
                return new FileTaskResult(relativePath, true, fileSize, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FileTaskResult(relativePath, false, 0, "被中断");
        } catch (Exception e) {
            log.warn("文件推送失败 {} ({}): {}", relativePath, file, e.getMessage());
            return new FileTaskResult(relativePath, false, 0, e.toString());
        } finally {
            if (acquired) {
                fileLimiter.release();
            }
        }
    }

    /** 打开到服务端的 TCP 连接（带重连重试） */
    private Socket openSocket() throws IOException {
        int maxRetries = 5;
        IOException last = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(config.getHost(), config.getPort()),
                        config.getConnectTimeoutMs());
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(config.getReadTimeoutMs());
                return socket;
            } catch (IOException e) {
                last = e;
                ThreadUtils.sleep(500L * (attempt + 1));
            }
        }
        throw new IOException("无法连接服务端 " + config.getHost() + ":" + config.getPort()
                + "（已重试 " + maxRetries + " 次）", last);
    }

    /**
     * 发送清理旧文件指令（控制连接，fileCount=0）。
     *
     * <p>帧格式：{@code [byte MSG_CLEANUP][int count]}，其后每条为
     * {@code [int pathLen][bytes path(utf-8)]}。服务端删除清单之外的文件。</p>
     *
     * @param expected 本次扫描到的全部相对路径（含增量跳过的文件）
     * @throws IOException 连接或协议失败
     */
    private void sendCleanup(List<String> expected) throws IOException {
        try (Socket socket = openSocket()) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), config.effectiveIoBufferSize()));
            out.writeInt(MAGIC);
            out.writeByte(PROTOCOL_VERSION);
            out.writeInt(0);
            out.flush();
            int ack = in.readUnsignedByte();
            if (ack != FilePushConfig.MSG_ACK) {
                throw new IOException("清理握手失败，服务端回 0x" + Integer.toHexString(ack));
            }
            out.writeByte(FilePushConfig.MSG_CLEANUP);
            out.writeInt(expected.size());
            for (String path : expected) {
                byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            out.flush();
            int resp = in.readUnsignedByte();
            if (resp == FilePushConfig.MSG_ERROR) {
                int errLen = in.readInt();
                byte[] errBytes = new byte[errLen];
                in.readFully(errBytes);
                throw new IOException("服务端清理失败: "
                        + new String(errBytes, StandardCharsets.UTF_8));
            }
            if (resp != FilePushConfig.MSG_ACK) {
                throw new IOException("清理等待 ACK 失败（回 0x" + Integer.toHexString(resp) + "）");
            }
        }
    }

    /**
     * 释放客户端资源（关闭线程池）。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ThreadUtils.shutdownNow(executor);
    }

    /**
     * 单个文件推送任务结果。
     *
     * @param relativePath 文件相对路径
     * @param success 是否推送成功
     * @param fileSize 文件字节数（成功时）
     * @param error 失败原因（成功时为 null）
     */
    public record FileTaskResult(String relativePath, boolean success, long fileSize, String error) {
    }

    /**
     * 整体推送结果。
     *
     * @param tasks 每个文件的任务结果
     * @param successCount 成功文件数
     * @param failedCount 失败文件数
     * @param skippedCount 增量同步跳过的未变更文件数（未开启增量时为 0）
     * @param elapsedMs 总耗时（毫秒）
     * @param throughputMbs 吞吐（MB/s）
     */
    public record PushResult(List<FileTaskResult> tasks, long successCount, long failedCount,
                             long skippedCount, long elapsedMs, double throughputMbs) {

        /**
         * 是否有失败。
         *
         * @return true 表示存在失败文件
         */
        public boolean hasFailures() {
            return failedCount > 0;
        }

        /**
         * 获取失败文件明细。
         *
         * @return 失败任务列表
         */
        public List<FileTaskResult> failures() {
            return tasks.stream().filter(t -> !t.success()).toList();
        }
    }
}
