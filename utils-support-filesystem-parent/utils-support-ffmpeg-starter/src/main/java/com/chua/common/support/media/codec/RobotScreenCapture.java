package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;

/**
 * 基于 Java.awt.Robot 的纯 Java 屏幕采集实现。
 *
 * <p>无需任何 native 依赖，跨平台支持 Windows / Linux / macOS。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("robot")
public class RobotScreenCapture implements ScreenCature {

    /** Robot */
    private Robot robot;
    /** 宽度 */
    private int width;
    /** 高度 */
    private int height;
    /** FPS */
    private int fps;
    /** Initialized */
    private boolean initialized;
    /** 帧转换器 */
    private Java2DFrameConverter frameConverter;
    /** capturebuf */
    private java.awt.image.BufferedImage captureBuf;

    @Override
    /** 初始化 */
    public boolean init(int width, int height, int fps) {
        try {
            this.robot = new Robot();
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.frameConverter = new Java2DFrameConverter();
            java.awt.Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            this.captureBuf = new BufferedImage((int) screenSize.getWidth(), (int) screenSize.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            this.initialized = true;
            log.info("[RobotScreenCapture] 已初始化: {}x{} {}fps YUV420P", width, height, fps);
            return true;
        } catch (AWTException e) {
            log.error("[RobotScreenCapture] 初始化失败: {}", e.getMessage());
            this.initialized = false;
            return false;
        }
    }

    @Override
    /** grab帧 */
    public Frame grabFrame() {
        if (!initialized || robot == null) {
            log.warn("[RobotScreenCapture] 未初始化，无法采集");
            return null;
        }
        try {
            BufferedImage fullScreen = robot.createScreenCapture(
                    new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            scaled.getGraphics().drawImage(fullScreen, 0, 0, width, height, null);
            return frameConverter.convert(scaled);
        } catch (Exception e) {
            log.warn("[RobotScreenCapture] 采集帧失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    /** 获取Width */
    public int getWidth() {
        return width;
    }

    @Override
    /** 获取Height */
    public int getHeight() {
        return height;
    }

    @Override
    /** 关闭 */
    public void close() {
        this.initialized = false;
        this.robot = null;
        this.frameConverter = null;
        this.captureBuf = null;
        log.info("[RobotScreenCapture] 已关闭");
    }
}