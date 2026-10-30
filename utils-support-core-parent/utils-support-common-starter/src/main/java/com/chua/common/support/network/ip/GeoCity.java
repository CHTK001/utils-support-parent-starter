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
     * @return 返回值
    */
    public String getCountry() {
        return country;
    }
    /**
     * 设置Country
     * @param country 字符串
     * @return 返回值
    */
    public void setCountry(String country) {
        this.country = country;
    }

    /**
     * 获取Province
     * @return 返回值
    */
    public String getProvince() {
        return province;
    }
    /**
     * 设置Province
     * @param province 字符串
     * @return 返回值
    */
    public void setProvince(String province) {
        this.province = province;
    }

    /**
     * 获取City
     * @return 返回值
    */
    public String getCity() {
        return city;
    }
    /**
     * 设置City
     * @param city 字符串
     * @return 返回值
    */
    public void setCity(String city) {
        this.city = city;
    }

    /**
     * 获取Isp
     * @return 返回值
    */
    public String getIsp() {
        return isp;
    }
    /**
     * 设置Isp
     * @param isp 字符串
     * @return 返回值
    */
    public void setIsp(String isp) {
        this.isp = isp;
    }

    /**
     * 获取Ip
     * @return 返回值
    */
    public String getIp() {
        return ip;
    }
    /**
     * 设置Ip
     * @param ip 字符串
     * @return 返回值
    */
    public void setIp(String ip) {
        this.ip = ip;
    }

    /**
     * 获取Latitude
     * @return 返回值
    */
    public Double getLatitude() {
        return latitude;
    }
    /**
     * 设置Latitude
     * @param latitude 双精度数
     * @return 返回值
    */
    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    /**
     * 获取Longitude
     * @return 返回值
    */
    public Double getLongitude() {
        return longitude;
    }
    /**
     * 设置Longitude
     * @param longitude 双精度数
     * @return 返回值
    */
    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    /**
     * 获取Postal
     * @return 返回值
    */
    public String getPostal() {
        return postal;
    }
    /**
     * 设置Postal
     * @param postal 字符串
     * @return 返回值
    */
    public void setPostal(String postal) {
        this.postal = postal;
    }

    /**
     * 获取TimeZone
     * @return 返回值
    */
    public String getTimeZone() {
        return timeZone;
    }
    /**
     * 设置TimeZone
     * @param timeZone 字符串
     * @return 返回值
    */
    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

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

    /**
     * ToString
     */
    @Override
    public String toString() {
        return getFullAddress();
    }
}
