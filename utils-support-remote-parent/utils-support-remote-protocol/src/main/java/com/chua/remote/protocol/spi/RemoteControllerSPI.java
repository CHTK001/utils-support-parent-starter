package com.chua.remote.protocol.spi;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.model.ControllerInfo;

/**
 * 控制端 SPI 接口。
 *
 * <p>定义控制端的接入、解码渲染、会话发起等能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RemoteControllerSPI {

    /**
     * 接入网关。
     *
     * @param info 控制端信息
     * @return 接入结果
     */
    String connect(ControllerInfo info);

    /**
     * 发起对某被控端的会话。
     *
     * @param agentId      被控端 id
     * @param verifyCode   验证码
     * @return 会话 id
     */
    String startSession(String agentId, String verifyCode);

    /**
     * 上报解码能力。
     *
     * @param capability 解码能力
     */
    void reportCapability(CodecProfile capability);

    /**
     * 渲染解码后的画面。
     *
     * @param frameData 帧数据
     */
    void renderFrame(byte[] frameData);

    /**
     * 注入键鼠事件。
     *
     * @param eventData 事件数据
     */
    void injectInputEvent(byte[] eventData);
}
