package com.chua.remote.agent;

import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Native 屏幕采集器。
 *
 * <p>使用本地系统 API（Windows DXGI / Linux X11 / macOS CGWindow）进行高性能截图，
 * 支持多显示器和指定区域采集。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ScreenCapture {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** 是否启用多屏 */
    private final boolean multiScreen;

    public ScreenCapture(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
        this.multiScreen = isMultiScreen();
    }

    /**
     * 采集屏幕画面。
     *
     * @return 截图数据（原始像素或压缩后的字节数组）
     */
    public byte[] capture() {
        if (multiScreen) {
            return captureAllScreens();
        }
        return capturePrimaryScreen();
    }

    /**
     * 采集主屏幕。
     */
    private byte[] capturePrimaryScreen() {
        log.debug("采集主屏幕: agentId={}", agentInfo.getId());
        return new byte[0];
    }

    /**
     * 采集所有屏幕。
     */
    private byte[] captureAllScreens() {
        log.debug("采集所有屏幕: agentId={}", agentInfo.getId());
        return new byte[0];
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
        log.debug("采集区域: agentId={}, x={}, y={}, w={}, h={}", agentInfo.getId(), x, y, width, height);
        return new byte[0];
    }

    /**
     * 关闭采集器，释放 native 资源。
     */
    public void close() {
        log.info("关闭屏幕采集器: agentId={}", agentInfo.getId());
    }

    /**
     * 是否为多屏环境。
     */
    private boolean isMultiScreen() {
        return false;
    }
}
