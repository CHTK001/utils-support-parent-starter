package com.chua.protocol.support.network.protocol.server;

import com.chua.protocol.support.network.protocol.request.ServletRequest;
import com.chua.protocol.support.network.protocol.request.ServletResponse;

import java.util.function.BiConsumer;

/**
* 协议服务器接口，抽象不同底层协议（http / armeria / kcp）的服务器生命周期与路由注册。
*
* @author CH
* @since 4.0.0.42
 */
public interface ProtocolServer extends AutoCloseable {

    /**
    * 注册 POST 路由。
    *
    * @param path    路由路径
    * @param handler 请求处理器
    */
    void post(String path, BiConsumer<ServletRequest, ServletResponse> handler);

    /**
    * 注册 获取 路由。
    *
    * @param path    路由路径
    * @param handler 请求处理器
    */
    void get(String path, BiConsumer<ServletRequest, ServletResponse> handler);

    /**
    * 启动服务器。
    *
    * @throws Exception 启动失败
    */
    void start() throws Exception;

    /**
    * 停止服务器。
    *
    * @throws Exception 停止失败
    */
    @Override
    void close() throws Exception;
}
