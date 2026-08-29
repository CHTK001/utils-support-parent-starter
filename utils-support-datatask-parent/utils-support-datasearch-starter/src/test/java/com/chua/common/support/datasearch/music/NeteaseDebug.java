package com.chua.common.support.datasearch.music;

import com.chua.common.support.datasearch.music.spi.impl.TencentMusicSourceProvider;
import com.chua.common.support.datasearch.music.spi.support.NeteaseCryptoSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 临时调试:网易云 weapi 搜索实际响应。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NeteaseDebug extends TencentMusicSourceProvider {

    /**
     * 调试入口。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("s", "周杰伦");
        payload.put("type", 1);
        payload.put("limit", 5);
        payload.put("offset", 0);
        payload.put("total", true);
        Map<String, String> enc = NeteaseCryptoSupport.weapi(payload);
        try {
            JsonNode root = new NeteaseDebug().postForm(
                    "https://music.163.com/weapi/cloudsearch/get/web?csrf_token=", enc,
                    b -> b.header("Referer", "https://music.163.com/").header("Cookie", "os=pc"));
            String text = root == null ? "null" : root.toString();
            System.out.println("响应长度: " + text.length());
            System.out.println("响应(前800): " + text.substring(0, Math.min(800, text.length())));
        } catch (Exception e) {
            System.out.println("EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
