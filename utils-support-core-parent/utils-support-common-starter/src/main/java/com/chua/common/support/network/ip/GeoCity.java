package com.chua.common.support.network.ip;


/**
 * 地理位置信息
 *
 * <p>存储 IP 定位或经纬度反向解析的结果，包含国家、省份、城市、ISP 等信息。
 *
 * @author CH
 * @since 1.0.0
 */
public class GeoCity {

    /** 空对象常量 */
    /** 是否为空 */
    public static final GeoCity EMPTY = new GeoCity();

    /** 国家 */
    private String country;

    /** 省份/地区 */
    /** Province */
    private String province;

    /** 城市 */
    /** City */
    private String city;

    /** ISP 运营商 */
    /** ISP */
    private String isp;

    /** IP 地址 */
    /** IP */
    private String ip;

    /** 纬度 */
    /** Latitude */
    private Double latitude;

    /** 经度 */
    /** Longitude */
    private Double longitude;

    /** 邮编 */
    /** Postal */
    private String postal;

    /** 时区 */
    private String timeZone;

    // ==================== getter/setter ====================

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getIsp() { return isp; }
    public void setIsp(String isp) { this.isp = isp; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getPostal() { return postal; }
    public void setPostal(String postal) { this.postal = postal; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

    /**
     * 获取完整地址字符串
     *
     * @return 国家+省份+城市
     */
    public String getFullAddress() {
        StringBuilder sb = new StringBuilder();
        if (country != null) {
            sb.append(country);
        }
        if (province != null) {
            sb.append(province);
        }
        if (city != null) {
            sb.append(city);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getFullAddress();
    }
}
