package com.chua.webrtc.spi;

import com.chua.webrtc.control.ControlService;
import com.chua.webrtc.model.WebRtcPeer;
import com.chua.webrtc.model.WebRtcSession;
import com.chua.webrtc.model.WebRtcSignalingRequest;
import com.chua.webrtc.model.WebRtcSignalingResponse;
import com.chua.webrtc.spi.media.ScreenCaptureService;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WebRTC 远控 SPI 入口接口。
 *
 * <p>定义 Java 服务端与浏览器客户端 WebRTC 通信的生命周期管理，包括：
 * 点对点连接创建、信令交换、桌面采集、远程控制等。</p>
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>所有 WebRTC 相关回调均运行在 webrtc-java 内部的 Native 线程中</li>
 *   <li>调用方需自行将回调翻译到业务线程（如 Spring @Async 或 ScheduledExecutor）</li>
 *   <li>屏幕帧投递 / 键鼠事件注入 需避免长耗时操作</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface WebRtcService {

    /**
     * SPI 名称，用于 ServiceProvider 多实现发现与覆盖排序。
     *
     * @return 服务名称，默认 {@code webrtc}
     */
    String spiName();

    /**
     * 获取当前所有活跃会话。
     *
     * @return 活跃会话集合
     */
    Collection<WebRtcSession> sessions();

    /**
     * 根据会话ID查找活跃会话。
     *
     * @param sessionId 会话ID
     * @return 找到的会话
     */
    Optional<WebRtcSession> session(String sessionId);

    /**
     * 创建新的 WebRTC 会话。
     * <p>每个会话对应一个浏览器客户端连接，内部维护自己的
     * RTCPeerConnection + 屏幕采集 + 控制通道。</p>
     *
     * @param sessionId 会话ID，唯一标识一个远控连接
     * @return 新会话
     */
    WebRtcSession createSession(String sessionId);

    /**
     * 创建新的 WebRTC 会话（自动生成 session.id）
     *
     * @return 新会话
     */
    default WebRtcSession createSession() {
        return createSession(UUID.randomUUID().toString());
    }

    /**
     * 销毁会话，关闭相关连接和资源。
     *
     * @param sessionId 会话ID
     */
    void destroySession(String sessionId);

    /**
     * 获取某个会话的全部连接点。
     *
     * @param sessionId 会话ID
     * @return 该会话连接点集合
     */
    List<WebRtcPeer> peers(String sessionId);

    /**
     * 处理入站信令请求。
     *
     * <p>由 WebSocket 消息处理器调用，负责：
     * <ul>
     *   <li>SDP offer → 创建 PeerConnection 并返回SDP answer</li>
     *   <li>SDP answer → 建立连接</li>
     *   <li>ICE candidate → 加入连接</li>
     * </ul>
     *
     * @param sessionId 会话ID
     * @param request   信令请求
     * @return 信令响应
     */
    WebRtcSignalingResponse receiveSignaling(String sessionId, WebRtcSignalingRequest request);

    /**
     * 检查运行时状态。
     *
     * @return true 表示 WebRTC runtime 已就绪
     */
    boolean isRunning();

    /**
     * WebRTC 相关扩展服务聚合。
     *
     * @return 扩展服务
     */
    Extensions extensions();

    /**
     * WebRTC 相关扩展服务聚合。
     */
    interface Extensions {
        /**
         * 桌面采集服务。
         *
         * @return 采集服务
         */
        ScreenCaptureService screenCaptureService();

        /**
         * 远端控制服务。
         *
         * @return 控制服务
         */
        ControlService controlService();
    }
}
