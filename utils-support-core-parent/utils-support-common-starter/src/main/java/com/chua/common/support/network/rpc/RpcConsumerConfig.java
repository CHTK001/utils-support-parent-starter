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
 *   <tr><td>{@code auto}</td><td>是否按当前系统自动调优</td><td>{@code true}</td></tr>
 * </table>
 *
 * @author CH
 * @since 1.0.0
 */
@Data
public class RpcConsumerConfig {

    /**
     * 创建一份按当前系统自动调优的消费者配置。
     *
     * <p>基于 CPU 核数与 JVM 可用堆内存自动调整超时、连接数、连接超时、
     * 重试间隔等关键参数（等效于 {@code autoConfig()}）。</p>
     *
     * @return 自动配置实例
     */
    public static RpcConsumerConfig auto() {
        return new RpcConsumerConfig().autoConfig();
    }

    /**
     * 是否启用按当前系统自动调优。
     *
     * <p>开启后，客户端创建前会基于 CPU 核数与 JVM 可用堆内存自动调整
     * 超时、连接数、连接超时、重试间隔等参数，使消费者在各环境中都能获得
     * 较优的默认表现。默认开启，可设为 {@code false} 手动指定。</p>
     */
    private boolean auto = true;

    /**
     * 按当前系统自动调优关键参数。
     *
     * <p>基于 CPU 核数 {@code cpus} 与 JVM 可用堆内存 {@code heapMb}：</p>
     * <ul>
     *   <li>调用超时：核数越多放大（预留 GC 与调度开销），范围 3000-15000ms</li>
     *   <li>连接超时：取调用超时的 {@code 1/3}</li>
     *   <li>连接数：核数 × 4，最少 4</li>
     *   <li>重试间隔：堆内存充足时收敛，避免大堆频繁重试放大压力</li>
     * </ul>
     *
     * @return 当前配置实例
     */
    public RpcConsumerConfig autoConfig() {
        int cpus = Runtime.getRuntime().availableProcessors();
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        this.timeout = Math.min(Math.max(cpus * 1500, 3000), 15000);
        this.connectTimeout = Math.max(this.timeout / 3, 1000);
        this.connections = Math.max(cpus * 4, 4);
        this.retryDelay = heapMb >= 4096 ? 200 : 500;

        return this;
    }

    /** 启动时检查 */
    /** Check */
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
    /** Loadbalance */
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
    /** Connections */
    private Integer connections;
    /** 集群策略（failover, failfast, failsafe, failback, forking, broadcast） */
    /** Cluster */
    private String cluster;
    /** 粘性连接 */
    /** Sticky */
    private Boolean sticky;
    /** 序列化协议 */
    /** Serialization */
    private String serialization;
    /** 连接超时（毫秒） */
    /**
     * 连接超时时间（毫秒）
     */
    private Integer connectTimeout;
    /** 是否启用重试 */
    /** 重试是否启用 */
    private Boolean retryEnabled;
    /** 重试间隔（毫秒） */
    /** 重试delay */
    private Integer retryDelay;
    /** 是否开启访问日志 */
    /** Access日志 */
    private Boolean accessLog;
    /** 是否延迟检查 */
    /** Lazy */
    private Boolean lazy;
    /** 直连 URL */
    /**
     * 地址
     */
    private String url;
    /** 调用模式（sync, async, future, callback, oneway） */
    /** Invoke类型 */
    private String invokeType;
    /** 服务接口类 */
    private Class<?> interfaceClass;
    /** 服务接口名 */
    /** 接口名称 */
    private String interfaceName;
    /** 服务治理令牌（请求头 X-RPC-Token），服务端 {@code RpcService#token()} 校验时必填 */
    /**
     * 令牌
     */
    private String token;
}