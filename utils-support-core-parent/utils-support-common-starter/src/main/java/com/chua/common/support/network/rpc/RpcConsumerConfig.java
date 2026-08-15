package com.chua.common.support.network.rpc;

import lombok.Data;

/**
 * RPC 消费者全局配置，定义客户端侧的核心调用行为参数。
 *
 * <h2>配置项说明</h2>
 * <table>
 *   <tr><th>配置项</th><th>作用</th><th>默认值</th></tr>
 *   <tr><td>{@code check}</td><td>启动时是否检查服务端可用</td><td>{@code true}</td></tr>
 *   <tr><td>{@code timeout}</td><td>远程调用超时（毫秒）</td><td>{@code 1000}</td></tr>
 *   <tr><td>{@code retries}</td><td>失败最大重试次数</td><td>{@code 2}</td></tr>
 *   <tr><td>{@code loadBalance}</td><td>负载均衡策略</td><td>{@code random}</td></tr>
 *   <tr><td>{@code async}</td><td>是否异步调用</td><td>{@code false}</td></tr>
 * </table>
 *
 * @author CH
 * @since 1.0.0
 */
@Data
public class RpcConsumerConfig {
    /** 启动时检查 */
    private Boolean check;
    /** 超时（毫秒） */
    /**
     * 超时时间（毫秒）
     */
    private Integer timeout;
    /** 重试次数 */
    /**
     * 重试次数
     */
    private Integer retries;
    /** 负载均衡策略（random, roundrobin, leastactive, consistenthash, shortestresponse） */
    private String loadBalance;
    /** 是否异步 */
    /**
     * 是否异步执行
     */
    private Boolean async;
    /** 版本 */
    /**
     * 版本号
     */
    private String version;
    /** 分组 */
    /**
     * 用户组
     */
    private String group;
    /** 最大连接数 */
    private Integer connections;
    /** 集群策略（failover, failfast, failsafe, failback, forking, broadcast） */
    private String cluster;
    /** 粘性连接 */
    private Boolean sticky;
    /** 序列化协议 */
    private String serialization;
    /** 连接超时（毫秒） */
    /**
     * 连接超时时间（毫秒）
     */
    private Integer connectTimeout;
    /** 是否启用重试 */
    private Boolean retryEnabled;
    /** 重试间隔（毫秒） */
    private Integer retryDelay;
    /** 是否开启访问日志 */
    private Boolean accessLog;
    /** 是否延迟检查 */
    private Boolean lazy;
    /** 直连 URL */
    /**
     * 地址
     */
    private String url;
    /** 调用模式（sync, async, future, callback, oneway） */
    private String invokeType;
    /** 服务接口类 */
    private Class<?> interfaceClass;
    /** 服务接口名 */
    private String interfaceName;
    /** 服务治理令牌（请求头 X-RPC-Token），服务端 {@code RpcService#token()} 校验时必填 */
    /**
     * 令牌
     */
    private String token;
}