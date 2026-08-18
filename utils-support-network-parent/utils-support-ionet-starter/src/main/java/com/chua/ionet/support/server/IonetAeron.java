package com.chua.ionet.support.server;

import com.iohao.net.server.connection.DefaultUnavailableImageHandler;
import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

/**
 * ionet 嵌入式 Aeron 运行时 — 提供单例 Aeron 实例
 * <p>
 * 封装 Aeron MediaDriver 和 Client 的生命周期管理，
 * 参考 ionet 官方示例的 EmbeddedAeronRuntime 实现。
 * <p>
 * 使用方式：
 * <pre>
 * Aeron aeron = IonetAeron.getInstance();
 * new RunOne().setAeron(aeron)...startup();
 * </pre>
 *
 * @author CH
 */
@Slf4j
public class IonetAeron {

    /**
     * 客户端存活超时时间（纳秒）
     */
    private static final long DEBUG_CLIENT_TIMEOUT_NS = 600_000_000_000L;
    /**
     * 驱动超时时间（毫秒）
     */
    private static final long DEBUG_DRIVER_TIMEOUT_MS = 600_000L;
    /**
     * 发布解阻塞超时时间（纳秒）
     */
    private static final long DEBUG_UNBLOCK_TIMEOUT_NS = 900_000_000_000L;
    /**
     * 服务间超时时间（纳秒）
     */
    private static final long DEBUG_INTER_SERVICE_TIMEOUT_NS = DEBUG_CLIENT_TIMEOUT_NS + 1_000_000_000L;

    static {
        System.setProperty("aeron.driver.timeout", String.valueOf(DEBUG_DRIVER_TIMEOUT_MS));
        System.setProperty("aeron.keepAliveIntervalNs", String.valueOf(DEBUG_CLIENT_TIMEOUT_NS));
        System.setProperty("aeron.interServiceTimeoutNs", String.valueOf(DEBUG_INTER_SERVICE_TIMEOUT_NS));
    }

    private static class Holder {
        static final IonetAeron INSTANCE = new IonetAeron();
    }

    /**
     * 嵌入式媒体驱动实例
     */
    private final MediaDriver mediaDriver;
    /**
     * Aeron 客户端实例
     */
    private final Aeron aeron;

    private IonetAeron() {
        String aeronDirectoryName = "%s-%s".formatted(CommonContext.getAeronDirectoryName(), "ionet");

        // 启动嵌入式 MediaDriver
        log.info("[IonetAeron] Starting Aeron Embedded Media Driver...");
        var mediaDriverCtx = new MediaDriver.Context()
                .clientLivenessTimeoutNs(DEBUG_CLIENT_TIMEOUT_NS)
                .publicationUnblockTimeoutNs(DEBUG_UNBLOCK_TIMEOUT_NS)
                .aeronDirectoryName(aeronDirectoryName)
                .sharedIdleStrategy(new SleepingMillisIdleStrategy(1))
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.DEDICATED);

        this.mediaDriver = MediaDriver.launchEmbedded(mediaDriverCtx);
        log.info("[IonetAeron] Media Driver started at: {}", this.mediaDriver.aeronDirectoryName());

        // 短暂等待驱动完成内部初始化
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 连接 Aeron Client
        log.info("[IonetAeron] Connecting Aeron Client...");
        var aeronCtx = new Aeron.Context();
        aeronCtx.driverTimeoutMs(DEBUG_DRIVER_TIMEOUT_MS);
        aeronCtx.idleStrategy(new SleepingMillisIdleStrategy(1));
        aeronCtx.aeronDirectoryName(aeronDirectoryName);

        var handler = new DefaultUnavailableImageHandler();
        aeronCtx.unavailableImageHandler(handler);
        aeronCtx.availableImageHandler(handler);

        this.aeron = Aeron.connect(aeronCtx);
        log.info("[IonetAeron] Aeron Client connected.");

        Runtime.getRuntime().addShutdownHook(new Thread(this::destroy));
    }

    /**
     * 获取单例 Aeron 实例
     */
    public static Aeron getAeronInstance() {
        return Holder.INSTANCE.aeron;
    }

    private void destroy() {
        log.info("[IonetAeron] Shutting down Aeron...");
        try {
            if (aeron != null) aeron.close();
        } catch (Exception e) {
            log.error("[IonetAeron] Error closing Aeron client: {}", e.getMessage());
        }
        try {
            if (mediaDriver != null) mediaDriver.close();
        } catch (Exception e) {
            log.error("[IonetAeron] Error closing Media Driver: {}", e.getMessage());
        }
        log.info("[IonetAeron] Aeron components shut down.");
    }
}