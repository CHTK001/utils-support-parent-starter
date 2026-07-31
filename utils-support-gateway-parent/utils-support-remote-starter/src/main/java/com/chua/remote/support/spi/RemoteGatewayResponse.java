/**
 *          
 *
 * <p>                                
 * WebSocket       ID     </p>
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
public class RemoteGatewayResponse {

    /**
     * 提供方标识
     */
    private String provider;

    /**
     * 是否启用
     */
    private Boolean enabled;

    private String protocol;

    private String gatewayUrl;

    private String websocketUrl;

    private String launchUrl;

    private String connectionId;

    /**
     * 提示消息
     */
    private String message;

    private Map<String, String> parameters;
}

