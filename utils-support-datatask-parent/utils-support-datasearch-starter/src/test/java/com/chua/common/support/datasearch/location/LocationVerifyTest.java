package com.chua.common.support.datasearch.location;

import com.chua.common.support.datasearch.geocode.spi.GeocodeProvider;
import com.chua.common.support.datasearch.location.model.LocationInfo;
import com.chua.common.support.datasearch.location.spi.LocationProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;

/**
 * 定位与物理地址真实数据验证:自身 IP / 指定 IP / 客户端 IP 解析 / 经纬度逆编码。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LocationVerifyTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        // 1) 服务器自身定位(客户端模式)
        LocationProvider lp = ServiceProvider.of(LocationProvider.class).getExtension("ip-api");
        System.out.println("SPI 发现 ip-api: " + (lp != null));
        if (lp != null) {
            LocationInfo self = lp.locateSelf();
            System.out.println("自身: " + (self == null ? "null" : self.getIp() + " " + self.getCountry()
                    + self.getRegion() + self.getCity() + " (" + self.getLatitude() + ", " + self.getLongitude() + ")"));
            // 2) 指定 IP 定位
            LocationInfo ipInfo = lp.locateIp("144.48.80.238");
            System.out.println("指定IP 144.48.80.238: " + (ipInfo == null ? "null"
                    : ipInfo.getCountry() + ipInfo.getRegion() + ipInfo.getCity()
                    + " (" + ipInfo.getLatitude() + ", " + ipInfo.getLongitude() + ") isp=" + ipInfo.getIsp()));
            // 3) 服务端客户端 IP 解析(请求头)
            String clientIp = lp.resolveClientIp(Map.of("X-Forwarded-For", "1.2.3.4, 5.6.7.8",
                    "X-Real-IP", "9.9.9.9"), "8.8.8.8");
            System.out.println("X-Forwarded-For 解析客户端 IP: " + clientIp);
            String clientIp2 = lp.resolveClientIp(Map.of("X-Real-IP", "9.9.9.9"), "8.8.8.8");
            System.out.println("X-Real-IP 解析客户端 IP: " + clientIp2);
        }

        // 4) 经纬度 → 物理地址 + IP → 物理地址
        GeocodeProvider gp = ServiceProvider.of(GeocodeProvider.class).getExtension("bigdatacloud");
        System.out.println("SPI 发现 bigdatacloud: " + (gp != null));
        if (gp != null) {
            String addr = gp.reverseGeocode(39.9, 116.4);
            System.out.println("北京经纬度逆编码: " + addr);
            String ipAddr = gp.ipToAddress("144.48.80.238");
            System.out.println("IP 物理地址: " + ipAddr);
        }
    }
}
