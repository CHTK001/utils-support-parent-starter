package com.chua.remote.support.gateway.transport.ws;

import io.netty.channel.Channel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 客户端能力管理器
 * <p>
 * 记录每个 WebSocket 会话的编解码器支持情况、期望分辨率与帧率。
 * 网关根据这些能力信息决策桌面帧的转码策略（直传 H264 / 转码 JPEG）。
 * 同时维护 sessionId 到 WS 通道的映射，用于推送桌面帧到指定客户端。
 *
 * @author CH
 * @see BinaryAgentFrameHandler 根据此管理器决策转码的处理器
 */
public class ClientCapabilityManager {

    /**
     * 客户端能力值对象
     *
     * @param sessionId 会话 ID
     * @param codecs    支持的编码器集合（如 "H264"、"JPEG"）
     * @param width     期望的显示宽度（像素）
     * @param height    期望的显示高度（像素）
     * @param fps       期望的帧率
     */
    public record ClientCapability(String sessionId, Set<String> codecs,
                                   int width, int height, int fps) {}

    /** 会话 ID → 客户端能力映射 */
    private final Map<String, ClientCapability> capabilities = new ConcurrentHashMap<>();
    /** 会话 ID → WebSocket 通道映射，用于桌面帧推送 */
    private final Map<String, Channel> wsChannels = new ConcurrentHashMap<>();

    /**
     * 注册客户端能力
     * @param sessionId 会话ID
     * @param codecs    支持的编码器列表 (e.g. ["H264"], ["H264","JPEG"])
     * @param width     期望宽度
     * @param height    期望高度
     * @param fps       期望帧率
     */
    public void register(String sessionId, List<String> codecs, int width, int height, int fps) {
        Set<String> codecSet;
        if (codecs != null && !codecs.isEmpty()) {
            codecSet = new CopyOnWriteArraySet<>();
            for (String codec : codecs) {
                // Normalize codec names to uppercase for consistent comparison
                String normalized = codec.trim().toUpperCase();
                codecSet.add(normalized);
            }
        }
 else {
            codecSet = new CopyOnWriteArraySet<>(Set.of("H264"));
        }
        capabilities.put(sessionId, new ClientCapability(sessionId, codecSet, width, height, fps));
    }

    /**
     * 绑定 sessionId 到 WS 通道
     *
     * @param sessionId 会话 ID
     * @param channel   WebSocket 通道
     */
    public void bindChannel(String sessionId, Channel channel) {
        wsChannels.put(sessionId, channel);
    }

    /**
     * 获取指定会话绑定的 WS 通道
     *
     * @param sessionId 会话 ID
     * @return WebSocket 通道，若未绑定则返回 null
     */
    public Channel getChannel(String sessionId) {
        return wsChannels.get(sessionId);
    }

    /**
     * 判断客户端是否支持指定编码格式
     *
     * @param sessionId 会话 ID
     * @param codec     编码格式名称（如 "H264"、"JPEG"）
     * @return 若客户端声明了该编码则返回 true
     */
    public boolean supportsCodec(String sessionId, String codec) {
        ClientCapability cap = capabilities.get(sessionId);
        return cap != null && cap.codecs().contains(codec);
    }

    /**
     * 获取客户端能力详情
     *
     * @param sessionId 会话 ID
     * @return 客户端能力值对象，若未注册则返回 null
     */
    public ClientCapability getCapability(String sessionId) {
        return capabilities.get(sessionId);
    }

    /**
     * 获取客户端期望的显示宽度
     *
     * @param sessionId 会话 ID
     * @return 宽度（像素），若未注册则返回默认值 1920
     */
    public int getClientWidth(String sessionId) {
        ClientCapability cap = capabilities.get(sessionId);
        return cap != null ? cap.width() : 1920;
    }

    /**
     * 获取客户端期望的显示高度
     *
     * @param sessionId 会话 ID
     * @return 高度（像素），若未注册则返回默认值 1080
     */
    public int getClientHeight(String sessionId) {
        ClientCapability cap = capabilities.get(sessionId);
        return cap != null ? cap.height() : 1080;
    }

    /**
     * 移除指定会话的客户端能力和通道绑定
     *
     * @param sessionId 会话 ID
     */
    public void remove(String sessionId) {
        capabilities.remove(sessionId);
        wsChannels.remove(sessionId);
    }
}
