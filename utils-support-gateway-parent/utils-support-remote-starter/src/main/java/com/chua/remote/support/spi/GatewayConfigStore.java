package com.chua.remote.support.spi;

import com.chua.common.support.spi.annotations.Spi;

import java.util.Map;

/**
 *         SPI   
 *
 * <p>                      
 * <ul>
 *   <li>     JSON       </li>
 *   <li>      SQLite   datasource   </li>
 * </ul>
 *
 * @since 4.0.0.41

 * @author CH
 */@Spi
public interface GatewayConfigStore {

    /**
     *     
     *
     * @return         Map         
     */
    Map<String, Object> load();

    /**
     *     
     *
     * @param config      
     */
    void save(Map<String, Object> config);
}

