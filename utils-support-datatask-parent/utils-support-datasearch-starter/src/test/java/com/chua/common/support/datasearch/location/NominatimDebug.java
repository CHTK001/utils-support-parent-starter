package com.chua.common.support.datasearch.location;

import com.chua.common.support.network.client.HttpClientFactory;

/**
 * 临时调试:HttpClientFactory 请求 Nominatim 的行为。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NominatimDebug {

    /**
     * 调试入口。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        try {
            String body = HttpClientFactory.of(
                    "https://nominatim.openstreetmap.org/reverse?lat=39.9&lon=116.4&format=jsonv2")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126")
                    .get().getBodyString();
            System.out.println("BODY: " + (body == null ? "null" : body.substring(0, Math.min(300, body.length()))));
        } catch (Exception e) {
            System.out.println("EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
