package com.chua.common.support.datasearch.location.model;

/**
 * IP 定位信息实体。
 * <p>含 IP 对应的经纬度、行政地址与物理地址（由 GeocodeProvider 补充）。
 * 字段名与 ip-api.com 响应对齐。</p>
 *
 * @param ip IP 地址
 * @param country 国家
 * @param countryCode 国家代码（ISO 3166-1）
 * @param region 省份/州
 * @param city 城市
 * @param zip 邮编
 * @param latitude 纬度
 * @param longitude 经度
 * @param timezone 时区
 * @param isp 运营商
 * @param address 物理地址（完整展示名，由逆地理编
 *                码补充）
 *
 * @author CH
 * @since 4.0.0.42
 */
public record LocationInfo(
        String ip,
        String country,
        String countryCode,
        String region,
        String city,
        String zip,
        Double latitude,
        Double longitude,
        String timezone,
        String isp,
        String address
) {
}
