package com.chua.remote.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 *        SPI   
 *
 * <p>                       
 * <ul>
 *   <li>            </li>
 *   <li>        /          </li>
 *   <li>             </li>
 * </ul>
 *
 * <p>                      SPI          
 *
 * @since 4.0.0.41

 * @author CH
 */@Spi
public interface GatewayTokenVerifier {

    /**
     *        
     *
     * @param token   
     * @return        TokenAuth                 null
     */
    @Nullable
    TokenAuth authenticate(String token);

    /**
     *                
     *
     * @return token   TokenAuth    
     */
    Map<String, TokenAuth> listTokens();

    /**
     *      
     *
     * @param auth      token         
     * @return         
     */
    String createToken(TokenAuth auth);

    /**
     *     
     *
     * @param token   
     * @param updates         displayName, accessibleAgentIds, expiresAt 
     * @return true       
     */
    default boolean editToken(String token, java.util.Map<String, Object> updates) { return false; }

    /**
     *     
     *
     * @param token   
     * @return true       
     */
    boolean deleteToken(String token);

    /**
     *       
     */
    interface TokenAuth {

        /**     */
        String getToken();

        /**   /     */
        String getUserId();

        /**        */
        String getDisplayName();

        /**         Agent ID    null           */
        @Nullable
        List<String> getAccessibleAgentIds();

        /**         Target ID    null           */
        @Nullable
        List<String> getAccessibleTargetIds();

        /**
         *        null       
         * @return      ISO    
         */
        @Nullable
        default String getExpiresAt() { return null; }

        /**
         *            Agent
         * @param agentId Agent ID
         * @return true             
         */
        default boolean canAccessAgent(String agentId) {
            List<String> ids = getAccessibleAgentIds();
            return ids == null || ids.isEmpty() || ids.contains(agentId);
        }

        /**
         *            Target
         * @param targetId Target ID
         * @return true             
         */
        default boolean canAccessTarget(String targetId) {
            List<String> ids = getAccessibleTargetIds();
            return ids == null || ids.isEmpty() || ids.contains(targetId);
        }
    }
}

