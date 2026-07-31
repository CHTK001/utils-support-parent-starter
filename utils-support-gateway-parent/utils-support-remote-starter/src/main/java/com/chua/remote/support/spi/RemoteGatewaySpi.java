/**
 *      SPI    
 *
 * <p>          SPI       {@link Spi}              
 *       {@link #getProvider()}         {@link #build(RemoteGatewayRequest)}
 *            {@link #supports(String)}              </p>
 *
 * @author CH
 * @since 1.0
 */
package com.chua.remote.support.spi;

import com.chua.common.support.spi.annotations.Spi;

@Spi
public interface RemoteGatewaySpi {

    String getProvider();

    default boolean supports(String provider) {
        return getProvider().equalsIgnoreCase(String.valueOf(provider));
    }

    RemoteGatewayResponse build(RemoteGatewayRequest request);
}

