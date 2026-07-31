/**
 *          
 *
 * <p>                                 
 *                </p>
 *
 * @author CH
 * @since 1.0
 */
package com.chua.remote.support.spi;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteGatewayRequest {

    /**
     * 提供方标识
     */
    private String provider;

    /**
     * 是否启用
     */
    private Boolean enabled;

    private Integer serverId;

    private String serverType;

    private String osType;

    /**
     * 主机名
     */
    private String host;

    /**
     * 端口号
     */
    private Integer port;

    private String gatewayUrl;

    private String protocol;

    private String launchPath;

    private String websocketPath;

    private String connectionId;

    private Map<String, Object> metadata;
}

