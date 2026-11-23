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
}
