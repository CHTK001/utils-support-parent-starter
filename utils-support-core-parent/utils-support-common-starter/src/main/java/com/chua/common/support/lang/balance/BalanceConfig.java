package com.chua.common.support.lang.balance;

import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

/**
 * @author CH
 */
@NullUnmarked
@Data
@Builder
public class BalanceConfig {
    /**
     * 主机名
     */
    private String host;
    /**
     * 端口号
     */
    private int port;
    /**
     * 用户
     */
    private String user;
    /**
     * 密码
     */
    private String password;
    @Builder.Default
    private String root = "/service";
}
