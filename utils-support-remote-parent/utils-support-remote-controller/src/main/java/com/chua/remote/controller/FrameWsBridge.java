package com.chua.remote.controller;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.sync.impl.WebSocketSyncServer;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 帧 WS 桥（远控画面推流）——复用框架 {@link WebSocketSyncServer}。
 *
 * <p>前端（vue-support-remote-starter 远控页）经 {@code ws://host:port/ws} 连接，
 * 桥按帧广播（JPEG 字节——前端嗅探 FFD8 后 canvas 直绘）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FrameWsBridge {

    private final WebSocketSyncServer wsServer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "frame-ws-bridge");
        t.setDaemon(true);
        return t;
    });

    public FrameWsBridge(int port) {
        ServerSetting setting = ServerSetting.builder().port(port).build();
        this.wsServer = new WebSocketSyncServer(setting);
    }

    public void start() {
        wsServer.start();
        log.info("帧WS桥已启动 port:{}", port());
    }

    /** 桥端口 */
    public int port() {
        return wsServer.getPort();
    }

    /**
     * 广播一帧（JPEG 字节）——前端嗅探 FFD8 后 canvas 直绘。
     *
     * @param jpeg JPEG 帧字节
     */
    public void broadcastFrame(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return;
        }
        wsServer.publish("frame", jpeg);
    }

    /**
     * 周期广播（测试模式）——无真实帧源时以测试 JPEG 占位推流。
     *
     * @param jpeg           测试帧
     * @param intervalMillis 广播间隔（毫秒）
     */
    public void broadcastTestLoop(byte[] jpeg, long intervalMillis) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                broadcastFrame(jpeg);
            } catch (Exception e) {
                log.warn("测试帧广播失败", e);
            }
        }, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
        wsServer.stop();
    }

    /** 生成 64x48 红色测试 JPEG（仅测试模式用——真实链路由采集→编码产出帧） */
    private static byte[] createTestJpeg() {
        try {
            BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(new java.awt.Color(0xE6, 0x3F, 0x3F));
            g.fillRect(0, 0, 64, 48);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpeg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("测试帧生成失败", e);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        FrameWsBridge bridge = new FrameWsBridge(port);
        bridge.start();
        byte[] testJpeg = createTestJpeg();
        log.info("测试帧 {} 字节，周期广播中（前端 ws://localhost:{}/ws 连接查看）", testJpeg.length, port);
        bridge.broadcastTestLoop(testJpeg, 1000);
        Thread.currentThread().join();
    }
}
