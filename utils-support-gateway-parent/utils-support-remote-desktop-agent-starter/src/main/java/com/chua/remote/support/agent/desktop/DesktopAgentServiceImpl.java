package com.chua.remote.support.agent.desktop;

import com.chua.common.support.media.codec.ScreenCature;
import com.chua.common.support.media.codec.VideoEncoder;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.remote.support.agent.BaseRemoteAgent;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.Frame;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 默认桌面代理服务实现。
 *
 * <p>使用 SPI 加载 ScreenCature 采集器和 VideoEncoder 编码器，实现桌面远程控制。</p>
 * <p>采集和编码使用独立线程，通过 BlockingQueue 解耦。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DesktopAgentServiceImpl implements DesktopAgentService {

    /**
     * 采集帧队列容量
     */
    private static final int CAPTURE_QUEUE_CAPACITY = 4;

    /**
     * 远程代理实例
     */
    private final BaseRemoteAgent agent;

    /**
     * 屏幕采集器
     */
    private final ScreenCature capture;

    /**
     * 桌面会话集合
     */
    private final Map<String, DesktopSession> sessions = new ConcurrentHashMap<>();

    /**
     * 采集线程池
     */
    private ExecutorService captureExecutor;

    /**
     * 是否正在采集
     */
    private volatile boolean capturing;

    /**
     * 采集帧队列（生产者-消费者模式）
     */
    private BlockingQueue<Frame> frameQueue;

    /**
     * 输入模拟器
     */
    private Robot robot;

    /**
     * 构造器。
     *
     * @param agent 远程代理实例
     */
    public DesktopAgentServiceImpl(BaseRemoteAgent agent) {
        this.agent = agent;
        this.capture = createCapture();
    }

    /**
     * 通过 SPI 创建屏幕采集器。
     *
     * @return ScreenCature 实例
     */
    private ScreenCature createCapture() {
        try {
            ScreenCature cap = ServiceProvider.of(ScreenCature.class).getNewExtension("javacv");
            if (cap == null) {
                log.warn("[DesktopAgent] JavaCVScreenCapture not available, falling back to RobotScreenCapture");
                cap = ServiceProvider.of(ScreenCature.class).getNewExtension("robot");
            }
            if (cap != null) {
                log.info("[DesktopAgent] 采集器: {} (SPI)", cap.getClass().getSimpleName());
            }
            return cap;
        } catch (Exception e) {
            log.warn("[DesktopAgent] 创建采集器失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void handleConnect(String sessionId, Map<String, Object> target, Map<String, Object> auth) {
        if (capture == null) {
            agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"no capture\"}");
            return;
        }

        int clientW = 1920;
        int clientH = 1080;
        if (target != null) {
            if (target.get("width") instanceof Number) {
                clientW = ((Number) target.get("width")).intValue();
            }
            if (target.get("height") instanceof Number) {
                clientH = ((Number) target.get("height")).intValue();
            }
        }
        int encW = clientW + (clientW & 1);
        int encH = clientH + (clientH & 1);

        log.info("[DesktopAgent] 创建会话: sessionId={} {}x{} enc={}x{}", sessionId, clientW, clientH, encW, encH);

        VideoEncoder encoder = createEncoder(encW, encH, 30);
        if (encoder == null) {
            agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"no encoder\"}");
            return;
        }

        String captureName = capture.getClass().getSimpleName();
        DefaultDesktopSession session = new DefaultDesktopSession(sessionId, encW, encH, 30, encoder, captureName,
                (sid, frame) -> agent.sendBinaryFrame((byte) 0xDF, sid, frame.width(), frame.height(), frame.keyFrame(), frame.data()));
        session.setTargetSize(clientW, clientH);
        sessions.put(sessionId, session);
        session.start();
        startCapture();

        agent.sendToGateway("{\"type\":\"connected\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"DESKTOP connected\"}");
        log.info("[DesktopAgent] 已连接: sessionId={}", sessionId);
    }

    @Override
    public void handleDisconnect(String sessionId) {
        DesktopSession session = sessions.remove(sessionId);
        if (session != null) {
            session.stop();
            log.info("[DesktopAgent] 已断开: sessionId={}", sessionId);
        }
        if (sessions.isEmpty()) {
            stopCapture();
        }
    }

    @Override
    public void handleDisconnectAll() {
        sessions.forEach((sid, session) -> session.stop());
        sessions.clear();
        stopCapture();
    }

    @Override
    public void handleControl(String sessionId, String type, Map<String, Object> payload) {
        DesktopSession s = sessions.get(sessionId);
        if (s == null) {
            return;
        }
        if ("desktop_control".equals(type)) {
            String action = (String) payload.get("action");
            if ("start".equals(action)) {
                s.start();
            } else if ("stop".equals(action)) {
                s.stop();
            } else if ("quality_mode".equals(action)) {
                String mode = (String) payload.get("value");
                try {
                    s.setQualityMode(DesktopSession.QualityMode.valueOf(mode));
                } catch (Exception e) {
                    log.warn("[DesktopAgent] 未知画质模式: {}", mode);
                }
            } else if ("quality".equals(action)) {
                Object val = payload.get("value");
                if (val instanceof Number) {
                    s.setQuality(((Number) val).intValue());
                }
            }
        }
    }

    @Override
    public void updateTargetSize(String sessionId, int width, int height) {
        DesktopSession s = sessions.get(sessionId);
        if (s != null) {
            s.setTargetSize(width, height);
        }
    }

    @Override
    public void handleInput(String sessionId, String type, Map<String, Object> payload) {
        if (!sessions.containsKey(sessionId)) {
            return;
        }
        try {
            if (robot == null) {
                robot = new Robot();
            }
            switch (type) {
                case "mouse" -> handleMouse(payload);
                case "key" -> handleKey(payload);
                default -> handleControl(sessionId, type, payload);
            }
        } catch (Exception e) {
            log.warn("[DesktopAgent] 输入处理异常: {}", e.getMessage());
        }
    }

    private void handleMouse(Map<String, Object> p) {
        int x = p.containsKey("x") ? ((Number) p.get("x")).intValue() : -1;
        int y = p.containsKey("y") ? ((Number) p.get("y")).intValue() : -1;
        String action = (String) p.get("action");
        if (x >= 0 && y >= 0) {
            robot.mouseMove(x, y);
        }
        if (action == null) {
            return;
        }
        int btn = p.containsKey("button") ? ((Number) p.get("button")).intValue() : 1;
        int mask = switch (btn) {
            case 2 -> InputEvent.BUTTON2_DOWN_MASK;
            case 3 -> InputEvent.BUTTON3_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
        switch (action) {
            case "down" -> robot.mousePress(mask);
            case "up" -> robot.mouseRelease(mask);
            case "wheel" -> {
                int wheel = p.containsKey("wheel") ? ((Number) p.get("wheel")).intValue() : 0;
                robot.mouseWheel(wheel);
            }
            default -> {
            }
        }
    }

    private void handleKey(Map<String, Object> p) {
        int keyCode = p.containsKey("keyCode") ? ((Number) p.get("keyCode")).intValue() : 0;
        String action = (String) p.get("action");
        if (keyCode <= 0 || action == null) {
            return;
        }
        int awtCode = toAwtKeyCode(keyCode);
        if (awtCode <= 0) {
            return;
        }
        if ("down".equals(action)) {
            robot.keyPress(awtCode);
        } else if ("up".equals(action)) {
            robot.keyRelease(awtCode);
        }
    }

    private static int toAwtKeyCode(int jsCode) {
        if (jsCode >= 32 && jsCode <= 126) {
            return jsCode;
        }
        return switch (jsCode) {
            case 8 -> KeyEvent.VK_BACK_SPACE;
            case 9 -> KeyEvent.VK_TAB;
            case 10 -> KeyEvent.VK_ENTER;
            case 16 -> KeyEvent.VK_SHIFT;
            case 17 -> KeyEvent.VK_CONTROL;
            case 18 -> KeyEvent.VK_ALT;
            case 27 -> KeyEvent.VK_ESCAPE;
            case 32 -> KeyEvent.VK_SPACE;
            case 33 -> KeyEvent.VK_PAGE_UP;
            case 34 -> KeyEvent.VK_PAGE_DOWN;
            case 35 -> KeyEvent.VK_END;
            case 36 -> KeyEvent.VK_HOME;
            case 37 -> KeyEvent.VK_LEFT;
            case 38 -> KeyEvent.VK_UP;
            case 39 -> KeyEvent.VK_RIGHT;
            case 40 -> KeyEvent.VK_DOWN;
            case 46 -> KeyEvent.VK_DELETE;
            case 48 -> KeyEvent.VK_0;
            case 49 -> KeyEvent.VK_1;
            case 50 -> KeyEvent.VK_2;
            case 51 -> KeyEvent.VK_3;
            case 52 -> KeyEvent.VK_4;
            case 53 -> KeyEvent.VK_5;
            case 54 -> KeyEvent.VK_6;
            case 55 -> KeyEvent.VK_7;
            case 56 -> KeyEvent.VK_8;
            case 57 -> KeyEvent.VK_9;
            case 65 -> KeyEvent.VK_A;
            case 66 -> KeyEvent.VK_B;
            case 67 -> KeyEvent.VK_C;
            case 68 -> KeyEvent.VK_D;
            case 69 -> KeyEvent.VK_E;
            case 70 -> KeyEvent.VK_F;
            case 71 -> KeyEvent.VK_G;
            case 72 -> KeyEvent.VK_H;
            case 73 -> KeyEvent.VK_I;
            case 74 -> KeyEvent.VK_J;
            case 75 -> KeyEvent.VK_K;
            case 76 -> KeyEvent.VK_L;
            case 77 -> KeyEvent.VK_M;
            case 78 -> KeyEvent.VK_N;
            case 79 -> KeyEvent.VK_O;
            case 80 -> KeyEvent.VK_P;
            case 81 -> KeyEvent.VK_Q;
            case 82 -> KeyEvent.VK_R;
            case 83 -> KeyEvent.VK_S;
            case 84 -> KeyEvent.VK_T;
            case 85 -> KeyEvent.VK_U;
            case 86 -> KeyEvent.VK_V;
            case 87 -> KeyEvent.VK_W;
            case 88 -> KeyEvent.VK_X;
            case 89 -> KeyEvent.VK_Y;
            case 90 -> KeyEvent.VK_Z;
            case 112 -> KeyEvent.VK_F1;
            case 113 -> KeyEvent.VK_F2;
            case 114 -> KeyEvent.VK_F3;
            case 115 -> KeyEvent.VK_F4;
            case 116 -> KeyEvent.VK_F5;
            case 117 -> KeyEvent.VK_F6;
            case 118 -> KeyEvent.VK_F7;
            case 119 -> KeyEvent.VK_F8;
            case 120 -> KeyEvent.VK_F9;
            case 121 -> KeyEvent.VK_F10;
            case 122 -> KeyEvent.VK_F11;
            case 123 -> KeyEvent.VK_F12;
            case 144 -> KeyEvent.VK_NUM_LOCK;
            case 145 -> KeyEvent.VK_SCROLL_LOCK;
            case 155 -> KeyEvent.VK_INSERT;
            default -> 0;
        };
    }

    @Override
    public BaseRemoteAgent getAgent() {
        return agent;
    }

    private VideoEncoder createEncoder(int w, int h, int fps) {
        try {
            VideoEncoder encoder = ServiceProvider.of(VideoEncoder.class)
                    .getNewExtension("nvenc");
            if (encoder == null) {
                log.warn("[DesktopAgent] NVENC 编码器不可用，回退到软件编码");
                encoder = ServiceProvider.of(VideoEncoder.class)
                        .getNewExtension("software");
            }
            if (encoder == null) {
                log.warn("[DesktopAgent] 所有编码器创建失败");
                return null;
            }
            log.info("[DesktopAgent] 编码器: {} {}x{} {}fps 硬件加速={}",
                    encoder.getCodecName(), w, h, fps, encoder.isHardwareAccelerated());
            return encoder;
        } catch (Exception e) {
            log.error("[DesktopAgent] 编码器创建失败: {}", e.getMessage());
            return null;
        }
    }

    private synchronized void startCapture() {
        if (capturing || capture == null) {
            return;
        }
        if (!capture.init(1920, 1080, 30)) {
            log.error("[DesktopAgent] 采集器初始化失败: {} ({})", capture.getClass().getSimpleName(), capture == null ? "null" : "init returned false");
            return;
        }
        capturing = true;
        frameQueue = new LinkedBlockingQueue<>(CAPTURE_QUEUE_CAPACITY);

        captureExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "desktop-capture");
            t.setDaemon(true);
            return t;
        });
        captureExecutor.submit(this::captureLoop);
        captureExecutor.submit(this::encodeLoop);

        ScheduledExecutorService metricsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "desktop-metrics");
            t.setDaemon(true);
            return t;
        });
        metricsScheduler.scheduleAtFixedRate(this::pushMetrics, 1, 1, TimeUnit.SECONDS);
        log.info("[DesktopAgent] 采集已启动");
    }

    private synchronized void stopCapture() {
        capturing = false;
        if (captureExecutor != null) {
            captureExecutor.shutdownNow();
            captureExecutor = null;
        }
        if (frameQueue != null) {
            frameQueue.clear();
            frameQueue = null;
        }
        if (capture != null) {
            capture.close();
        }
    }

    private void captureLoop() {
        while (capturing) {
            if (sessions.isEmpty()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            long frameStart = System.nanoTime();
            try {
                Frame frame = capture.grabFrame();
                if (frame == null) {
                    Thread.sleep(1);
                    continue;
                }
                if (!frameQueue.offer(frame)) {
                    frameQueue.poll();
                    frameQueue.offer(frame);
                }
            } catch (Exception e) {
                log.warn("[DesktopAgent] 采集异常: {}", e.getMessage());
            }
            long elapsed = System.nanoTime() - frameStart;
            int targetFps = sessions.values().stream()
                    .findFirst().map(this::getTargetFps).orElse(60);
            long sleepMs = Math.max(0, (1000L / targetFps) - elapsed / 1_000_000);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void encodeLoop() {
        while (capturing) {
            try {
                Frame frame = frameQueue.take();
                for (DesktopSession session : sessions.values()) {
                    if (session.isRunning()) {
                        session.feedFrame(frame);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[DesktopAgent] 编码异常: {}", e.getMessage());
            }
        }
    }

    private void pushMetrics() {
        try {
            Runtime rt = Runtime.getRuntime();
            long memTotal = rt.totalMemory();
            long memUsed = memTotal - rt.freeMemory();
            for (DesktopSession s : sessions.values()) {
                if (s.isRunning()) {
                    int fps = s.getFps();
                    String decoder = s.getEncoder().getCodecName();
                    log.info("[DesktopAgent] pushMetrics: fps={}, decoder={}", fps, decoder);
                    String json = String.format(
                            "{\"type\":\"desktop_metrics\",\"sessionId\":\"%s\",\"fps\":%d,\"memUsed\":%d,\"memTotal\":%d,\"capture\":\"%s\",\"decoder\":\"%s\"}",
                            s.getSessionId(), fps, memUsed, memTotal,
                            capture.getClass().getSimpleName(), decoder);
                    agent.sendToGateway(json);
                }
            }
        } catch (Exception e) {
            log.warn("[DesktopAgent] pushMetrics error: {}", e.getMessage(), e);
        }
    }

    private int getTargetFps(DesktopSession s) {
        return switch (s.getQualityMode()) {
            case SPEED, BALANCED -> 60;
            case QUALITY -> 30;
            case ORIGINAL -> 25;
        };
    }
}