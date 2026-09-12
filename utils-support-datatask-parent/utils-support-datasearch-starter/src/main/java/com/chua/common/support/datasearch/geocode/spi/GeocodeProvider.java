package com.chua.common.support.datasearch.geocode.spi;

/**
* 物理地址解析提供者 SPI 接口。
*
* <p>提供两类地址解析：</p>
* <ul>
*   <li>经纬度 → 物理地址（逆地理编码，如 Nominatim OpenStreetMap）</li>
*   <li>IP → 物理地址（先经 {@code LocationProvider} 定位到经纬度再逆编码，
*       定位失败时回退到行政信息拼接）</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public interface GeocodeProvider {

    /**
    * 获取数据源名称。
    *
    * @return 数据源名称
     */
    String name();

    /**
    * 经纬度转物理地址（逆地理编码）。
    *
    * @param latitude  纬度
    * @param longitude 经度
    * @return 物理地址（如 "北京市东城区...中国"）；解析失败返回 空
     */
    String reverseGeocode(double latitude, double longitude);

    /**
    * IP 转物理地址。
    *
    * @param ip ipv4 地址
    * @return 物理地址；解析失败返回 空
     */
    String ipToAddress(String ip);
}
