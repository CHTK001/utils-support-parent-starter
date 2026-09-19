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

    /**
     * 空对象常量
    */
    public static final GeoCity EMPTY = new GeoCity();

    /**
     * 国家
    */
    private String country;

    /**
     * 省份/地区
    */
    private String province;

    /**
     * 城市
    */
    private String city;

    /**
     * ISP 运营商
    */
    private String isp;

    /**
     * IP 地址
    */
    private String ip;

    /**
     * 纬度
    */
    private Double latitude;

    /**
     * 经度
    */
    private Double longitude;

    /**
     * 邮编
    */
    private String postal;

    /**
     * 时区
    */
    private String timeZone;

    // ==================== getter/setter ====================

    /**
     * 获取Country
    */
    public String getCountry() { return country; }
    /**
     * 设置Country
    */
    public void setCountry(String country) { this.country = country; }

    /**
     * 获取Province
    */
    public String getProvince() { return province; }
    /**
     * 设置Province
    */
    public void setProvince(String province) { this.province = province; }

    /**
     * 获取City
    */
    public String getCity() { return city; }
    /**
     * 设置City
    */
    public void setCity(String city) { this.city = city; }

    /**
     * 获取Isp
    */
    public String getIsp() { return isp; }
    /**
     * 设置Isp
    */
    public void setIsp(String isp) { this.isp = isp; }

    /**
     * 获取Ip
    */
    public String getIp() { return ip; }
    /**
     * 设置Ip
    */
    public void setIp(String ip) { this.ip = ip; }

    /**
     * 获取Latitude
    */
    public Double getLatitude() { return latitude; }
    /**
     * 设置Latitude
    */
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    /**
     * 获取Longitude
    */
    public Double getLongitude() { return longitude; }
    /**
     * 设置Longitude
    */
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    /**
     * 获取Postal
    */
    public String getPostal() { return postal; }
    /**
     * 设置Postal
    */
    public void setPostal(String postal) { this.postal = postal; }

    /**
     * 获取TimeZone
    */
    public String getTimeZone() { return timeZone; }
    /**
     * 设置TimeZone
    */
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
    /**
     * ToString
    */
    public String toString() {
        return getFullAddress();
    }
}
