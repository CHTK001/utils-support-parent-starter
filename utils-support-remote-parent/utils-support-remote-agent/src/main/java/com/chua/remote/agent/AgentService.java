package com.chua.remote.agent;

import com.chua.common.support.image.ImageProcessors;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.spi.RemoteAgentSPI;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务模式被控端实现。
 *
 * <p>纯自研的完整系统：使用 {@link java.awt.Robot} 进行 Native 截图，
 * 通过 {@link ImageProcessors} 进行图像编码压缩，实时传输到网关。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("remote-agent")
public class AgentService implements RemoteAgentSPI {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** 编码能力 */
    private final CodecProfile capability;

    /** 截图采集器 */
    private final ScreenCapture screenCapture;

    /** 编码器 */
    private final NativeEncoder nativeEncoder;

    /** 运行状态 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 采集线程池 */
    private final ExecutorService captureExecutor;

    /** 截图回调（发送到网关） */
    private ScreenCallback screenCallback;

    public AgentService(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
        this.capability = agentInfo.getEncodingCapability();
        this.screenCapture = new ScreenCapture(agentInfo);
        this.nativeEncoder = new NativeEncoder(capability);
        this.captureExecutor = ThreadUtils.newVirtualThreadExecutor();
    }

    /**
     * 设置截图回调（由 AgentBootstrap 注入网关发送逻辑）。
     *
     * @param callback 回调
     */
    public void setScreenCallback(ScreenCallback callback) {
        this.screenCallback = callback;
    }

    @Override
    public void reportInfo(AgentInfo info) {
        log.info("上报被控端信息: id={}", info.getId());
    }

    @Override
    public void reportCapability(CodecProfile capability) {
        log.info("上报编码能力: agentId={}, encodings={}", agentInfo.getId(), capability.getEncodings());
    }

    @Override
    public byte[] captureScreen() {
        // 1. Native 截图
        BufferedImage screenshot = screenCapture.captureBufferedImage();
        if (screenshot == null) {
            return new byte[0];
        }
        // 2. 编码压缩（缩放 + JPEG 编码）
        return nativeEncoder.encode(screenshot);
    }

    @Override
    public byte[] captureInputEvent() {
        return new byte[0];
    }

    @Override
    public boolean isServiceMode() {
        return true;
    }

    /**
     * 启动服务模式。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // 启动截图采集循环
        captureExecutor.submit(this::captureLoop);
        log.info("服务模式启动: agentId={}", agentInfo.getId());
    }

    /** 截图采集循环 */
    private void captureLoop() {
        log.info("采集循环启动: agentId={}, fps={}", agentInfo.getId(), capability.getMaxFps());
        int fps = capability.getMaxFps() > 0 ? capability.getMaxFps() : 30;
        long intervalMs = 1000L / fps;

        while (running.get()) {
            try {
                byte[] encoded = captureScreen();
                if (encoded.length > 0 && screenCallback != null) {
                    screenCallback.onFrame(encoded);
                }
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("采集画面失败: agentId={}", agentInfo.getId(), e);
            }
        }
        log.info("采集循环停止: agentId={}", agentInfo.getId());
    }

    /**
     * 停止服务模式。
     */
    public void stop() {
        running.set(false);
        screenCapture.close();
        nativeEncoder.close();
        captureExecutor.close();
        log.info("服务模式已停止: agentId={}", agentInfo.getId());
    }

    /**
     * 屏幕回调接口。
     */
    @FunctionalInterface
    public interface ScreenCallback {
        void onFrame(byte[] encodedFrame);
    }
}
