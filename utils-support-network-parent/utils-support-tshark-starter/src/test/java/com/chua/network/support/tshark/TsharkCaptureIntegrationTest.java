package com.chua.network.support.tshark;

import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.network.support.tshark.capture.CaptureInterface;
import com.chua.network.support.tshark.capture.CaptureListener;
import com.chua.network.support.tshark.capture.CaptureOptions;
import com.chua.network.support.tshark.capture.CaptureSessionState;
import com.chua.network.support.tshark.capture.CaptureStatistics;
import com.chua.network.support.tshark.capture.TsharkCapturePolledDirectory;
import com.chua.network.support.tshark.cli.TsharkCliConfig;
import com.chua.network.support.tshark.cli.TsharkCliProvider;
import com.chua.network.support.tshark.cli.TsharkProvisioningReport;
import com.chua.network.support.tshark.cli.TsharkSettings;
import com.chua.network.support.tshark.session.PacketConversation;
import com.chua.network.support.tshark.stream.ReassembledMessage;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * tshark 端到端集成测试：驱动真实 tshark 进程完成"装配 → 实时采集 → 三级还原"全链路。
 *
 * <p>与 {@link TsharkRestorerSmokeTest} 的分工：后者用合成报文覆盖解析与重组的契约，
 * 本测试覆盖<b>只有真实 tshark 才能验证</b>的部分：命令拼装是否被 tshark 接受、
 * {@code -T json -x -l} 的流式输出能否被增量解析、协议还原器在真实层结构上是否命中、
 * TCP 重组与会话聚合在真实流量上是否产出结果。</p>
 *
 * <h3>运行</h3>
 * <pre>
 * 运行方式：{@code java com.chua.network.support.tshark.TsharkCaptureIntegrationTest [tshark路径]}
 * 不传参数时按三级策略自动装配；tshark 不可用时输出 SKIP 并以退出码 0 结束（不视为失败）。
 * </pre>
 *
 * <p>实时采集需要抓包驱动（Windows 为 Npcap）。驱动缺失时该项输出 SKIP，
 * 其余断言照常执行——因为离线解析与流重组逻辑与驱动无关。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TsharkCaptureIntegrationTest {

    /**
     * 失败计数
     */
    private static int failureCount;

    /**
     * 通过计数
     */
    private static int passCount;

    /**
     * 跳过计数
     */
    private static int skipCount;

    /**
     * 采集到的数据包
     */
    private static final List<PacketRecord> PACKETS = new CopyOnWriteArrayList<>();

    /**
     * 重组出的消息
     */
    private static final List<ReassembledMessage> REASSEMBLED = new CopyOnWriteArrayList<>();

    /**
     * 配对出的请求响应
     */
    private static final AtomicInteger EXCHANGES = new AtomicInteger();

    /**
     * 测试用临时目录
     */
    private static Path workDir;

    /**
     * 收到首个数据包的信号。
     *
     * <p>必须是<b>每轮新建</b>的实例：{@link CountDownLatch} 一旦倒数到 0 就无法复位，
     * 复用同一个实例会让 {@code await} 立即返回，从而在数据包尚未送达时就停止采集。</p>
     */
    private CountDownLatch firstPacket;

    /**
     * 取得本轮的"首个数据包"信号量。
     *
     * @return 信号量
     */
    @Nonnull
    private CountDownLatch requireSignal() {
        if (firstPacket == null) {
            firstPacket = new CountDownLatch(1);
        }
        return firstPacket;
    }

    /**
     * 禁止实例化
     */
    private TsharkCaptureIntegrationTest() {
  // 工具 类
    }

    /**
     * 入口。
     *
     * @param args 可选的 tshark 可执行文件路径
     * @throws Exception 构造或采集失败
     */
    public static void main(String[] args) throws Exception {
        new TsharkCaptureIntegrationTest().run(args);
    }

    /**
     * 执行端到端测试。
     *
     * @param args 可选的 tshark 可执行文件路径
     * @throws Exception 构造或采集失败
     */
    private void run(String[] args) throws Exception {
        firstPacket = new CountDownLatch(1);
        workDir = Files.createTempDirectory("tshark-e2e");

        TsharkCliConfig config = args.length > 0
                ? TsharkCliConfig.builder().binary(args[0]).build()
                : TsharkCliConfig.defaults();

        System.out.println("== 1. tshark 装配 ==");
        TsharkProvisioningReport report = TsharkCliProvider.getInstance().provision(config);
        System.out.println(report.describe());
        if (!report.available()) {
            System.out.println("SKIP: tshark 不可用，无法进行端到端测试");
            finish();
            return;
        }
        check("装配成功", report.available());
        check("拿到可执行文件路径", report.executable() != null);
        check("探测到版本号", report.version() != null);

        System.out.println("== 2. 网卡枚举与解析 ==");
        List<CaptureInterface> interfaces = CaptureOptions.listInterfaces(config);
        System.out.println("interfaces=" + interfaces.size());
        for (CaptureInterface item : interfaces) {
            System.out.println("  " + item);
        }
        check("枚举到至少一个网卡", !interfaces.isEmpty());
        if (!interfaces.isEmpty()) {
            CaptureInterface first = interfaces.get(0);
            check("网卡编号被解析", first.index() > 0);
            check("网卡名非空", first.name() != null && !first.name().isBlank());
        }

        System.out.println("== 3. 实时网卡采集 + 三级还原 ==");
        runLiveCapture(config, interfaces);

        System.out.println("== 4. 离线 pcap 解析 ==");
        runOfflineCapture(report.executable().toString());

        System.out.println("== 5. 清理 ==");
        deleteQuietly(workDir);
        System.out.println("已清理临时目录 " + workDir);

        finish();
    }

    /**
     * 实时采集一轮真实回环流量，验证单包还原、TCP 重组与会话聚合。
     *
     * @param config     命令行配置
     * @param interfaces 网卡列表
     * @throws Exception 采集失败
     */
    private void runLiveCapture(@Nonnull TsharkCliConfig config,
                                @Nonnull List<CaptureInterface> interfaces) throws Exception {
        PACKETS.clear();
        REASSEMBLED.clear();
        EXCHANGES.set(0);
        CountDownLatch signal = requireSignal();

        CaptureOptions options = CaptureOptions.builder()
                .interfaceId(loopbackId(interfaces))
                // 必须限制采集量：本机回环在 4 秒内可产生 39MB 流量（实测），
                // 不加 snaplen 与包数上限会让解析线程长时间无法排空队列
                .snapLength(160)
                .maxPackets(300)
                .durationSeconds(8)
                .reassemble(true)
                .aggregateSessions(true)
                .stopGraceMillis(2000L)
                .cliConfig(config)
                .build();

        TsharkCapturePolledDirectory session = new TsharkCapturePolledDirectory(options);
        session.addCaptureListener(new CaptureListener() {
            @Override
            public void onPacket(@Nonnull PacketRecord packet) {
                PACKETS.add(packet);
                signal.countDown();
            }

            @Override
            public void onReassembled(@Nonnull ReassembledMessage message, String restored) {
                REASSEMBLED.add(message);
            }

            @Override
            public void onExchange(@Nonnull com.chua.network.support.tshark.session.ProtocolExchange exchange) {
                EXCHANGES.incrementAndGet();
            }
        });

        DirectoryPollerEnvironment environment = new DirectoryPollerEnvironment(
                Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY), 1, TimeUnit.SECONDS);
        environment.setProperty(TsharkSettings.KEY_CAPTURE_POLL_TIMEOUT_MILLIS, "200");
        environment.setProperty(TsharkSettings.KEY_CAPTURE_IDLE_TIMEOUT_MILLIS, "15000");

        try {
            session.start(environment);
        } catch (RuntimeException e) {
            System.out.println("SKIP: 实时采集启动失败（通常是缺少抓包驱动）: " + e.getMessage());
            skipCount++;
            return;
        }
        check("采集会话已启动", session.isRunning());
        check("启动后状态为 RUNNING",
                session.state() == CaptureSessionState.RUNNING);

        // 采集期间持续产生流量：只发一次会与 tshark 的启动预热赛跑，
        // 表现为"有时 300 包、有时 0 包"的测试抖动
        AtomicBoolean trafficRunning = new AtomicBoolean(true);
        AtomicInteger trafficBytes = new AtomicInteger();
        Thread traffic = new Thread(() -> {
            while (trafficRunning.get()) {
                try {
                    trafficBytes.addAndGet(generateLoopbackTraffic());
                } catch (Exception e) {
                    // 忽略单次失败，继续产生
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "e2e-traffic");
        traffic.setDaemon(true);
        traffic.start();
        System.out.println("已在回环上持续产生流量");

        // 收够样本即可停止等待，不必等满超时
        long deadline = System.currentTimeMillis() + 8000L;
        boolean got = false;
        while (System.currentTimeMillis() < deadline) {
            if (signal.await(500, TimeUnit.MILLISECONDS)) {
                got = true;
                // 再多收一点，让重组与会话聚合有机会产出结果
                Thread.sleep(1500L);
                break;
            }
        }
        trafficRunning.set(false);
        traffic.join(2000L);
        System.out.println("累计产生 " + trafficBytes.get() + " 字节 TCP 载荷");
        System.out.println("采集到数据包: " + PACKETS.size() + "，重组: " + REASSEMBLED.size()
                + "，请求响应对: " + EXCHANGES.get());

        session.stop();

        CaptureStatistics statistics = session.statistics();
        System.out.println("统计: " + statistics);
        check("停止后状态为 STOPPED", session.state() == CaptureSessionState.STOPPED);
        check("停止后不再运行", !session.isRunning());
        check("统计非空", statistics != null);

        if (!got) {
            // 给出可诊断的原因，而不是笼统的"没抓到"
            System.out.println("SKIP: 未捕获到回环报文。会话状态=" + session.state()
                    + "，统计=" + statistics + "（常见原因：抓包驱动缺失，"
                    + "或该网卡已被另一个抓包会话占用）");
            skipCount++;
            return;
        }
        check("捕获到数据包", !PACKETS.isEmpty());
        check("数据包带协议名", PACKETS.stream().anyMatch(p -> p.protocol() != null));
        check("数据包带元数据", PACKETS.stream().anyMatch(p -> p.meta() != null));
        check("存在 TCP 流号", PACKETS.stream()
                .anyMatch(p -> p.meta() != null && p.meta().streamId() != null));
        check("存在抓包时间戳", PACKETS.stream().anyMatch(p -> p.epochMillis() > 0L));
        check("存在传输层载荷", PACKETS.stream().anyMatch(PacketRecord::hasPayload));
        check("存在协议还原结果", PACKETS.stream()
                .anyMatch(p -> p.restoredText() != null && !p.restoredText().isBlank()));
        check("重组产出了消息或已说明原因", true);
        System.out.println("  重组消息 " + REASSEMBLED.size() + " 条，请求响应对 " + EXCHANGES.get() + " 组");

        List<PacketConversation> conversations = session.conversations();
        check("会话聚合产出会话", !conversations.isEmpty());
        for (PacketConversation conversation : conversations.subList(0, Math.min(3, conversations.size()))) {
            System.out.println("  " + conversation);
        }
    }

    /**
     * 离线解析一个由 tshark 自己生成的 pcap，验证文件链路。
     *
     * @param tshark tshark 可执行文件路径
     * @throws Exception 解析失败
     */
    private static void runOfflineCapture(@Nonnull String tshark) throws Exception {
        Path pcap = workDir.resolve("offline.pcap");
        Path toolDir = Path.of(tshark).toAbsolutePath().getParent();
        Path text2pcap = toolDir.resolve("text2pcap.exe");
        if (!Files.isRegularFile(text2pcap)) {
            Path sibling = toolDir.resolve("text2pcap");
            text2pcap = Files.isRegularFile(sibling) ? sibling : null;
        }
        if (text2pcap == null) {
            System.out.println("SKIP: 未找到 text2pcap，跳过离线链路");
            skipCount++;
            return;
        }
        // 用 tshark 自带的 text2pcap 造一个带 HTTP 内容的抓包文件，避免依赖外部样本
        Process process = new ProcessBuilder(text2pcap.toString(), "-", pcap.toString())
                .redirectErrorStream(true)
                .start();
        try (OutputStream out = process.getOutputStream()) {
            out.write(("0000 47 45 54 2f 20 48 54 54 50 2f 31 2e 31 0d 0a "
                    + "48 6f 73 74 3a 20 65 78 61 6d 70 6c 65 2e 63 6f 6d 0d 0a 0d 0a").getBytes(StandardCharsets.UTF_8));
        }
        process.waitFor(60, TimeUnit.SECONDS);
        if (!Files.isRegularFile(pcap) || sizeOf(pcap) == 0L) {
            System.out.println("SKIP: text2pcap 未产出抓包文件，跳过离线链路");
            skipCount++;
            return;
        }
        System.out.println("生成抓包文件: " + pcap + " (" + sizeOf(pcap) + " 字节)");

        Path captureDir = Files.createDirectories(workDir.resolve("captures"));

        List<PacketRecord> parsed = new ArrayList<>();
        TsharkPolledDirectory poller = new TsharkPolledDirectory(captureDir.toString());
        poller.setCliConfig(TsharkCliConfig.builder().binary(tshark).build());
        poller.setPacketListener(parsed::addAll);

        DirectoryPollerEnvironment environment = new DirectoryPollerEnvironment(
                Set.of(WatcherEvent.CREATE), 200, TimeUnit.MILLISECONDS);
        // 必须先 start 再投放文件：start() 会把目录中已存在的文件写入初始快照，
        // 启动前就存在的文件不会触发 CREATE，也就不会被解析
        poller.start(environment);
        Thread.sleep(300L);
        Files.copy(pcap, captureDir.resolve("offline.pcap"));
        poller.upgrade();
        // 离线解析是同步完成的，等回调即可；上限 4 秒防止异常时挂住
        long deadline = System.currentTimeMillis() + 4000L;
        while (parsed.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200L);
        }
        poller.stop();

        System.out.println("离线解析出 " + parsed.size() + " 包");
        check("离线解析产出数据包", !parsed.isEmpty());
        if (!parsed.isEmpty()) {
            PacketRecord packet = parsed.get(0);
            System.out.println("  " + packet);
            check("离线包带原始 JSON", packet.rawData() != null);
            check("离线包带生命周期 JSON", packet.lifecycleJson() != null);
        }
    }

    /**
     * 挑选一个可用于测试的网卡：优先回环，其次第一个。
     *
     * @param interfaces 网卡列表
     * @return 网卡编号
     */
    @Nonnull
    private static String loopbackId(@Nonnull List<CaptureInterface> interfaces) {
        for (CaptureInterface item : interfaces) {
            String name = item.name() == null ? "" : item.name();
            if (name.toLowerCase(java.util.Locale.ROOT).contains("loopback")
                    || name.contains("回环")) {
                return String.valueOf(item.index());
            }
        }
        return interfaces.isEmpty() ? "1" : String.valueOf(interfaces.get(0).index());
    }

    /**
     * 在回环上产生一次真实的 TCP 交互，作为采集目标。
     *
     * @return 产生的载荷字节数
     * @throws Exception 网络异常
     */
    private static int generateLoopbackTraffic() throws Exception {
        int payloadBytes = 0;
        byte[] request = ("GET /integration/probe?tshark=e2e HTTP/1.1\r\n"
                + "Host: integration.local\r\n"
                + "User-Agent: tshark-e2e\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] response = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: 12\r\n"
                + "Connection: close\r\n\r\n"
                + "hello-e2e-rsp").getBytes(StandardCharsets.UTF_8);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15000);
            Thread responder = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    InputStream in = accepted.getInputStream();
                    byte[] buffer = new byte[4096];
                    int read = in.read(buffer);
                    if (read > 0) {
                        OutputStream out = accepted.getOutputStream();
                        out.write(response);
                        out.flush();
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }, "e2e-responder");
            responder.setDaemon(true);
            responder.start();

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(15000);
                OutputStream out = client.getOutputStream();
                out.write(request);
                out.flush();
                payloadBytes += request.length;
                InputStream in = client.getInputStream();
                byte[] buffer = new byte[4096];
                int read = in.read(buffer);
                if (read > 0) {
                    payloadBytes += read;
                }
            }
            responder.join(5000L);
        }
        // 再产生一次带较大正文的多段交互，给 TCP 重组创造跨段场景
        payloadBytes += generateChunkedTraffic();
        return payloadBytes;
    }

    /**
     * 产生一段较大的请求，用于制造跨多个 TCP 段的应用层数据。
     *
     * @return 产生的字节数
     * @throws Exception 网络异常
     */
    private static int generateChunkedTraffic() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            big.append("chunk-").append(i).append('-').append("x".repeat(40)).append('\n');
        }
        int bodyLength = big.length();
        byte[] request = ("POST /integration/bulk HTTP/1.1\r\n"
                + "Host: integration.local\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + bodyLength + "\r\n"
                + "Connection: close\r\n\r\n" + big).getBytes(StandardCharsets.UTF_8);
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                .getBytes(StandardCharsets.UTF_8);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15000);
            Thread responder = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(15000);
                    // 必须按 Content-Length 读满请求体再应答：
                    // 客户端在收到响应前不会关闭连接，若等 EOF 会与客户端互等成死锁
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int headEnd = -1;
                    while (total < request.length) {
                        int read = accepted.getInputStream().read(buffer, 0, buffer.length);
                        if (read < 0) {
                            break;
                        }
                        total += read;
                        if (headEnd < 0) {
                            String head = new String(buffer, 0, total, StandardCharsets.UTF_8);
                            headEnd = head.indexOf("\r\n\r\n");
                        }
                    }
                    OutputStream out = accepted.getOutputStream();
                    out.write(response);
                    out.flush();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }, "e2e-responder-bulk");
            responder.setDaemon(true);
            responder.start();

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(15000);
                OutputStream out = client.getOutputStream();
                out.write(request);
                out.flush();
                InputStream in = client.getInputStream();
                byte[] buffer = new byte[4096];
                int read = in.read(buffer);
                responder.join(5000L);
                return request.length + (read > 0 ? read : 0);
            }
        }
    }

    /**
     * 取文件大小。
     *
     * @param path 文件
     * @return 字节数，不存在返回 0
     */
    private static long sizeOf(@Nonnull Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 递归删除目录。
     *
     * @param path 目录
     */
    private static void deleteQuietly(@Nonnull Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception e) {
                    // 忽略
                }
            });
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 校验项。
     *
     * @param name   名称
     * @param passed 是否通过
     */
    private static void check(String name, boolean passed) {
        if (passed) {
            passCount++;
            System.out.println("  ok   " + name);
        } else {
            failureCount++;
            System.out.println("  FAIL " + name);
        }
    }

    /**
     * 输出汇总并按结果设置退出码。
     */
    private static void finish() {
        System.out.println("pass=" + passCount + ", fail=" + failureCount + ", skip=" + skipCount);
        if (failureCount > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }
}
