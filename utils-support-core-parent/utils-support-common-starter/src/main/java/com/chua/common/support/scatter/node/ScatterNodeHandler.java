package com.chua.common.support.scatter.node;

import com.chua.common.support.scatter.protocol.ScatterFrame;

import java.net.InetSocketAddress;

/**
 * scatter 节点帧处理器：服务端收到一帧（REQ/PUSH 等）后由 discovery 实现处理并返回响应帧。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterNodeHandler {

    /**
     * 处理一帧请求。
     *
     * @param frame 请求帧
     * @return 响应帧字节
     * @throws Exception 处理异常
     */
    byte[] handle(ScatterFrame frame) throws Exception;

    /**
     * 处理一帧请求（带收包源地址）。UDP 服务端传入源地址，供 DISC 宣告合并出新增成员后
     * 向新成员单播回表等场景使用；无源地址语义的实现沿用 {@link #handle(ScatterFrame)}。
     *
     * @param frame  请求帧
     * @param sender 收包源地址（UDP 场景）；无源地址语义时为 null
     * @return 响应帧字节
     * @throws Exception 处理异常
     */
    default byte[] handle(ScatterFrame frame, InetSocketAddress sender) throws Exception {
        return handle(frame);
    }

    /**
     * 对即将发送的帧字节追加鉴权签名尾部（帧鉴权开启时）。服务端响应与客户端直发
     * （UDP 广播/单播宣告）统一经本方法签名；未开启鉴权时默认原样返回。
     *
     * @param frameBytes 原始帧字节（ScatterFrame#encode 的输出），不允许为 null
     * @return 签名后的线格式字节（未开启鉴权时与入参相同）
     */
    default byte[] signFrame(byte[] frameBytes) {
        return frameBytes;
    }

    /**
     * 校验并剥离收到的线格式字节的鉴权签名尾部（帧鉴权开启时），返回可解码的原始帧字节。
     * 未开启鉴权时默认原样返回；开启后验签失败返回 null（调用方应丢弃该帧）。
     *
     * @param wireBytes 线格式字节（可能带签名尾部），不允许为 null
     * @return 可解码的原始帧字节；验签失败返回 null（未开启鉴权时与入参相同）
     */
    default byte[] verifyFrame(byte[] wireBytes) {
        return wireBytes;
    }
}
