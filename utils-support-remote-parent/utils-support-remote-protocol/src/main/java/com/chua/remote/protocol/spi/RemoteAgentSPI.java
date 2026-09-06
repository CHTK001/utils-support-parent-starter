package com.chua.remote.protocol.spi;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.model.AgentInfo;

/**
 * 被控端 SPI 接口。
 *
 * <p>定义被控端的编码能力上报、屏幕采集、键鼠事件捕获等能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RemoteAgentSPI {

    /**
     * 上报被控端信息到网关。
     *
     * @param info 被控端注册信息
     */
    void reportInfo(AgentInfo info);

    /**
     * 上报编码能力。
     *
     * @param capability 编码能力
     */
    void reportCapability(CodecProfile capability);

    /**
     * 采集屏幕画面（服务模式，纯自研截图）。
     *
     * @return 截图数据
     */
    byte[] captureScreen();

    /**
     * 捕获键鼠事件（服务模式）。
     *
     * @return 事件数据
     */
    byte[] captureInputEvent();

    /**
     * 是否为服务模式（完整自研系统）。
     *
     * @return true=服务模式
     */
    boolean isServiceMode();
}
