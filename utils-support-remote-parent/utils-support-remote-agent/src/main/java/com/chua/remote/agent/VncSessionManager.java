package com.chua.remote.agent;

import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VNC 桌面会话管理器。
 *
 * <p>管理 VNC 桌面会话生命周期：{@code start} 启动桌面采集循环并推送编码帧到网关，
 * {@code input} 将控制端键鼠事件注入被控机桌面，{@code stop} 停止采集并回收资源。
 * 采集复用 {@link ScreenCapture} + {@link NativeEncoder}（原生 GDI/X11/CG 采集 + JPEG/H264 编码）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VncSessionManager {

    /** 帧消息 action 值：启动会话 */
    private static final String ACTION_START = "start";

    /** 帧消息 action 值：键鼠输入 */
    private static final String ACTION_INPUT = "input";

    /** 帧消息 action 值：停止会话 */
    private static final String ACTION_STOP = "stop";

    /** 元数据键：VNC action */
    private static final String META_ACTION = "vncAction";

    /** 元数据键：会话 id */
    private static final String META_SESSION_ID = "vncSessionId";

    /** 客户端连接 */
    private final RemoteClient client;

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** 屏幕采集器 */
    private final ScreenCapture screenCapture;

    /** 编码器 */
    private final NativeEncoder nativeEncoder;

    /** 键鼠注入器 */
    private final VncInputInjector inputInjector;

    /** 会话集合（sessionId → 采集循环） */
    private final Map<String, CaptureLoop> sessions = new ConcurrentHashMap<>();

    /** 是否已初始化资源 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 采集线程池 */
    private final ExecutorService captureExecutor;

    /**
     * 创建 VNC 会话管理器。
     *
     * @param client    与网关的连接
     * @param agentInfo 被控端信息
     */
    public VncSessionManager(RemoteClient client, AgentInfo agentInfo) {
        this.client = client;
        this.agentInfo = agentInfo;
        this.screenCapture = new ScreenCapture(agentInfo);
        this.nativeEncoder = new NativeEncoder(agentInfo.getEncodingCapability());
        this.inputInjector = new VncInputInjector();
        this.captureExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 处理 VNC 帧消息。
     *
     * @param frame VNC 帧（元数据携带 vncAction/vncSessionId）
     */
    public void handleVncFrame(Frame frame) {
        Map<String, String> meta = frame.getMetadata();
        if (meta == null) {
            return;
        }
        String action = meta.get(META_ACTION);
        String sessionId = meta.get(META_SESSION_ID);
        if (action == null || sessionId == null) {
            log.warn("VNC 帧缺 action/sessionId: meta={}", meta);
            return;
        }
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
                log.warn("未知 VNC action: {}", action);
                break;
        }
    }

    /**
     * 启动 VNC 桌面会话（开始采集推流）。
     *
     * @param sessionId 会话 id
     */
    private void startSession(String sessionId) {
        ensureInitialized();
        CaptureLoop loop = new CaptureLoop(sessionId);
        sessions.put(sessionId, loop);
        captureExecutor.submit(loop);
        sendVncFrame(sessionId, "started", new byte[0]);
        log.info("VNC 会话已启动: sessionId={}, agentId={}", sessionId, agentInfo.getId());
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
            sendVncFrame(sessionId, "stopped", new byte[0]);
            log.info("VNC 会话已停止: sessionId={}, agentId={}", sessionId, agentInfo.getId());
        }
    }

    /**
     * 初始化共享资源（幂等）。
     */
    private void ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            log.info("VNC 资源已初始化: agentId={}, platform={}",
                    agentInfo.getId(), agentInfo.getPlatform());
        }
    }

    /**
     * 发送 VNC 帧到网关。
     *
     * @param sessionId 会话 id
     * @param action    动作（started/stopped）
     * @param payload   载荷
     */
    private void sendVncFrame(String sessionId, String action, byte[] payload) {
        Map<String, String> meta = Map.of(
                META_ACTION, action,
                META_SESSION_ID, sessionId);
        client.getTransport().send(FrameCodec.vncFrame(agentInfo.getId(), payload, meta));
    }

    /**
     * 停止所有会话并释放资源。
     */
    public void stopAll() {
        sessions.forEach((sessionId, loop) -> loop.stop());
        sessions.clear();
        screenCapture.close();
        nativeEncoder.close();
        inputInjector.close();
        captureExecutor.close();
        log.info("VNC 会话管理已停止: agentId={}", agentInfo.getId());
    }

    /**
     * 桌面采集循环。
     *
     * <p>按协商帧率周期性采集屏幕 → 编码 → 推送 DATA 帧到网关。</p>
     */
    private class CaptureLoop implements Runnable {

        /** 会话 id */
        private final String sessionId;

        /** 运行状态 */
        private final AtomicBoolean running = new AtomicBoolean(true);

        /** 帧间隔（毫秒） */
        private final long intervalMs;

        CaptureLoop(String sessionId) {
            this.sessionId = sessionId;
            int fps = agentInfo.getEncodingCapability() != null
                    ? agentInfo.getEncodingCapability().getMaxFps() : 30;
            this.intervalMs = fps > 0 ? 1000L / fps : 33L;
        }

        @Override
        public void run() {
            log.info("VNC 采集循环启动: sessionId={}, intervalMs={}", sessionId, intervalMs);
            int sent = 0;
            while (running.get()) {
                try {
                    NativeFrame frame = screenCapture.captureFrame();
                    if (frame != null && frame.pixels().length > 0) {
                        byte[] encoded = nativeEncoder.encode(frame);
                        if (encoded.length > 0) {
                            Map<String, String> meta = Map.of(
                                    META_ACTION, "frame",
                                    META_SESSION_ID, sessionId);
                            client.getTransport().send(
                                    FrameCodec.vncFrame(agentInfo.getId(), encoded, meta));
                            if (sent++ < 3) {
                                log.info("VNC 画面帧已推送: sessionId={}, raw={}, encoded={}",
                                        sessionId, frame.pixels().length, encoded.length);
                            }
                        } else {
                            log.warn("VNC 编码为空: sessionId={}, rawPixels={}", sessionId, frame.pixels().length);
                        }
                    } else {
                        log.warn("VNC 采集为空: sessionId={}, frameNull={}", sessionId, frame == null);
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
