package com.chua.remote.support.spi;

import com.chua.common.support.spi.annotations.Spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *       SPI   
 *
 * <p>   IP                    
 *         +          SPI               
 *
 * @since 4.0.0.42

 * @author CH
 */@Spi
public interface GatewayFirewallProvider {

    // =====       =====

    /**
     *           
     * @param clientIp     IP
     * @param path     
     * @return null        null      
     */
    String checkRequest(String clientIp, String path);

    /**
     *       
     * @param clientIp     IP
     * @param path     
     * @param method HTTP   
     * @param statusCode      
     * @param responseTimeMs     (ms)
     */
    void recordAccess(String clientIp, String path, String method, int statusCode, long responseTimeMs);

    // ===== IP    =====

    /**         : DISABLED, BLACKLIST, WHITELIST */
    String getIpFilterMode();

    /**        */
    void setIpFilterMode(String mode);

    /**    IP */
    void blockIp(String ip, String reason);

    /**    IP */
    boolean unblockIp(String ip);

    /**        */
    void allowIp(String ip);

    /**        */
    boolean removeAllowedIp(String ip);

    /**        : [{ip, reason, blockedAt}] */
    List<Map<String, Object>> getBlockedList();

    /**         */
    List<String> getAllowedList();

    // =====    =====

    /**        */
    boolean isRateLimitEnabled();

    /**   /     */
    void setRateLimitEnabled(boolean enabled);

    // =====      =====

    /**       : {totalRequests, topIps: [{ip, count}], topPaths: [{path, count}]} */
    Map<String, Object> getAccessStats();

    /**          */
    List<Map<String, Object>> getAccessLogs(String ipFilter, int count);

    /**        */
    void clearAccessLogs();

    // =====      =====

    /**           */
    Map<String, Object> getStatus();

    // =====      =====

    /**
     *      QPS                Top IP   
     *          
     */
    default Map<String, Object> getRealtimeMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currentQps", 0);
        m.put("avgQps", 0);
        m.put("peakQps", 0);
        m.put("totalRequests", 0L);
        m.put("avgResponseTimeMs", 0L);
        m.put("statusCodeDistribution", Map.of());
        m.put("qpsTimeSeries", List.of());
        m.put("topIpsRecent", List.of());
        return m;
    }
}

