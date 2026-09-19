package com.chua.common.support.network.rpc;

import lombok.Data;

/**
 * RPC 注册中心配置。
 *
 * <h2>支持的注册中心类型</h2>
 * <table>
 *   <tr><th>协议</th><th>说明</th><th>示例地址</th></tr>
 *   <tr><td>{@code zookeeper}</td><td>Apache ZooKeeper</td><td>{@code 192.168.1.100:2181}</td></tr>
 *   <tr><td>{@code nacos}</td><td>Nacos</td><td>{@code 192.168.1.100:8848}</td></tr>
 *   <tr><td>{@code redis}</td><td>Redis</td><td>{@code redis://192.168.1.100:6379}</td></tr>
 *   <tr><td>{@code multicast}</td><td>组播（开发测试）</td><td>{@code 224.5.6.7:1234}</td></tr>
 *   <tr><td>{@code direct}</td><td>直连模式</td><td>不填或填目标服务地址</td></tr>
 * </table>
 *
 * @author CH
 * @since 1.0.0
 */
@Data
public class RpcRegistryConfig {
    /** 注册中心地址 */
    private String address;
    /** 登录用户名 */
    private String username;
    /** 登录密码 */
    private String password;
    /** 端口 */
    private Integer port;
    /**
     * 注册中心协议类型（zookeeper, nacos, redis, multicast, direct）
     */
    private String protocol;
    /** 连接超时（毫秒） */
    private Integer timeout;
    /** 会话超时（毫秒），ZooKeeper 等依赖心跳的注册中心使用 */
    private Integer sessionTimeout;
    /** 服务分组 */
    private String group;
    /** 服务版本 */
    private String version;
    /** 启动时检查连通性 */
    private Boolean check;
    /** 是否动态注册 */
    private Boolean dynamic;
    /** 是否注册（服务端） */
    private Boolean register;
    /** 是否订阅（客户端） */
    private Boolean subscribe;
    /** 节点权重 */
    private Integer weight;
    /** 扩展参数 */
    private java.util.Map<String, String> parameters;
    /** 本地缓存文件路径（如 dubbo-registry-file） */
    private String file;
}
