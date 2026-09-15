package com.chua.common.support.datasearch.example;

import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;

/**
 * 探测 SubHD API 原始响应。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ProbeSubhdApi {

    /** 防止工具类实例化 */
    private ProbeSubhdApi() {
    }

    /**
     * 测试入口。
     *
     * @param args 无
     */
    public static void main(String[] args) {
        try {
            ClientResponse resp = HttpClientFactory.of("https://subhd.tv/api/search")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Referer", "https://subhd.tv/")
                    .header("Accept", "application/json")
                    .connectTimeout(15000)
                    .readTimeout(15000)
                    .query("keyword", "流浪地球")
                    .query("page", "1")
                    .get();
            String body = resp.getBodyString();
            System.out.println("status=" + resp.getStatusCode());
            System.out.println("body is null: " + (body == null));
            if (body != null) {
                System.out.println("len=" + body.length());
                System.out.println("head: " + body.substring(0, Math.min(500, body.length())));
            }
        } catch (Exception e) {
            System.out.println("EXCEPTION: " + e);
        }
    }
}
