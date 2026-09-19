package com.chua.common.support.datasearch.music.spi.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 咪咕音乐签名支持工具类
 * 为咪咕音乐API请求生成签名（标志）与设备标识（deviceid）等请求参数
 * 
 * @author CH
 * @since 4.0.0.42
 */
public final class MiguSignSupport {

    /** Device_标识 */
    private static final String DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20";
    /** 签名_md5 */
    private static final String SIGNATURE_MD5 = "6cdc72a439cef99a3418d2a78aa28c73";

    /** 创建 migu标志支持 实例 */
    private MiguSignSupport() {
    }

    /**
     * 头部
     *
     * @param keyword keyword
     * @param timestamp 时间戳
     * @return 头部的结果
     */
    public static Map<String, String> headers(String keyword, String timestamp) {
        String sign = md5(keyword + SIGNATURE_MD5 + "yyapp2d16148780a1dcc7408e06336b98cfd50" + DEVICE_ID + timestamp);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("uiVersion", "A_music_3.6.1");
        headers.put("deviceId", DEVICE_ID);
        headers.put("timestamp", timestamp);
        headers.put("sign", sign);
        headers.put("channel", "0146921");
        return headers;
    }

    /**
     * Md
     *
     * @param text 文本
     * @return md5的结果
     */
    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("咪咕签名失败", e);
        }
    }
}


