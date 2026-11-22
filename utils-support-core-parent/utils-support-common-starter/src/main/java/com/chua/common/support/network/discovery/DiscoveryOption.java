package com.chua.common.support.network.discovery;

import com.chua.common.support.collection.wrap.Wraps;
import lombok.Data;
import lombok.experimental.Accessors;
import java.util.Map;

/**
 * 服务发现配置选项。
 * 用于配置连接远程服务注册中心或发现服务的参数。
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Accessors(chain = true)
public class DiscoveryOption {

    /**
     * 服务地址，例如 ZooKeeper 的 connect string。
     */
    private String address;

    /**
     * 命名空间根路径，默认为 "discovery"。
     */
    private String root = "discovery";

    /**
     * 认证用户名（可选）。
     */
    private String user;

    /**
     * 认证密码（可选）。
     */
    private String password;

    /**
     * 数据库名称（特定于某些存储后端时）。
     */
    private String database;

    /**
     * 连接超时时间（毫秒），默认 10 秒。
     */
    private int connectionTimeoutMillis = 10_000;

    /**
     * 会话超时时间（毫秒），默认 10 秒。
     */
    private int sessionTimeoutMillis = 10_000;

    /**
     * 注册中心客户端与注册中心的对账间隔（毫秒）。
     *
     * <p>仅 {@code registry-client} 使用：每隔该时长向注册中心重取一次全量快照，
     * 并重申本实例名下的全部注册，用于自愈「注册中心重启 / 增量事件丢失 /
     * 上行消息发送失败」造成的视图漂移。默认 30 秒，最小 5 秒。</p>
     */
    private long reconcileIntervalMillis = 30_000L;

    /**
     * 是否优先使用接口地址进行发现，默认 false。
     */
    private boolean deferToInterface;

    /**
     * 额外的自定义配置项。
     */
    private Map<String, Object> extra = Wraps.linkedMap();
}
