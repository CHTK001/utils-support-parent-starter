package com.chua.common.support.network.discovery;

import lombok.Data;
import lombok.experimental.Accessors;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务发现配置选项。
 * 用于配置连接远程服务注册中心或发现服务的参数。
 * @author CH
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
     * 是否优先使用接口地址进行发现，默认 false。
     */
    private boolean deferToInterface;

    /**
     * 额外的自定义配置项。
     */
    private Map<String, Object> extra = new LinkedHashMap<>();
}
