package com.chua.common.support.network.ip;

import lombok.Data;

/**
 * IP 地理位置信息。
 *
 * @author CH
 * @since 1.0.0
 */
@Data
public class IpLocation {

    /** 国家或地区 */
    /** 国家 */
    private String country;

    /** 省份 */
    /** Province */
    private String province;

    /** 城市 */
    /** City */
    private String city;

    /** 运营商 */
    /** ISP */
    private String isp;

    @Override
    public String toString() {
        return String.format("%s%s%s %s",
                country != null ? country : "",
                province != null ? province : "",
                city != null ? city : "",
                isp != null ? isp : "").trim();
    }
}