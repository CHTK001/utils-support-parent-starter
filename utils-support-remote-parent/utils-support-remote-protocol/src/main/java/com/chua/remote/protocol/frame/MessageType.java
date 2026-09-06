package com.chua.remote.protocol.frame;

/**
 * 远控消息类型枚举。
 *
 * <p>SIGNAL: 信令（注册、心跳、能力上报、会话控制）</p>
 * <p>DATA: 媒体数据（屏幕流、键鼠事件、剪贴板）</p>
 * <p>CTRL: 控制（鉴权握手、编解码协商、转码指令）</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum MessageType {
    SIGNAL,
    DATA,
    CTRL
}
