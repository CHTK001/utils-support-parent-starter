package com.chua.common.support.scatter.node;

import com.chua.common.support.scatter.protocol.ScatterFrame;

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
}
