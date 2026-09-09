package com.chua.remote.agent.vnc;

import com.chua.remote.agent.NativeFrame;
import com.chua.remote.agent.NativeEncoder;
import com.chua.remote.agent.ScreenCapture;
import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VNC 桌面会话代理（自研采集 + 键鼠注入）。
 *
 * <p>会话画面通过被控机自研采集（{@link ScreenCapture} → {@link NativeEncoder} JPEG/H264）
 * 实时推送到网关；键鼠事件经 {@link VncInputInjector}（java.awt.Robot）注入被控机桌面：</p>
 * <ul>
 *   <li>画面：按协商帧率采集即编码即推（frame 帧——不攒批、不缓冲延迟）</li>
 *   <li>输入：键鼠事件即收即注入（Robot 实时注入）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class VncSessionChannel {

    /** 元数据键：VNC action */
    private static final String META_ACTION = "vncAction";

    /** 元数据键：会话 id */
    private static final String META_SESSION_ID = "vncSessionId";

    /** VNC action：启动会话 */
    private static final String ACTION_START = "start";

    /** VNC action：键鼠输入 */
    private static final String ACTION_INPUT = "input";

    /** VNC action：停止会话 */
    private static final String ACTION_STOP = "stop";

    /** 默认帧率上限（fps） */
    private static final int DEFAULT_FPS = 15;

    private final RemoteClient client;
    private final AgentInfo agentInfo;
    private final String agentId;
    private final ScreenCapture screenCapture;
    private final NativeEncoder nativeEncoder;
    private final VncInputInjector inputInjector;
    private final VncServiceManager serviceManager;
    private final ExecutorService captureExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CaptureLoop> sessions = new ConcurrentHashMap<>();

    public VncSessionChannel(RemoteClient client, AgentInfo agentInfo, VncServiceManager serviceManager) {
        this.client = client;
        this.agentInfo = agentInfo;
        this.agentId = agentInfo.getId();
        this.serviceManager = serviceManager;
        this.screenCapture = new ScreenCapture(agentInfo);
        this.nativeEncoder = new NativeEncoder(agentInfo.getEncodingCapability());
        this.inputInjector = new VncInputInjector();
    }

    /**
     * VNC 帧分派（start / input / stop）。
     *
     * @param frame VNC 帧（元数据携带 vncAction/vncSessionId）
     */
    public void handleVncFrame(Frame frame) {
        Map<String, String> meta = frame.getMetadata();
        if (meta == null) {
            return;
        }
        String action = meta.getOrDefault(META_ACTION, "");
        String sessionId = meta.getOrDefault(META_SESSION_ID, "");
        switch (action) {
            case ACTION_START:
                startSession(sessionId);
                break;
            case ACTION_INPUT:
                inputInjector.inject(frame.getPayload());
                break;
            case ACTION_STOP:
                stopSession(sessionId);
                break;
            default:
                log.debug("未知 vncAction: {}", action);
                break;
        }
    }

    /**
     * 启动 VNC 桌面会话（确保 VNC 服务可达 + 采集推流）。
     *
     * @param sessionId 会话 id
     */
    private void startSession(String sessionId) {
        if (!serviceManager.ensureVncService()) {
            sendVncFrame(sessionId, "error", "VNC 服务不可用".getBytes());
            return;
        }
        sessions.computeIfAbsent(sessionId, id -> {
            CaptureLoop loop = new CaptureLoop(id);
            captureExecutor.submit(loop);
            log.info("VNC 会话已启动: sessionId={}, agentId={}, forward={}, desktop={}",
                    id, agentId, serviceManager.isForwardMode(), serviceManager.isDesktopAvailable());
            return loop;
        });
        sendVncFrame(sessionId, "started", null);
    }

    /**
     * 停止 VNC 桌面会话。
     *
     * @param sessionId 会话 id
     */
    private void stopSession(String sessionId) {
        CaptureLoop loop = sessions.remove(sessionId);
        if (loop != null) {
            loop.stop();
            sendVncFrame(sessionId, "stopped", null);
            log.info("VNC 会话已停止: sessionId={}", sessionId);
        }
    }

    /**
     * 发送 VNC 帧到网关。
     *
     * @param sessionId 会话 id
     * @param action    动作（started/stopped/error）
     * @param payload   载荷
     */
    private void sendVncFrame(String sessionId, String action, byte[] payload) {
        Map<String, String> meta = Map.of(
                META_ACTION, action,
                META_SESSION_ID, sessionId);
        client.getTransport().send(
                FrameCodec.vncFrame(agentId, payload != null ? payload : new byte[0], meta));
    }

    /**
     * 关闭所有会话并释放资源。
     */
    public void stopAll() {
        sessions.forEach((id, loop) -> loop.stop());
        sessions.clear();
        screenCapture.close();
        nativeEncoder.close();
        inputInjector.close();
        captureExecutor.close();
        log.info("VNC 会话管理已停止: agentId={}", agentId);
    }

    /**
     * 桌面采集循环（自研采集 → 编码 → 推送）。
     */
    private final class CaptureLoop implements Runnable {

        private final String sessionId;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final long intervalMs;

        private CaptureLoop(String sessionId) {
            this.sessionId = sessionId;
            int fps = DEFAULT_FPS;
            if (agentInfo.getEncodingCapability() != null && agentInfo.getEncodingCapability().getMaxFps() > 0) {
                fps = agentInfo.getEncodingCapability().getMaxFps();
            }
            this.intervalMs = 1000L / Math.max(1, fps);
        }

        @Override
        public void run() {
            log.info("VNC 采集循环启动: sessionId={}, intervalMs={}", sessionId, intervalMs);
            int pushed = 0;
            while (running.get()) {
                try {
                    NativeFrame frame = screenCapture.captureFrame();
                    if (frame == null || frame.pixels().length == 0) {
                        if (pushed++ < 3) {
                            log.warn("[VNC_CAPTURE_EMPTY] sessionId={} frameNull={}", sessionId, frame == null);
                        }
                        Thread.sleep(intervalMs);
                        continue;
                    }
                    byte[] encoded = nativeEncoder.encode(frame);
                    if (encoded.length == 0) {
                        if (pushed++ < 3) {
                            log.warn("[VNC_ENCODE_EMPTY] sessionId={} rawPixels={}", sessionId, frame.pixels().length);
                        }
                        Thread.sleep(intervalMs);
                        continue;
                    }
                    Map<String, String> meta = Map.of(
                            META_ACTION, "frame",
                            META_SESSION_ID, sessionId);
                    client.getTransport().send(FrameCodec.vncFrame(agentId, encoded, meta));
                    if (pushed++ < 3) {
                        log.info("[VNC_FRAME_PUSH] sessionId={} raw={} encoded={}",
                                sessionId, frame.pixels().length, encoded.length);
                    }
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("VNC 采集失败: sessionId={}", sessionId, e);
                }
            }
            log.info("VNC 采集循环停止: sessionId={}", sessionId);
        }

        /**
         * 停止采集循环。
         */
        void stop() {
            running.set(false);
        }
    }
}
