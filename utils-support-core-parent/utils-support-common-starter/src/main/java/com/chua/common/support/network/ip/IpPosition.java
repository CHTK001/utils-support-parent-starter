package com.chua.common.support.network.ip;


/**
 * IP 地理位置查询 SPI 接口。
 *
 * @author CH
 * @since 1.0.0
 */
public interface IpPosition {

    /**
     * 查询指定 IP 的地理位置。
     *
     * @param ip IP 地址
     * @return 地理位置信息，未找到返回 null
     */
    IpLocation query(String ip);

    /**
     * 获取实现类型标识
     *
     * @return 类型标识（如 "geoip2"、"ip2region"）
     */
    default String getType() {
        return this.getClass().getSimpleName();
    }

    /**
     * IP 正向解析：IP → 地理信息
     *
     * @param ip IP 地址（支持 IPv4/IPv6）
     * @return 地理信息，解析失败返回空对象
     */
    default GeoCity getCity(String ip) {
        IpLocation location = query(ip);
        if (location == null) {
            return GeoCity.EMPTY;
        }
        GeoCity city = new GeoCity();
        city.setCountry(location.getCountry());
        city.setProvince(location.getProvince());
        city.setCity(location.getCity());
        city.setIsp(location.getIsp());
        city.setIp(ip);
        return city;
    }

    /**
     * 经纬度反向解析：经纬度 → 地理信息
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @return 地理信息，解析失败返回空对象
     */
    default GeoCity reverseGeocode(double latitude, double longitude) {
        return GeoCity.EMPTY;
    }

    /**
     * 两点距离计算（米）
     *
     * @param lat1 纬度1
     * @param lng1 经度1
     * @param lat2 纬度2
     * @param lng2 经度2
     * @return 距离（米）
     */
    default double distance(double lat1, double lng1, double lat2, double lng2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double a = radLat1 - radLat2;
        double b = Math.toRadians(lng1) - Math.toRadians(lng2);
        double s = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin(a / 2), 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.pow(Math.sin(b / 2), 2)));
        return s * 6378137;
    }

    /**
     * 判断点是否在圆内
     *
     * @param pointLat  点纬度
     * @param pointLng  点经度
     * @param centerLat 圆心纬度
     * @param centerLng 圆心经度
     * @param radiusM   半径（米）
     * @return 是否在圆内
     */
    default boolean inCircle(double pointLat, double pointLng,
                             double centerLat, double centerLng, double radiusM) {
        return distance(pointLat, pointLng, centerLat, centerLng) < radiusM;
    }
}
