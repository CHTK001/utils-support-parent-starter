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
    private String country;

    /** 省份 */
    private String province;

    /** 城市 */
    private String city;

    /** 运营商 */
    private String isp;

    @Override
    /** ToString */
    public String toString() {
        return String.format("%s%s%s %s",
                country != null ? country : "",
                province != null ? province : "",
                city != null ? city : "",
                isp != null ? isp : "").trim();
    }
}