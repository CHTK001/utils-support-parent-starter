package com.chua.webrtc.capture;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import dev.onvoid.webrtc.media.video.VideoTrack;
import com.chua.webrtc.spi.media.ScreenCaptureService;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 webrtc-java 的桌面采集实现。
 *
 * <p>使用 {@link VideoDesktopSource} 替代 {@link java.awt.Robot} 截屏：
 * <ul>
 *   <li>Windows: DXGI Desktop Duplication 硬件加速</li>
 *   <li>Linux: X11 Shm / PipeWire</li>
 *   <li>macOS: VideoToolbox 硬件加速</li>
 * </ul>
 * 帧率可配置、体积可伸缩。</p>
 *
 * <p>帧投递模式：<br/>
 * {@link VideoDesktopSource} 启动后由 Native 端连续采集帧，
 * {@link PeerConnectionFactory#createVideoTrack(String, dev.onvoid.webrtc.media.video.VideoTrackSource)}
 * 创建的 {@link VideoTrack} 与采集源绑定，帧自动注入轨道。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi(value = "webrtc.desktop-capture", order = 10)
@SpiDefault
public class JavaScreenCaptureService implements ScreenCaptureService {

    /** SPI 名称 */
    public static final String SPI_NAME = "webrtc.desktop-capture";

    /** 默认帧率 */
    private static final int DEFAULT_FPS = 15;

    /** 默认宽度 */
    private static final int DEFAULT_WIDTH = 1280;

    /** 默认高度 */
    private static final int DEFAULT_HEIGHT = 720;

    /** 帧率 */
    private int frameRate = DEFAULT_FPS;

    /** 视频宽度 */
    private int width = DEFAULT_WIDTH;

    /** 视频高度 */
    private int height = DEFAULT_HEIGHT;

    /**
     * 是否由 {@link #setResize(int, int)} 显式指定过采集尺寸。
     *
     * <p>未指定时采集尺寸跟随真实屏幕像素：远控的鼠标坐标是「采集像素」，
     * 注入端（{@code Robot.mouseMove}）按同一套数值直接落点，两者必须 1:1，
     * 否则画面缩放到 1280×720 后，点在画面中心的鼠标会落到屏幕左上区域。</p>
     */
    private boolean resizeConfigured;

    /**
     * 采集器（列出屏幕）
     *
     * <p>懒加载：webrtc-java 的 Native 库由 {@link PeerConnectionFactory} 的静态块加载，
     * 本类可能在工厂创建之前实例化，因此不能在构造期创建 {@link ScreenCapturer}，
     * 否则会抛 {@code UnsatisfiedLinkError}。</p>
     */
    private volatile ScreenCapturer screenCapturer;

    /** 桌面采集源（VideoTrackSource） */
    private volatile VideoDesktopSource desktopSource;

    /** 视频轨道（供 PeerConnection 使用） */
    private volatile VideoTrack videoTrack;

    /** 信令 PeerConnectionFactory（由 WebRtcServer 注入） */
    private volatile PeerConnectionFactory peerConnectionFactory;

    /**
     * 当前使用的屏幕源 ID
     */
    private long sourceId = 0L;

    @Override
    public String spiName() {
        return SPI_NAME;
    }

    @Override
    public void start(VideoTrack track) {
        try {
            stop(track);

            // 创建桌面采集源
            desktopSource = new VideoDesktopSource();
            desktopSource.setFrameRate(frameRate);
            // 采集尺寸与真实屏幕 1:1（未显式 setResize 时），保证画面坐标 == 屏幕坐标
            Rectangle screen = resizeConfigured ? null : primaryScreenBounds();
            desktopSource.setMaxFrameSize(screen == null ? width : screen.width,
                    screen == null ? height : screen.height);
            // 关键：sourceId 默认值是 0，而 0 不是合法的桌面源 id。
            // 传 0 时 Native 端不会报错、轨道也建得出来，但一帧都不会产出 ——
            // 表现为 SDP 已协商、DataChannel 通、浏览器侧 videoWidth=0 且 0 帧。
            // 未显式指定源时按「第一个可用桌面源」兜底。
            desktopSource.setSourceId(sourceId > 0 ? sourceId : defaultSourceId(), false);
            desktopSource.start();

            // 轨道由 PeerConnectionFactory 从源创建，帧自动注入
            if (peerConnectionFactory != null) {
                videoTrack = peerConnectionFactory.createVideoTrack("videoTrack", desktopSource);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start screen capture", e);
        }
    }

    @Override
    public void stop(VideoTrack track) {
        try {
            VideoDesktopSource source = desktopSource;
            if (source != null) {
                desktopSource = null;
                try {
                    source.stop();
                } catch (Exception ignored) {
                    // ignore
                }
                try {
                    source.dispose();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        } finally {
            videoTrack = null;
        }
    }

    @Override
    public void setCaptureRate(int fps) {
        this.frameRate = Math.max(1, Math.min(60, fps));
        if (desktopSource != null) {
            desktopSource.setFrameRate(frameRate);
        }
    }

    @Override
    public void setResize(int width, int height) {
        this.width = width;
        this.height = height;
        this.resizeConfigured = true;
        if (desktopSource != null) {
            desktopSource.setMaxFrameSize(width, height);
        }
    }

    @Override
    public boolean supportWindowMode() {
        return true;
    }

    @Override
    public ScreenInfo[] screens() {
        List<ScreenInfo> list = new ArrayList<>();
        try {
            List<DesktopSource> sources = screenCapturer().getDesktopSources();
            Rectangle[] bounds = screenBounds(sources.size());
            int index = 0;
            for (DesktopSource screen : sources) {
                Rectangle box = bounds[index];
                String name = screen.title == null || screen.title.isBlank()
                        ? "屏幕 " + (index + 1) : screen.title;
                list.add(new DefaultScreenInfo(String.valueOf(screen.id), name,
                        box == null ? width : box.width,
                        box == null ? height : box.height));
                index++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list screen sources", e);
        }
        return list.toArray(new ScreenInfo[0]);
    }

    /**
     * 未显式指定采集源时的默认源：第一个可用桌面源。
     *
     * <p>不能沿用 {@code sourceId} 的初始值 0：Native 端对未知 id 既不报错也不出帧。</p>
     *
     * @return 默认桌面源 id
     */
    private long defaultSourceId() {
        List<DesktopSource> sources = screenCapturer().getDesktopSources();
        if (sources == null || sources.isEmpty()) {
            throw new IllegalStateException("No desktop source available");
        }
        return sources.get(0).id;
    }

    /**
     * 主屏像素尺寸。
     *
     * @return 主屏尺寸；headless 或 AWT 不可用时返回 null
     */
    private Rectangle primaryScreenBounds() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getBounds();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取各桌面源的像素尺寸。
     *
     * <p>webrtc-java 0.14 的 {@link DesktopSource} 只有 id/title，没有宽高；
     * 这里用 AWT 的屏幕设备尺寸按序号对齐。数量对不上或处于 headless 时返回全 null，
     * 由调用方回落到配置的采集尺寸。</p>
     *
     * @param count 桌面源数量
     * @return 与 count 等长的尺寸数组，未知为 null
     */
    private Rectangle[] screenBounds(int count) {
        Rectangle[] result = new Rectangle[count];
        try {
            GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            if (devices.length == count) {
                for (int i = 0; i < count; i++) {
                    result[i] = devices[i].getDefaultConfiguration().getBounds();
                }
            }
        } catch (Exception ignored) {
            // headless 或 AWT 不可用：交给调用方回落
        }
        return result;
    }

    @Override
    public void close() {
        ScreenCapturer capturer = screenCapturer;
        if (capturer != null) {
            capturer.dispose();
            screenCapturer = null;
        }
    }

    /**
     * 获取（懒创建）桌面采集器
     *
     * @return 采集器
     */
    private ScreenCapturer screenCapturer() {
        ScreenCapturer capturer = screenCapturer;
        if (capturer == null) {
            synchronized (this) {
                capturer = screenCapturer;
                if (capturer == null) {
                    capturer = new ScreenCapturer();
                    screenCapturer = capturer;
                }
            }
        }
        return capturer;
    }

    @Override
    public VideoTrack videoTrack() {
        return videoTrack;
    }

    /**
     * 注入 PeerConnectionFactory（在服务器启动时调用）
     *
     * @param factory 工厂
     */
    public void init(PeerConnectionFactory factory) {
        this.peerConnectionFactory = factory;
    }

    /**
     * 屏幕信息默认实现
     */
    private static class DefaultScreenInfo implements ScreenCaptureService.ScreenInfo {
        private final String id;
        private final String name;
        private final int width;
        private final int height;

        DefaultScreenInfo(String id, String name, int width, int height) {
            this.id = id;
            this.name = name;
            this.width = width;
            this.height = height;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }
    }
}
