package com.chua.common.support.network.server.handler;

import com.chua.common.support.network.server.http.HttpDefaultServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

/**
* 通用路由处理器接口，该接口设计为协议无关。
* <p>
* 所有网络协议均使用统一的 handle 方法进行请求处理。
* 具体的协议特性（如 HTTP 的路径、方法头信息等）由子接口实现多态处理。
* </p>
* <p>
* 支持的子接口及特性参考：
* <ul>
*   <li>{@link HttpDefaultServerHandler} — 专门用于 HTTP 协议，支持 path、method 和 headers 等特有属性</li>
* </ul>
* </p>
*
* @author CH
* @since 2026/07/16
 */
@FunctionalInterface
public interface ServerHandler {

    /**
    * 处理服务器请求的核心方法。
    * <p>
    * 该方法接收一个请求对象和一个响应对象，执行相应的业务逻辑并填充响应。
    * </p>
    *
    * @param request  代表当前客户端发起的网络请求，包含原始数据和上下文信息
    * @param response 代表需要返回给客户端的响应对象，用于设置状态码、数据体等信息
    * @throws Exception 当处理过程中发生任何异常时抛出，由上层调用者捕获或传播
     */
    void handle(ServerRequest request, ServerResponse response) throws Exception;
}