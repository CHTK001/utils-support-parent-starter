package com.chua.remote.support.spi;

import com.chua.common.support.spi.annotations.Spi;

/**
 *        SPI
 * <p>
 *               TCP UDP    
 *    TCP   BaseRemoteAgent           default    false 
 *   transport    "TCP"        SPI     Agent     I/O      SPI 
 * </p>
 *
 * <p>
 * TCP              SPI     SPI    false       TCP      
 * </p>
 *
 * @author CH
 * @since 4.0.0.41
 */
@Spi("remote-transport")
public interface RemoteTransportProvider {

    /**
     * @return          "TCP" "UDP" 
     */
    String transport();

    /**
     *         Gateway
     *
     * @param gatewayHost Gateway   
     * @param gatewayPort Gateway   
     * @param options         
     * @return true        false           TCP    
     * @throws Exception      
     */
    default boolean connect(String gatewayHost, int gatewayPort,
                            java.util.Map<String, Object> options) throws Exception {
        return false;
    }

    /**
     *         Gateway
     *
     * @param message JSON     
     * @return true       false           TCP      
     */
    default boolean sendText(String message) {
        return false;
    }

    /**
     *              Gateway
     *
     * @param frameType      0xDF=H264, 0xDE=JPEG 
     * @param sessionId     ID
     * @param width        
     * @param height       
     * @param keyFrame        
     * @param data           
     * @return true       false           TCP      
     */
    default boolean sendBinaryFrame(byte frameType, String sessionId,
                                    int width, int height, boolean keyFrame, byte[] data) {
        return false;
    }

    /**
     * @return true         
     */
    default boolean isActive() {
        return false;
    }

    /**
     *       
     */
    default void close() {
    }
}

