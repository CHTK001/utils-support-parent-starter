package com.chua.remote.controller;

import com.chua.common.support.image.ImageProcessors;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.RemoteServer;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * 远控链路性能基准。
 *
 * <p>覆盖四类指标：线格式编解码吞吐、真实 TCP 双向帧吞吐（分档帧大小）、
 * 控制端渲染热路径成本对比（仅解码 vs 旧实现的双重再编码）。
 * main() 入口，退出码恒为 0，结果以 [PERF] 行输出。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RemotePerfBenchmark {

    /** 基准服务监听起始端口 */
    private static final int BASE_PORT = 28401;

    /**
     * 基准入口。
     *
     * @param args 未使用
     * @throws Exception 链路异常
     */
    public static void main(String[] args) throws Exception {
        int[][] dims = {{480, 360}, {1280, 720}, {1920, 1080}};
        for (int[] wh : dims) {
            byte[] jpeg = makeJpeg(wh[0], wh[1]);
            System.out.printf("[PERF] frame %dx%d -> jpeg %d KB%n", wh[0], wh[1], jpeg.length / 1024);
            benchWireCodec(jpeg);
            benchTcp(jpeg, wh[0] + "x" + wh[1]);
        }
        byte[] fullHd = makeJpeg(1920, 1080);
        benchRenderPath(fullHd);
        System.out.println("[PERF] BENCHMARK DONE");
    }

    /**
     * 生成真实 JPEG 测试帧。
     *
     * @param width  宽
     * @param height 高
     * @return JPEG 字节
     * @throws Exception 图像编码异常
     */
    static byte[] makeJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int x = 0; x < width; x += 8) {
            g.setColor(new Color((x * 7) % 256, (x * 13) % 256, (x * 29) % 256));
            g.fillRect(x, 0, 8, height);
        }
        g.setColor(Color.WHITE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        byte[] png = BufferedImageUtils.toBufferedImageArray(image, "png");
        return ImageProcessors.from(png).format("jpeg").toBytes();
    }

    /**
     * 线格式编解码吞吐（编码+解码计入同一轮）。
     *
     * @param jpeg 帧载荷
     */
    static void benchWireCodec(byte[] jpeg) {
        Frame frame = FrameCodec.dataFrame("perf-sess", jpeg);
        int rounds = jpeg.length > 128 * 1024 ? 2000 : 5000;
        // 预热
        for (int i = 0; i < 200; i++) {
            FrameCodec.decodeWire(FrameCodec.encodeWire(frame));
        }
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            FrameCodec.decodeWire(FrameCodec.encodeWire(frame));
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double mbps = rounds * jpeg.length / seconds / 1024 / 1024;
        System.out.printf("[PERF] wire enc+dec %.1f KB payload: %,d ops/s, %.0f MB/s%n",
                jpeg.length / 1024.0, (int) (rounds / seconds), mbps);
    }

    /**
     * 真实 TCP 双向吞吐：下行（服务端定向发送→客户端）、上行（客户端→服务端）。
     *
     * @param jpeg 帧载荷
     * @param tag  档位标签
     * @throws Exception 链路异常
     */
    static void benchTcp(byte[] jpeg, String tag) throws Exception {
        int port = BASE_PORT + (jpeg.length % 7) + 1;
        ServerSetting setting = ServerSetting.builder().auto(false).host("127.0.0.1").port(port).build();
        RemoteServer server = new RemoteServer(setting);
        AtomicInteger upCount = new AtomicInteger();
        server.getTransport().on(MessageType.DATA, f -> upCount.incrementAndGet());
        server.start();
        try {
            RemoteClient client = new RemoteClient("perf-client", "tcp://127.0.0.1:" + port);
            AtomicInteger downCount = new AtomicInteger();
            client.getTransport().on(MessageType.DATA, f -> downCount.incrementAndGet());
            client.connect();

            int frames = jpeg.length > 128 * 1024 ? 100 : 300;
            // 下行：服务端定向发送
            downCount.set(0);
            long start = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                server.getTransport().send("perf-client", FrameCodec.dataFrame("perf-sess", jpeg));
            }
            boolean downOk = await(() -> downCount.get() >= frames, 20_000);
            double downSec = (System.nanoTime() - start) / 1e9;
            report("tcp down (server->client) " + tag, downOk, downCount.get(), frames, jpeg.length, downSec);

            // 上行：客户端发送（被控端推流方向）
            upCount.set(0);
            start = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                client.getTransport().send(FrameCodec.dataFrame("perf-sess", jpeg));
            }
            boolean upOk = await(() -> upCount.get() >= frames, 20_000);
            double upSec = (System.nanoTime() - start) / 1e9;
            report("tcp up (client->server) " + tag, upOk, upCount.get(), frames, jpeg.length, upSec);

            client.disconnect();
        } finally {
            server.stop();
        }
    }

    /**
     * 输出吞吐结果行。
     *
     * @param name     场景名
     * @param ok       是否全部送达
     * @param received 实际送达帧数
     * @param frames   发送帧数
     * @param size     单帧字节
     * @param seconds  耗时
     */
    static void report(String name, boolean ok, int received, int frames, int size, double seconds) {
        if (ok) {
            System.out.printf("[PERF] %s: %d fps, %.0f MB/s (%d/%d frames)%n",
                    name, (int) (received / seconds), received * (double) size / seconds / 1024 / 1024,
                    received, frames);
        } else {
            System.out.printf("[PERF] %s: TIMEOUT/DEAD — %d/%d frames in budget%n", name, received, frames);
        }
    }

    /**
     * 控制端渲染热路径对比：仅解码（新）vs 解码+PNG再编码+JPEG再编码（旧实现）。
     *
     * @param jpeg 1080p 帧
     * @throws Exception 图像编解码异常
     */
    static void benchRenderPath(byte[] jpeg) throws Exception {
        int rounds = 30;
        // 预热
        BufferedImage warm = BufferedImageUtils.toBufferedImage(jpeg);
        warm.getWidth();
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            BufferedImage image = BufferedImageUtils.toBufferedImage(jpeg);
            image.getWidth();
        }
        double decodeMs = (System.nanoTime() - start) / 1e6 / rounds;

        start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            BufferedImage image = BufferedImageUtils.toBufferedImage(jpeg);
            byte[] png = BufferedImageUtils.toBufferedImageArray(image, "png");
            ImageProcessors.from(png).format("jpeg").toBytes();
        }
        double oldMs = (System.nanoTime() - start) / 1e6 / rounds;
        System.out.printf("[PERF] render 1080p: decode-only %.1f ms/frame (%.0f fps) | old double-encode %.1f ms/frame (%.0f fps) | waste %.1fx%n",
                decodeMs, 1000 / decodeMs, oldMs, 1000 / oldMs, oldMs / decodeMs);
    }

    /**
     * 轮询等待条件成立。
     *
     * @param cond      条件
     * @param timeoutMs 超时
     * @return 是否成立
     * @throws InterruptedException 中断
     */
    static boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }
}
