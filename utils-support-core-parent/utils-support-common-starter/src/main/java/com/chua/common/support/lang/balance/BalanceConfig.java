package com.chua.common.support.lang.balance;

import lombok.Builder;
import lombok.Data;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * 负载均衡服务配置。
 * <p>封装负载均衡器所需的连接信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullMarked
@Data
@Builder
public class BalanceConfig {

    /**
     * 主机名
     */
    private @Nullable String host;

    /**
     * 端口号
     */
    private int port;

    /**
     * 用户名
     */
    private @Nullable String user;

    /**
     * 密码
     */
    private @Nullable String password;

    /**
     * 路径前缀
     */
    @Builder.Default
    private String root = "/service";
}
