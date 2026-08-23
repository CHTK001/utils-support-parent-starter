package com.chua.geoip2.support;

import com.chua.common.support.network.ip.GeoCity;
import com.chua.common.support.network.ip.IpLocation;
import com.chua.common.support.network.ip.IpPosition;
import com.chua.common.support.spi.annotations.Spi;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Country;
import com.maxmind.geoip2.record.Subdivision;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MaxMind GeoLite2 / GeoIP2 离线 IP 地理位置查询 SPI 实现。
 *
 * <p>基于 GeoLite2-City.mmdb 数据库，支持：国家、省份、城市、经纬度查询。</p>
 * <p>代理检测（VPN/TOR/DataCenter）请使用 {@code utils-support-resource-ip2proxy-starter}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("geoip2")
public class GeoLite2IpPosition implements IpPosition {

    /**
     * 默认数据库文件路径（classpath 资源名）
     */
    private static final String DEFAULT_DB = "GeoLite2-City.mmdb";

    /**
     * GeoLite2 数据库读取器（线程安全，可复用）
     */
    private final DatabaseReader reader;

    /**
     * 使用默认 classpath 路径构造。
     */
    public GeoLite2IpPosition() {
        this(findDefaultResource());
    }

    /**
     * 指定数据库文件路径构造。
     *
     * @param dbPath GeoLite2-City.mmdb 文件路径
     */
    public GeoLite2IpPosition(String dbPath) {
        try {
            this.reader = new DatabaseReader.Builder(Path.of(dbPath).toFile()).build();
        } catch (IOException e) {
            throw new IllegalStateException("初始化 GeoLite2 Reader 失败: " + dbPath, e);
        }
    }

    /**
     * 使用输入流构造（适合从 classpath 或网络加载）。
     *
     * @param mmdbStream GeoLite2-City.mmdb 输入流
     */
    public GeoLite2IpPosition(InputStream mmdbStream) {
        try {
            this.reader = new DatabaseReader.Builder(mmdbStream).build();
        } catch (IOException e) {
            throw new IllegalStateException("初始化 GeoLite2 Reader 失败", e);
        }
    }

    @Override
    /** 查询 */
    public IpLocation query(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            CityResponse response = reader.city(address);
            if (response == null) {
                return null;
            }
            return mapResponse(response);
        } catch (Exception e) {
            log.error("GeoLite2 查询失败: ip={}", ip, e);
            return null;
        }
    }

    /**
     * 经纬度 → 城市信息（GeoLite2 不支持反向地理编码，返回空对象）。
     */
    @Override
    public GeoCity reverseGeocode(double latitude, double longitude) {
        return GeoCity.EMPTY;
    }

    /**
     * 将 GeoLite2 响应映射为 {@link IpLocation}。
     */
    private static IpLocation mapResponse(CityResponse response) {
        IpLocation location = new IpLocation();
        Country country = response.getCountry();
        if (country != null) {
            location.setCountry(country.getNames() != null
                    ? country.getNames().get("zh-CN")
                    : country.getIsoCode());
        }
        Subdivision subdivision = response.getMostSpecificSubdivision();
        if (subdivision != null) {
            location.setProvince(subdivision.getNames() != null
                    ? subdivision.getNames().get("zh-CN")
                    : subdivision.getIsoCode());
        }
        City city = response.getCity();
        if (city != null) {
            location.setCity(city.getNames() != null
                    ? city.getNames().get("zh-CN")
                    : city.getName());
        }
        return location;
    }

    /**
     * 从 classpath 加载默认 GeoLite2-City.mmdb 资源。
     */
    private static String findDefaultResource() {
        URL resource = GeoLite2IpPosition.class.getClassLoader()
                .getResource(DEFAULT_DB);
        if (resource == null) {
            throw new IllegalStateException(
                    "未找到 GeoLite2-City.mmdb，请添加 utils-support-resource-geolite2 依赖");
        }
        try {
            return Path.of(resource.toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("解析 GeoLite2 数据库路径失败", e);
        }
    }
}
