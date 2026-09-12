package com.chua.runtime.protocol;

/**
* 端点角色（打开telemetry 语义）。
*
* @author CH
* @since 4.0.0.42
 */
public enum EndpointKind {

    /**
    * 服务端（接收连接）
     */
    SERVER,

    /**
    * 客户端（发起连接）
     */
    CLIENT,

    /**
    * 消息生产者（异步消息）
     */
    PRODUCER,

    /**
    * 消息消费者（异步消息）
     */
    CONSUMER,

    /**
    * 进程内（无网络）
     */
    INTERNAL
}