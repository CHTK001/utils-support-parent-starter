package com.chua.remote.agent;

import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * Native 屏幕采集器。
 *
 * <p>使用 {@link java.awt.Robot} 进行跨平台截图，
 * 支持多显示器和指定区域采集。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ScreenCapture {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** Robot 实例 */
    private final Robot robot;

    /** 屏幕尺寸 */
    private final Dimension screenSize;

    /** 是否多屏 */
    private final boolean multiScreen;

    public ScreenCapture(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
        this.multiScreen = isMultiScreen();
        try {
            this.robot = new Robot();
            this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        } catch (AWTException e) {
            throw new RuntimeException("初始化 Robot 失败", e);
        }
    }

    /**
     * 采集屏幕画面。
     *
     * @return 截图数据
     */
    public byte[] capture() {
        BufferedImage image = captureBufferedImage();
        if (image == null) {
            return new byte[0];
        }
        return BufferedImageUtils.toBufferedImageArray(image, "png");
    }

    /**
     * 采集屏幕画面为 BufferedImage。
     *
     * @return BufferedImage
     */
    public BufferedImage captureBufferedImage() {
        if (multiScreen) {
            return captureAllScreens();
        }
        return capturePrimaryScreen();
    }

    /**
     * 采集主屏幕。
     */
    public BufferedImage capturePrimaryScreen() {
        try {
            BufferedImage capture = robot.createScreenCapture(
                    new Rectangle(screenSize));
            log.debug("采集主屏幕: agentId={}, size={}x{}",
                    agentInfo.getId(), capture.getWidth(), capture.getHeight());
            return capture;
        } catch (Exception e) {
            log.error("采集主屏幕失败: agentId={}", agentInfo.getId(), e);
            return null;
        }
    }

    /**
     * 采集所有屏幕（多屏合并）。
     */
    public BufferedImage captureAllScreens() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screens = ge.getScreenDevices();
            if (screens.length == 0) {
                return capturePrimaryScreen();
            }
            // 计算合并后的总尺寸
            int totalWidth = 0;
            int totalHeight = 0;
            for (GraphicsDevice screen : screens) {
                totalWidth += screen.getDefaultConfiguration().getBounds().width;
                totalHeight = Math.max(totalHeight, screen.getDefaultConfiguration().getBounds().height);
            }
            // 创建合并后的图像
            BufferedImage combined = new BufferedImage(
                    totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = combined.createGraphics();
            int offsetX = 0;
            for (GraphicsDevice screen : screens) {
                Rectangle bounds = screen.getDefaultConfiguration().getBounds();
                BufferedImage capture = robot.createScreenCapture(bounds);
                g.drawImage(capture, offsetX, 0, null);
                offsetX += capture.getWidth();
            }
            g.dispose();
            log.debug("采集多屏: agentId={}, totalSize={}x{}",
                    agentInfo.getId(), totalWidth, totalHeight);
            return combined;
        } catch (Exception e) {
            log.error("采集多屏失败: agentId={}", agentInfo.getId(), e);
            return capturePrimaryScreen();
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
            BufferedImage capture = robot.createScreenCapture(
                    new Rectangle(x, y, width, height));
            log.debug("采集区域: agentId={}, x={}, y={}, w={}, h={}",
                    agentInfo.getId(), x, y, width, height);
            return BufferedImageUtils.toBufferedImageArray(capture, "png");
        } catch (Exception e) {
            log.error("采集区域失败", e);
            return new byte[0];
        }
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
