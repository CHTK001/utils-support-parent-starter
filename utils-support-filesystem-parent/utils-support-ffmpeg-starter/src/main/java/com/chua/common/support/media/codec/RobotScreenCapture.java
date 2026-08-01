package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;

/**
 * 基于 java.awt.Robot 的纯 Java 屏幕采集实现。
 *
 * <p>无需任何 native 依赖，跨平台支持 Windows / Linux / macOS。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("robot")
public class RobotScreenCapture implements ScreenCature {

    private Robot robot;
    private int width;
    private int height;
    private int fps;
    private boolean initialized;

    @Override
    public boolean init(int width, int height, int fps) {
        try {
            this.robot = new Robot();
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.initialized = true;
            log.info("[RobotScreenCapture] 已初始化: {}x{} {}fps", width, height, fps);
            return true;
        } catch (AWTException e) {
            log.error("[RobotScreenCapture] 初始化失败: {}", e.getMessage());
            this.initialized = false;
            return false;
        }
    }

    @Override
    public ByteBuffer grabFrame() {
        if (!initialized || robot == null) {
            log.warn("[RobotScreenCapture] 未初始化，无法采集");
            return null;
        }
        try {
            BufferedImage fullScreen = robot.createScreenCapture(
                    new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            scaled.getGraphics().drawImage(fullScreen, 0, 0, width, height, null);
            byte[] pixels = ((DataBufferByte) scaled.getRaster().getDataBuffer()).getData();
            ByteBuffer buf = ByteBuffer.allocateDirect(pixels.length);
            buf.put(pixels);
            buf.flip();
            return buf;
        } catch (Exception e) {
            log.warn("[RobotScreenCapture] 采集帧失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void close() {
        this.initialized = false;
        this.robot = null;
        log.info("[RobotScreenCapture] 已关闭");
    }
}