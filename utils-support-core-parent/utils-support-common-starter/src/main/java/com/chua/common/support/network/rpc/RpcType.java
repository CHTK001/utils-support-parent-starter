package com.chua.common.support.network.rpc;


/**
* RPC 协议类型枚举，标识当前使用的底层 RPC 通信协议。
*
* <h2>协议说明</h2>
* <table>
*   <tr><th>枚举常量</th><th>底层框架</th><th>适用场景</th></tr>
*   <tr><td>{@link #DUBBO}</td><td>Apache Dubbo 3.x</td><td>企业级微服务、高并发场景</td></tr>
*   <tr><td>{@link #SOFA}</td><td>蚂蚁集团 SOFA-RPC (Bolt)</td><td>金融级场景、大促流量</td></tr>
*   <tr><td>{@link #JSON}</td><td>jsonrpc4j (HTTP 传输)</td><td>跨语言集成、轻量服务、开发测试</td></tr>
*   <tr><td>{@link #NONE}</td><td>—</td><td>未指定协议，占位使用</td></tr>
* </table>
*
* @author CH
* @since 1.0.0
 */
public enum RpcType {

    /**
    * Apache Dubbo RPC 协议
    *
    * <p>业界广泛使用的高性能 Java RPC 框架，提供丰富的服务治理能力。
    * 支持多种协议（Dubbo、gRPC、Thrift、REST）和注册中心集成。</p>
     */
    DUBBO,

    /**
    * 蚂蚁集团 SOFA-RPC (Bolt) 协议
    *
    * <p>蚂蚁集团开源的金融级 RPC 框架，原生采用 Bolt 协议（基于 Netty 的二进制协议），
    * 具备高吞吐、低延迟的特点，经过历年双十一大促流量验证。</p>
     */
    SOFA,

    /**
    * JSON-RPC 2.0 协议
    *
    * <p>基于 JSON 序列化和 HTTP 传输的轻量级 RPC 协议，
    * 最大的优点是语言无关性，任何语言平台都可以消费 JSON-RPC 服务。</p>
     */
    JSON,

    /**
    * 未指定的空值占位
    *
    * <p>用于表示未设置或待填充的协议类型，系统内部使用。</p>
     */
    NONE
}