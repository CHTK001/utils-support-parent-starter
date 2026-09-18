package com.chua.common.support.datasearch.location.spi;

import com.chua.common.support.datasearch.location.model.LocationInfo;

import java.util.Map;

/**
* IP 定位提供者 SPI 接口。
*
* <p>提供两类定位能力：</p>
* <ul>
*   <li>自身作为客户端：{@link #locateSelf()} 返回当前服务器公网 IP 的经纬度与地址</li>
*   <li>作为服务端：业务层从请求头提取客户端 IP 后，经 {@link #resolveClientIp(Map, String)}
*       拿到真实 IP，再调 {@link #locateIp(String)} 获取客户端经纬度与物理地址</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public interface LocationProvider {

    /**
    * 获取数据源名称。
    *
    * @return 数据源名称
    */
    String name();

    /**
    * 定位当前服务器（客户端模式）：返回服务器公网 IP 的定位信息。
    *
    * @return 定位信息；数据源不可达时返回 空
    */
    LocationInfo locateSelf();

    /**
    * 按 IP 定位（服务端模式）：IP 转经纬度 + 城市 + 物理地址。
    *
    * @param ip ipv4 地址
    * @return 定位信息；数据源不可达或 IP 非法时返回 空
    */
    LocationInfo locateIp(String ip);

    /**
    * 从 HTTP 请求头解析客户端真实 IP（服务端模式）。
    *
    * <p>依次尝试 {@code X-Forwarded-For}（取第一个）、{@code X-Real-IP}，
    * 最后回退到 {@code remoteAddr}（Socket 直连地址）。</p>
    *
    * @param headers    请求头（键 大小写不敏感）
    * @param remoteAddr 服务端 Socket 直连地址，可为 空
    * @return 客户端真实 IP；均缺失时返回 空
    */
    default String resolveClientIp(Map<String, String> headers, String remoteAddr) {
        if (headers != null) {
            for (String key : new String[]{"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"}) {
                String value = firstHeader(headers, key);
                if (value != null && !value.isBlank()) {
                    String ip = value.split(",")[0].trim();
                    if (!ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                        return ip;
                    }
                }
            }
        }
        return remoteAddr;
    }

    /**
    * 大小写不敏感地读取首个请求头。
    *
    * @param headers 请求头
    * @param key     头名
    * @return 头值；缺失时返回 空
    */
    private static String firstHeader(Map<String, String> headers, String key) {
        String direct = headers.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
