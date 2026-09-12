package com.chua.common.support.datasearch.location.model;

import lombok.Data;

/**
* IP 定位信息实体。
*
* <p>含 IP 对应的经纬度、行政地址与物理地址（由 GeocodeProvider 补充）。
* 字段名与 ip-api.com 响应对齐。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class LocationInfo {

    /** IP 地址 */
    private String ip;

    /** 国家 */
    private String country;

    /** 国家代码（ISO 3166-1） */
    private String countryCode;

    /** 省份/州 */
    private String region;

    /** 城市 */
    private String city;

    /** 邮编 */
    private String zip;

    /** 纬度 */
    private Double latitude;

    /** 经度 */
    private Double longitude;

    /** 时区 */
    private String timezone;

    /** 运营商 */
    private String isp;

    /** 物理地址（完整展示名，由逆地理编码补充） */
    private String address;
}
