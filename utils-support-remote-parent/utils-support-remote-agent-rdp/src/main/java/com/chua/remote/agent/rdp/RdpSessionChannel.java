package com.chua.remote.agent.rdp;

import com.chua.remote.agent.DesktopInputInjector;
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
 * RDP 桌面会话代理（原生 RDP 供给 + 降级自研采集推流 + 键鼠注入）。
 *
 * <p>会话画面通过被控机自研采集（{@link ScreenCapture} → {@link NativeEncoder} JPEG/H264）
 * 实时推送到网关（与 VNC 桌面通道同构——原生 RDP 码流直传待 RDP 解码转码，属未来项）；
 * 键鼠事件经 {@link DesktopInputInjector}（java.awt.Robot）注入被控机桌面：</p>
 * <ul>
 *   <li>画面：按协商帧率采集即编码即推（frame 帧——不攒批、不缓冲延迟）</li>
 *   <li>输入：键鼠事件即收即注入（Robot 实时注入）</li>
 *   <li>WebRTC：webrtc-offer 动作受理信令——当前 native 栈（webrtc-jni）不可得，
 *       回 {@code webrtc-unsupported} 由控制端降级 WS 帧流；native 引入后在此接入 PeerConnection</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class RdpSessionChannel {

    /** 元数据键：RDP action */
    private static final String META_ACTION = "rdpAction";

    /** 元数据键：会话 id */
    private static final String META_SESSION_ID = "rdpSessionId";

    /** RDP action：启动会话 */
    private static final String ACTION_START = "start";

    /** RDP action：键鼠输入 */
    private static final String ACTION_INPUT = "input";

    /** RDP action：停止会话 */
    private static final String ACTION_STOP = "stop";

    /** RDP action：WebRTC 信令——SDP offer（控制端 → agent） */
    private static final String ACTION_WEBRTC_OFFER = "webrtc-offer";

    /** RDP action：WebRTC 信令——SDP answer（agent → 控制端，native 栈可用时） */
    private static final String ACTION_WEBRTC_ANSWER = "webrtc-answer";

    /** RDP action：WebRTC 信令——ICE candidate 双向 */
    private static final String ACTION_WEBRTC_ICE = "webrtc-ice";

    /** 默认帧率上限（fps） */
    private static final int DEFAULT_FPS = 15;

    private final RemoteClient client;
    private final AgentInfo agentInfo;
    private final String agentId;
    private final ScreenCapture screenCapture;
    private final NativeEncoder nativeEncoder;
    private final DesktopInputInjector inputInjector;
    private final RdpServiceManager serviceManager;
    private final ExecutorService captureExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CaptureLoop> sessions = new ConcurrentHashMap<>();

    public RdpSessionChannel(RemoteClient client, AgentInfo agentInfo, RdpServiceManager serviceManager) {
        this.client = client;
        this.agentInfo = agentInfo;
        this.agentId = agentInfo.getId();
        this.serviceManager = serviceManager;
        this.screenCapture = new ScreenCapture(agentInfo);
        this.nativeEncoder = new NativeEncoder(agentInfo.getEncodingCapability());
        this.inputInjector = new DesktopInputInjector();
    }

    /**
     * RDP 帧分派（start / input / stop / webrtc-*）。
     *
     * @param frame RDP 帧（元数据携带 rdpAction/rdpSessionId）
     */
    public void handleRdpFrame(Frame frame) {
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
            case ACTION_WEBRTC_OFFER:
            case ACTION_WEBRTC_ICE:
                handleWebrtcSignal(sessionId, action, frame.getPayload());
                break;
            default:
                log.debug("未知 rdpAction: {}", action);
                break;
        }
    }

    /**
     * 启动 RDP 桌面会话（确保桌面供给可达 + 采集推流）。
     *
     * @param sessionId 会话 id
     */
    private void startSession(String sessionId) {
        if (!serviceManager.ensureRdpService()) {
            sendRdpFrame(sessionId, "error", "RDP 桌面供给不可用（原生 RDP 开启失败且无桌面会话）".getBytes());
            return;
        }
        sessions.computeIfAbsent(sessionId, id -> {
            CaptureLoop loop = new CaptureLoop(id);
            captureExecutor.submit(loop);
            log.info("RDP 会话已启动: sessionId={}, agentId={}, forward={}, degrade={}, desktop={}",
                    id, agentId, serviceManager.isForwardMode(), serviceManager.isDegradeMode(),
                    serviceManager.isDesktopAvailable());
            return loop;
        });
        sendRdpFrame(sessionId, "started", null);
    }

    /**
     * 停止 RDP 桌面会话。
     *
     * @param sessionId 会话 id
     */
    private void stopSession(String sessionId) {
        CaptureLoop loop = sessions.remove(sessionId);
        if (loop != null) {
            loop.stop();
            sendRdpFrame(sessionId, "stopped", null);
            log.info("RDP 会话已停止: sessionId={}", sessionId);
        }
    }

    /**
     * WebRTC 信令受理。
     *
     * <p>当前 webrtc-jni native 栈不可得（Maven Central 无 natives、私库未接）——
     * 回 {@code webrtc-unsupported}，控制端自动降级 WS 帧流。
     * native 引入后在此创建 RTCPeerConnection：SDP offer → answer、ICE 双向中继，
     * 画面经 VideoTrack 推送（采集帧即编码帧）。</p>
     *
     * @param sessionId 会话 id
     * @param action    信令动作（webrtc-offer / webrtc-ice）
     * @param payload   SDP/ICE 载荷（JSON）
     */
    private void handleWebrtcSignal(String sessionId, String action, byte[] payload) {
        sendRdpFrame(sessionId, ACTION_WEBRTC_ANSWER.equals(action) ? action : "webrtc-unsupported",
                payload != null ? payload : new byte[0]);
        log.info("WebRTC 信令受理（native 栈未接入——控制端降级帧流）: sessionId={}, action={}", sessionId, action);
    }

    /**
     * 发送 RDP 帧到网关。
     *
     * @param sessionId 会话 id
     * @param action    动作（started/stopped/error/webrtc-*）
     * @param payload   载荷
     */
    private void sendRdpFrame(String sessionId, String action, byte[] payload) {
        Map<String, String> meta = Map.of(
                META_ACTION, action,
                META_SESSION_ID, sessionId);
        client.getTransport().send(
                FrameCodec.rdpFrame(agentId, payload != null ? payload : new byte[0], meta));
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
        log.info("RDP 会话管理已停止: agentId={}", agentId);
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
            log.info("RDP 采集循环启动: sessionId={}, intervalMs={}", sessionId, intervalMs);
            int pushed = 0;
            while (running.get()) {
                try {
                    NativeFrame frame = screenCapture.captureFrame();
                    if (frame == null || frame.pixels().length == 0) {
                        if (pushed++ < 3) {
                            log.warn("[RDP_CAPTURE_EMPTY] sessionId={} frameNull={}", sessionId, frame == null);
                        }
                        Thread.sleep(intervalMs);
                        continue;
                    }
                    byte[] encoded = nativeEncoder.encode(frame);
                    if (encoded.length == 0) {
                        if (pushed++ < 3) {
                            log.warn("[RDP_ENCODE_EMPTY] sessionId={} rawPixels={}", sessionId, frame.pixels().length);
                        }
                        Thread.sleep(intervalMs);
                        continue;
                    }
                    Map<String, String> meta = Map.of(
                            META_ACTION, "frame",
                            META_SESSION_ID, sessionId);
                    client.getTransport().send(FrameCodec.rdpFrame(agentId, encoded, meta));
                    if (pushed++ < 3) {
                        log.info("[RDP_FRAME_PUSH] sessionId={} raw={} encoded={}",
                                sessionId, frame.pixels().length, encoded.length);
                    }
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("RDP 采集失败: sessionId={}", sessionId, e);
                }
            }
            log.info("RDP 采集循环停止: sessionId={}", sessionId);
        }

        /**
         * 停止采集循环。
         */
        void stop() {
            running.set(false);
        }
    }
}
