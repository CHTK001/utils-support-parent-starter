package com.chua.common.support.network.filepush;

import com.chua.common.support.utils.ThreadUtils;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.chua.common.support.network.filepush.FilePushConfig.MAGIC;
import static com.chua.common.support.network.filepush.FilePushConfig.PROTOCOL_VERSION;

/**
 * 目录推送服务端：接收客户端推送的整个目录并写入本地目标目录。
 *
 * <p><b>并发模型</b>（对应配置 {@link FilePushConfig}）：</p>
 * <ul>
 *   <li><b>连接层</b> — {@link ServerSocket} 接受连接后，每个连接交给虚拟线程处理，
 *       连接数上限由 {@link Semaphore} 控制（默认 CPU × 4，上限 256）；饱和时后续连接
 *       在该信号量上排队等待，而不是被拒绝，避免瞬时溢出丢文件。</li>
 *   <li><b>写盘层</b> — 每条连接内，分片按序读取后调度到写盘虚拟线程池，
 *       整个文件只开一个 {@code FileChannel}，用 {@code write(buffer, position)} 定位写落盘
 *       （定位写不改变通道位置，且各分片写入区间互不重叠，因此无需业务侧加锁）。
 *       注意 JDK 对同一通道的定位写在内部 {@code positionLock} 上串行化，
 *       写盘池带来的是"网络读与磁盘写重叠"，而非写盘并行。</li>
 * </ul>
 *
 * <p><b>协议帧格式</b>（全部大端序，消息类型常量见 {@link FilePushConfig}）：</p>
 * <pre>
 * 连接握手：  客户端 → 服务端  [int MAGIC][byte VERSION][int fileCount]
 *            服务端 → 客户端  [byte MSG_ACK]
 *            fileCount 语义：0=控制连接（MANIFEST/CLEANUP）
 *                          1=单文件连接（客户端收到 ACK 后直接关闭）
 *                         &gt;1=多文件连接（末尾还有一条 DONE/CLEANUP）
 *
 * 文件消息（客户端 → 服务端）：
 *   BEGIN:  [byte MSG_BEGIN][int len][bytes meta]
 *           meta 为四段以 0x00 分隔的 UTF-8 字符串：
 *           relativePath / fileSize / lastChunk / mtimeMillis
 *           （mtimeMillis 供服务端落盘后还原源文件修改时间，增量同步依赖它）
 *   CHUNK:  [byte MSG_CHUNK][int chunkIndex][int len][bytes data]
 *   END:    [byte MSG_END]   （当前文件全部分片发完，等待服务端 ACK 后发下一文件）
 *
 * 控制消息（客户端 → 服务端，fileCount=0 的连接）：
 *   MANIFEST: [byte MSG_MANIFEST]
 *             → 服务端回 [byte MSG_MANIFEST_RESP][int count]
 *               每条 [int pathLen][bytes path][long size][long mtimeMillis]
 *   CLEANUP:  [byte MSG_CLEANUP][int count]
 *             每条 [int pathLen][bytes path]；服务端删除清单之外的文件后回 ACK
 *
 * 多文件连接末尾（客户端 → 服务端）：
 *   DONE:   [byte MSG_DONE]
 *
 * 服务端 → 客户端：
 *   ACK:    [byte MSG_ACK]
 *   错误:   [byte MSG_ERROR][int len][bytes message(utf-8)]，随后关闭连接
 * </pre>
 *
 * <p>每个连接推送 {@code fileCount} 个文件（客户端按文件并发切分），同一文件的所有分片
 * 在同一连接内串行传输，文件完整后回 ACK，再开始下一文件。</p>
 *
 * <p><b>链式用法</b>（须在 {@link #start()} 之前调用）：</p>
 * <pre>
 * // 直接指定同步目录
 * try (FilePushServer server = new FilePushServer(FilePushConfig.defaults())
 *         .targetDir("/data/received").host("0.0.0.0").port(9777)
 *         .chunkSize(4 * 1024 * 1024).cleanup(true)) {
 *     server.start();
 * }
 *
 * // 先吃 filepush.* 系统属性，再链式覆盖
 * FilePushServer.create().targetDir(Path.of("/data/received")).port(9777).start();
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FilePushServer implements AutoCloseable {

    /** SLF4J 日志 */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FilePushServer.class);

    /** 清单条数上限，防止损坏或恶意的 count 值导致巨额预分配 */
    private static final int MAX_MANIFEST_ENTRIES = 2_000_000;

    /** 服务端配置 */
    private final FilePushConfig config;

    /** 监听套接字 */
    private volatile ServerSocket serverSocket;

    /** 连接数信号量 */
    private volatile Semaphore connectionLimiter;

    /** 写盘虚拟线程池 */
    private volatile ExecutorService writeExecutor;

    /** 运行标记 */
    private volatile boolean running;

    /** 统计：成功接收的文件数 */
    private final AtomicLong filesReceived = new AtomicLong();

    /** 统计：成功接收的总字节数 */
    private final AtomicLong bytesReceived = new AtomicLong();

    /** 统计：发生的错误数 */
    private final AtomicLong errors = new AtomicLong();

    /**
     * 创建服务端实例。
     *
     * @param config 服务端配置（targetDir 必填）
     */
    public FilePushServer(FilePushConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        this.config = config;
    }

    /**
     * 从系统属性（{@code filepush.*}）加载配置并创建实例（便捷方法）。
     *
     * @return 服务端实例
     */
    public static FilePushServer create() {
        return new FilePushServer(FilePushConfig.loadFromSystemProperties(FilePushConfig.defaults()));
    }

    /**
     * 设置同步目录（服务端接收并落盘的根目录），链式调用。
     *
     * <p>必须在 {@link #start()} 之前调用。</p>
     *
     * @param targetDir 同步目录
     * @return this
     */
    public FilePushServer targetDir(Path targetDir) {
        config.setTargetDir(targetDir);
        return this;
    }

    /**
     * 设置同步目录（字符串路径重载），链式调用。
     *
     * @param targetDir 同步目录路径
     * @return this
     */
    public FilePushServer targetDir(String targetDir) {
        if (targetDir == null || targetDir.isBlank()) {
            throw new IllegalArgumentException("targetDir 不能为空");
        }
        return targetDir(Path.of(targetDir.trim()));
    }

    /**
     * 设置监听地址，链式调用。
     *
     * @param host 监听地址
     * @return this
     */
    public FilePushServer host(String host) {
        config.setHost(host);
        return this;
    }

    /**
     * 设置监听端口，链式调用；传 0 由系统分配空闲端口。
     *
     * @param port 监听端口
     * @return this
     */
    public FilePushServer port(int port) {
        config.setPort(port);
        return this;
    }

    /**
     * 设置分片大小，链式调用；须与客户端一致。
     *
     * @param chunkSize 分片字节数
     * @return this
     */
    public FilePushServer chunkSize(int chunkSize) {
        config.setChunkSize(chunkSize);
        return this;
    }

    /**
     * 设置并发连接数上限，链式调用。
     *
     * @param parallelism 并发连接数
     * @return this
     */
    public FilePushServer connectionParallelism(int parallelism) {
        config.setServerConnectionParallelism(parallelism);
        return this;
    }

    /**
     * 设置是否允许客户端请求清理同步目录中的旧文件，链式调用。
     *
     * @param cleanup 是否允许清理
     * @return this
     */
    public FilePushServer cleanup(boolean cleanup) {
        config.setCleanup(cleanup);
        return this;
    }

    /**
     * 获取底层配置，用于链式方法未覆盖的参数。
     *
     * @return 服务端配置
     */
    public FilePushConfig config() {
        return config;
    }

    /**
     * 启动服务端并开始监听。
     *
     * @return 实际监听端口（port=0 时由系统分配）
     * @throws IOException 启动失败
     */
    public int start() throws IOException {
        Path target = config.getTargetDir();
        if (target == null) {
            throw new IllegalArgumentException("targetDir 未配置，无法启动 FilePushServer");
        }
        Files.createDirectories(target);
        connectionLimiter = new Semaphore(Math.max(1, config.effectiveServerParallelism()));
        writeExecutor = ThreadUtils.newVirtualThreadPerTaskExecutor();
        running = true;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        int backlog = Math.max(128, config.effectiveServerParallelism() * 4);
        serverSocket.bind(new InetSocketAddress(config.getHost(), config.getPort()), backlog);
        int port = serverSocket.getLocalPort();
        ThreadUtils.startVirtualThread("filepush-accept", this::acceptLoop);
        log.info("FilePushServer 监听 {}:{} (targetDir={}, 连接并发={}, 分片大小={}KB, 写盘并发={})",
                config.getHost(), port, target.toAbsolutePath(),
                config.effectiveServerParallelism(), config.effectiveChunkSize() / 1024,
                config.effectiveWriteParallelism());
        return port;
    }

    /**
     * 停止服务端并释放资源。
     */
    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 安静关闭
        }
        ThreadUtils.shutdownNow(writeExecutor);
        log.info("FilePushServer 已停止：{}", stats());
    }

    /**
     * 获取统计信息（文件数、字节数、错误数）。
     *
     * @return 统计快照字符串
     */
    public String stats() {
        return String.format("files=%d bytes=%d errors=%d",
                filesReceived.get(), bytesReceived.get(), errors.get());
    }

    /** 接受连接循环（虚拟线程 accept，虚拟线程池处理连接） */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().name("filepush-conn-" + socket.getPort()).start(() -> {
                    // 许可在连接线程内阻塞获取：饱和时排队而非拒绝。
                    // 客户端并发数常与服务端相等，而服务端释放许可晚于客户端发起下一条连接，
                    // 若在 accept 处 tryAcquire 拒绝，必然出现瞬时溢出并丢失文件。
                    try {
                        connectionLimiter.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ThreadUtils.closeQuietly(socket);
                        return;
                    }
                    try {
                        handleConnection(socket);
                    } catch (Exception e) {
                        if (running) {
                            log.warn("FilePush 连接处理异常({}): {}",
                                    socket.getRemoteSocketAddress(), e.toString());
                        }
                    } finally {
                        connectionLimiter.release();
                        ThreadUtils.closeQuietly(socket);
                    }
                });
            } catch (IOException e) {
                if (running) {
                    // 监听套接字关闭会持续抛异常，短暂休眠避免空转
                    ThreadUtils.sleep(100);
                }
            }
        }
    }

    /**
     * 处理单个连接的完整消息循环。
     *
     * @param socket 已接受的 TCP 连接
     * @throws Exception 处理失败
     */
    private void handleConnection(Socket socket) throws Exception {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(config.getReadTimeoutMs());
        InputStream rawIn = socket.getInputStream();
        OutputStream rawOut = socket.getOutputStream();
        DataInputStream in = new DataInputStream(rawIn);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(rawOut));

        // 握手：MAGIC + VERSION + fileCount
        int magic = in.readInt();
        if (magic != MAGIC) {
            log.warn("非法魔数 0x{}，拒绝连接 {}", Integer.toHexString(magic),
                    socket.getRemoteSocketAddress());
            return;
        }
        int version = in.readUnsignedByte();
        if (version != PROTOCOL_VERSION) {
            throw new IOException("不支持的协议版本: " + version);
        }
        int fileCount = in.readInt();
        out.writeByte(FilePushConfig.MSG_ACK);
        out.flush();
        log.debug("连接 {} 握手成功，fileCount={}", socket.getRemoteSocketAddress(), fileCount);

        // fileCount 语义：0=控制连接（MANIFEST/CLEANUP），1=单文件连接，>1=多文件连接
        if (fileCount == 0) {
            handleControlConnection(socket, in, out);
            return;
        }

        for (int file = 0; file < fileCount; file++) {
            if (!running) {
                return;
            }
            handleFile(in, out, fileCount > 1 ? file + 1 : -1);
        }

        // 单文件连接：客户端发完 END + 收到 ACK 后直接关闭连接，无需等待 DONE/CLEANUP。
        // 多文件连接才继续等待结束指令（DONE / CLEANUP）。
        if (fileCount == 1) {
            log.debug("连接 {} 完成（单文件）: {}", socket.getRemoteSocketAddress(), stats());
            return;
        }
        int msg = in.readUnsignedByte();
        switch (msg) {
            case FilePushConfig.MSG_DONE -> {
                out.writeByte(FilePushConfig.MSG_ACK);
                out.flush();
            }
            case FilePushConfig.MSG_CLEANUP -> {
                Set<String> expected = readPathSet(in);
                out.writeByte(FilePushConfig.MSG_ACK);
                out.flush();
                log.info("清理完成，删除旧文件 {} 个", cleanupStaleFiles(expected));
            }
            default -> log.warn("连接 {} 末尾消息类型未知: 0x{}", socket.getRemoteSocketAddress(),
                    Integer.toHexString(msg));
        }
        log.info("连接 {} 完成: {}", socket.getRemoteSocketAddress(), stats());
    }

    /**
     * 处理控制连接（握手 fileCount=0）：清单协商与旧文件清理。
     *
     * @param socket 连接（仅用于日志）
     * @param in     连接输入流
     * @param out    连接输出流
     * @throws IOException 协议或 IO 失败
     */
    private void handleControlConnection(Socket socket, DataInputStream in, DataOutputStream out)
            throws IOException {
        int msg = in.readUnsignedByte();
        switch (msg) {
            case FilePushConfig.MSG_MANIFEST -> sendManifest(out);
            case FilePushConfig.MSG_CLEANUP -> {
                Set<String> expected = readPathSet(in);
                try {
                    log.info("清理完成，删除旧文件 {} 个", cleanupStaleFiles(expected));
                    out.writeByte(FilePushConfig.MSG_ACK);
                    out.flush();
                } catch (IOException e) {
                    sendError(socket, "cleanup failed: " + e.getMessage());
                }
            }
            default -> {
                log.warn("控制连接 {} 消息类型未知: 0x{}", socket.getRemoteSocketAddress(),
                        Integer.toHexString(msg));
                sendError(socket, "unknown control message 0x" + Integer.toHexString(msg));
            }
        }
    }

    /**
     * 扫描目标目录并回送文件清单，供客户端做增量比对。
     *
     * <p>帧格式：{@code [byte MSG_MANIFEST_RESP][int count]}，其后每条为
     * {@code [int pathLen][bytes path(utf-8)][long size][long mtimeMillis]}。</p>
     *
     * @param out 连接输出流
     * @throws IOException 扫描或写入失败
     */
    private void sendManifest(DataOutputStream out) throws IOException {
        Path targetRoot = config.getTargetDir().toAbsolutePath().normalize();
        List<Path> files;
        if (Files.isDirectory(targetRoot)) {
            try (var walk = Files.walk(targetRoot)) {
                files = walk.filter(Files::isRegularFile).toList();
            }
        } else {
            files = List.of();
        }
        out.writeByte(FilePushConfig.MSG_MANIFEST_RESP);
        out.writeInt(files.size());
        for (Path file : files) {
            String rel = targetRoot.relativize(file).toString().replace('\\', '/');
            byte[] relBytes = rel.getBytes(StandardCharsets.UTF_8);
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            out.writeInt(relBytes.length);
            out.write(relBytes);
            out.writeLong(attrs.size());
            out.writeLong(attrs.lastModifiedTime().toMillis());
        }
        out.flush();
        log.debug("已回送目标目录清单，{} 个文件", files.size());
    }

    /**
     * 读取客户端上报的相对路径清单。
     *
     * @param in 连接输入流
     * @return 相对路径集合
     * @throws IOException 读取失败或条数/长度非法
     */
    private Set<String> readPathSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_MANIFEST_ENTRIES) {
            throw new IOException("非法清单条数: " + count);
        }
        Set<String> paths = new HashSet<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) {
            int len = in.readInt();
            if (len <= 0 || len > 16384) {
                throw new IOException("非法路径长度: " + len);
            }
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            paths.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return paths;
    }

    /**
     * 处理一个文件的接收：读 BEGIN 元数据 → 循环读 CHUNK 分片 → 收 END → 落盘 → 回 ACK。
     *
     * @param in 连接输入流
     * @param out 连接输出流
     * @param debugSeq 文件序号（单文件连接传 -1）
     * @throws Exception 处理失败
     */
    private void handleFile(DataInputStream in, DataOutputStream out, int debugSeq)
            throws Exception {
        int type = in.readUnsignedByte();
        if (type != FilePushConfig.MSG_BEGIN) {
            throw new IOException("期望 BEGIN 但收到 0x" + Integer.toHexString(type));
        }
        int metaLen = in.readInt();
        if (metaLen <= 0 || metaLen > 16384) {
            throw new IOException("非法元数据长度: " + metaLen);
        }
        byte[] metaBytes = new byte[metaLen];
        in.readFully(metaBytes);
        // meta = relativePath \0 fileSize \0 lastChunk \0 mtimeMillis \0
        String meta = new String(metaBytes, StandardCharsets.UTF_8);
        String[] parts = meta.split("\u0000", -1);
        if (parts.length < 3 || parts[0].isEmpty()) {
            throw new IOException("元数据格式非法: " + metaLen + " bytes");
        }
        String relativePath = parts[0];
        long fileSize;
        int lastChunk;
        long mtimeMillis;
        try {
            fileSize = Long.parseLong(parts[1]);
            lastChunk = Integer.parseInt(parts[2]);
            mtimeMillis = (parts.length > 3 && !parts[3].isEmpty()) ? Long.parseLong(parts[3]) : 0L;
        } catch (NumberFormatException nfe) {
            throw new IOException("元数据字段解析失败: " + nfe.getMessage());
        }
        if (fileSize < 0 || lastChunk < 0) {
            throw new IOException("非法文件参数: fileSize=" + fileSize + ", lastChunk=" + lastChunk);
        }

        Path target = resolveSafePath(relativePath);
        Files.createDirectories(target.getParent());
        Path tempPath = target.resolveSibling(
                target.getFileName() + ".push-" + System.nanoTime());
        Files.createFile(tempPath);

        // 分片大小 = 除最后一片外的固定值（客户端按 chunkSize 切分）
        int chunkSize = config.effectiveChunkSize();
        int lastSize;
        if (fileSize == 0) {
            // 空文件：无分片数据
            lastSize = 0;
        } else {
            lastSize = (int) (fileSize - (long) lastChunk * chunkSize);
        }

        // 落盘：整个文件只开一个 FileChannel，各分片用带 position 的定位写。
        // 定位写不改变通道位置且各分片区间互不重叠，业务侧无需加锁；但 JDK 会在通道内部
        // positionLock 上串行化定位写，故写盘池的收益是与网络读重叠，而非写盘并行。
        final long size = fileSize;
        final int chunks = lastChunk + 1;
        final int fixedChunk = chunkSize;
        ExecutorService pool = writeExecutor;

        CountDownLatch chunksDone = new CountDownLatch(chunks);
        AtomicInteger writeFailed = new AtomicInteger();
        try (FileChannel channel = FileChannel.open(tempPath, StandardOpenOption.WRITE)) {
            try {
                for (int idx = 0; idx < chunks; idx++) {
                    final int chunkIndex = idx;
                    int expectedLen = (idx == lastChunk) ? lastSize : fixedChunk;
                    if (expectedLen == 0) {
                        // 空文件：客户端不发送数据分片，直接跳过
                        chunksDone.countDown();
                        continue;
                    }
                    int type2 = in.readUnsignedByte();
                    if (type2 != FilePushConfig.MSG_CHUNK) {
                        throw new IOException("期望 CHUNK 但收到 0x" + Integer.toHexString(type2));
                    }
                    int chunkIndexMsg = in.readInt();
                    int len = in.readInt();
                    if (chunkIndexMsg != chunkIndex || len != expectedLen) {
                        throw new IOException(String.format(
                                "分片不匹配: 期望 idx=%d len=%d, 实际 idx=%d len=%d",
                                chunkIndex, expectedLen, chunkIndexMsg, len));
                    }
                    byte[] data = new byte[len];
                    in.readFully(data);
                    pool.execute(() -> {
                        long offset = (long) chunkIndex * fixedChunk;
                        try {
                            writeFully(channel, ByteBuffer.wrap(data), offset);
                        } catch (IOException e) {
                            writeFailed.incrementAndGet();
                            errors.incrementAndGet();
                            log.warn("写盘失败 {} @{}: {}", relativePath, offset, e.getMessage());
                        } finally {
                            chunksDone.countDown();
                        }
                    });
                }
            } finally {
                // 协议异常时也必须等已派发的写盘任务落地，否则关闭通道会触发 ClosedChannelException
                chunksDone.await();
            }
        }
        if (writeFailed.get() > 0) {
            Files.deleteIfExists(tempPath);
            throw new IOException("文件 " + relativePath + " 有 " + writeFailed.get()
                    + " 个分片写入失败");
        }

        // END 消息
        int endType = in.readUnsignedByte();
        if (endType != FilePushConfig.MSG_END) {
            throw new IOException("期望 END 但收到 0x" + Integer.toHexString(endType));
        }

        // 原子改名到最终路径
        Files.move(tempPath, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        // 还原源文件 mtime：否则服务端写入时间永远与源文件不一致，增量比对会退化为全量重推
        if (mtimeMillis > 0) {
            Files.setLastModifiedTime(target, FileTime.fromMillis(mtimeMillis));
        }
        filesReceived.incrementAndGet();
        bytesReceived.addAndGet(size);

        out.writeByte(FilePushConfig.MSG_ACK);
        out.flush();
        log.debug("文件写入完成 {} ({} 字节, {} 分片) {}", relativePath, size, chunks,
                debugSeq > 0 ? "[" + debugSeq + "]" : "");
    }

    /**
     * 定位写，循环直到缓冲区全部写完。
     *
     * <p>{@link FileChannel#write(ByteBuffer, long)} 允许部分写入，必须循环补齐，
     * 否则磁盘繁忙时大分片会静默丢失尾部数据。</p>
     *
     * @param channel  目标文件通道
     * @param buffer   待写数据
     * @param position 起始偏移
     * @throws IOException 写入失败
     */
    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }

    /**
     * 解析相对路径到目标根目录（防路径穿越）。
     *
     * @param relativePath 客户端上报的相对路径（/ 分隔）
     * @return 目标根下的安全路径
     * @throws IOException 路径非法
     */
    private Path resolveSafePath(String relativePath) throws IOException {
        Path targetRoot = config.getTargetDir().toAbsolutePath().normalize();
        Path resolved = targetRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(targetRoot)) {
            throw new IOException("非法目标路径（路径穿越）: " + relativePath);
        }
        return resolved;
    }

    /**
     * 删除目标目录中不在客户端清单内的旧文件（仅 config.cleanup=true 时生效）。
     *
     * <p>清单由客户端携带本次扫描到的<b>全部</b>相对路径，而非服务端累积的"已推送"集合：
     * 后者在增量同步下只包含变更文件，会导致未变更文件被误删。</p>
     *
     * @param expected 客户端清单（相对路径，/ 分隔）
     * @return 实际删除的文件数
     * @throws IOException 遍历或删除失败
     */
    private int cleanupStaleFiles(Set<String> expected) throws IOException {
        if (!config.isCleanup()) {
            log.debug("cleanup 未开启，跳过清理");
            return 0;
        }
        Path targetRoot = config.getTargetDir().toAbsolutePath().normalize();
        if (!Files.isDirectory(targetRoot)) {
            return 0;
        }
        int removed = 0;
        try (var walk = Files.walk(targetRoot)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String rel = targetRoot.relativize(file).toString().replace('\\', '/');
                if (!expected.contains(rel) && Files.deleteIfExists(file)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * 发送错误帧并关闭连接。
     *
     * @param socket 目标连接
     * @param message 错误消息（UTF-8）
     * @throws IOException 写入失败
     */
    static void sendError(Socket socket, String message) throws IOException {
        DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream()));
        out.writeByte(FilePushConfig.MSG_ERROR);
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        out.writeInt(msgBytes.length);
        out.write(msgBytes);
        out.flush();
    }

    /**
     * 统计信息监听器：供外部拉取运行期计数。
     *
     * @return 统计快照
     */
    public Map<String, Long> snapshotStats() {
        return Map.of("files", filesReceived.get(), "bytes", bytesReceived.get(),
                "errors", errors.get());
    }
}
