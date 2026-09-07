package com.chua.remote.agent;

import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;

/**
 * Native 屏幕采集器。
 *
 * <p>使用 {@link NativeScreenCapture}（操作系统原生采集——Windows GDI / Linux X11 /
 * macOS CoreGraphics）进行截图，不允许 {@link java.awt.Robot}；采集直接产出原始
 * RGB 像素帧（{@link NativeFrame}，不经 BufferedImage），编码链路直接消费原始帧；
 * 兼容方法（capture/captureBufferedImage）仅在消费边界做一次转换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ScreenCapture {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** 屏幕尺寸 */
    private final Dimension screenSize;

    /** 是否多屏 */
    private final boolean multiScreen;

    public ScreenCapture(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
        this.multiScreen = isMultiScreen();
        this.screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
    }

    /**
     * 采集屏幕画面（原始像素帧——不经 BufferedImage）。
     *
     * @return 原始 RGB 像素帧
     */
    public NativeFrame captureFrame() {
        if (multiScreen) {
            return captureAllScreensFrame();
        }
        return NativeScreenCapture.capture(new Rectangle(screenSize));
    }

    /**
     * 采集所有屏幕（多屏合并，原始像素行拼接）。
     *
     * @return 合并后的原始 RGB 像素帧
     */
    private NativeFrame captureAllScreensFrame() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screens = ge.getScreenDevices();
            if (screens.length == 0) {
                return NativeScreenCapture.capture(new Rectangle(screenSize));
            }
            // 计算合并后的总尺寸
            int totalWidth = 0;
            int totalHeight = 0;
            for (GraphicsDevice screen : screens) {
                totalWidth += screen.getDefaultConfiguration().getBounds().width;
                totalHeight = Math.max(totalHeight, screen.getDefaultConfiguration().getBounds().height);
            }
            // 原始像素行拼接（显式拷贝——不经 BufferedImage）
            byte[] combined = new byte[totalWidth * totalHeight * 3];
            int offsetX = 0;
            for (GraphicsDevice screen : screens) {
                Rectangle bounds = screen.getDefaultConfiguration().getBounds();
                NativeFrame frame = NativeScreenCapture.capture(bounds);
                for (int y = 0; y < frame.height(); y++) {
                    System.arraycopy(frame.pixels(), y * frame.width() * 3,
                            combined, y * totalWidth * 3 + offsetX * 3, frame.width() * 3);
                }
                offsetX += frame.width();
            }
            log.debug("采集多屏: agentId={}, totalSize={}x{}",
                    agentInfo.getId(), totalWidth, totalHeight);
            return new NativeFrame(totalWidth, totalHeight, NativeFrame.FORMAT_RGB, combined);
        } catch (Exception e) {
            log.error("采集多屏失败: agentId={}", agentInfo.getId(), e);
            return NativeScreenCapture.capture(new Rectangle(screenSize));
        }
    }

    /**
     * 采集屏幕画面为 BufferedImage（消费边界转换——编码链路请使用 {@link #captureFrame()}）。
     *
     * @return BufferedImage
     */
    public BufferedImage captureBufferedImage() {
        return toBufferedImage(captureFrame());
    }

    /**
     * 采集屏幕画面。
     *
     * @return 截图数据（PNG 编码）
     */
    public byte[] capture() {
        try {
            return BufferedImageUtils.toBufferedImageArray(captureBufferedImage(), "png");
        } catch (IOException e) {
            log.error("截图编码失败: agentId={}", agentInfo.getId(), e);
            return new byte[0];
        }
    }

    /**
     * 采集指定区域。
     *
     * @param x      起始 x
     * @param y      起始 y
     * @param width  宽度
     * @param height 高度
     * @return 截图数据
     */
    public byte[] captureRegion(int x, int y, int width, int height) {
        try {
            NativeFrame frame = NativeScreenCapture.capture(new Rectangle(x, y, width, height));
            log.debug("采集区域: agentId={}, x={}, y={}, w={}, h={}",
                    agentInfo.getId(), x, y, width, height);
            return BufferedImageUtils.toBufferedImageArray(toBufferedImage(frame), "png");
        } catch (Exception e) {
            log.error("采集区域失败", e);
            return new byte[0];
        }
    }

    /**
     * 原始 RGB 像素帧 → BufferedImage（仅消费边界转换）。
     *
     * @param frame 原始 RGB 像素帧
     * @return BufferedImage（TYPE_INT_RGB，显式像素回填）
     */
    private static BufferedImage toBufferedImage(NativeFrame frame) {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        byte[] rgb = frame.pixels();
        for (int i = 0; i < target.length; i++) {
            int p = i * 3;
            target[i] = ((rgb[p] & 0xFF) << 16) | ((rgb[p + 1] & 0xFF) << 8) | (rgb[p + 2] & 0xFF);
        }
        return image;
    }

    /**
     * 关闭采集器，释放资源。
     */
    public void close() {
        log.info("关闭屏幕采集器: agentId={}", agentInfo.getId());
    }

    /**
     * 是否为多屏环境。
     */
    private boolean isMultiScreen() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            return ge.getScreenDevices().length > 1;
        } catch (Exception e) {
            return false;
        }
    }
}
