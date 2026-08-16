package com.chua.common.support.datasearch.music.spi.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 网易云音乐加解密支持工具类
 * 提供weapi、linuxapi、eapi三种加密方案，用于网易云音乐API请求参数加密
 * 
 * @author CH
 * @since 4.0.0.42
*/
public final class NeteaseCryptoSupport {

    private static final byte[] IV = "0102030405060708".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PRESET_KEY = "0CoJUm6Qyw8W8jud".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LINUX_API_KEY = "rFgB&h#%2?^eDg:Q".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EAPI_KEY = "e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8);
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n" +
                    "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUr\n" +
                    "X/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK\n" +
                    "9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLb\n" +
                    "CiK45wIDAQAB\n" +
                    "-----END PUBLIC KEY-----";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NeteaseCryptoSupport() {
    }

    public static Map<String, String> weapi(Object payload) {
        byte[] secretKey = randomSecretKey();
        String text = toJson(payload);
        byte[] first = aes("AES/CBC/PKCS5Padding", text.getBytes(StandardCharsets.UTF_8), PRESET_KEY, IV, Cipher.ENCRYPT_MODE);
        byte[] second = aes("AES/CBC/PKCS5Padding",
                Base64.getEncoder().encode(first),
                secretKey,
                IV,
                Cipher.ENCRYPT_MODE);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("params", Base64.getEncoder().encodeToString(second));
        result.put("encSecKey", rsaNoPadding(reverse(secretKey)));
        return result;
    }

    public static Map<String, String> linuxapi(Object payload) {
        String text = toJson(payload);
        byte[] encrypted = aes("AES/ECB/PKCS5Padding",
                text.getBytes(StandardCharsets.UTF_8),
                LINUX_API_KEY,
                null,
                Cipher.ENCRYPT_MODE);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("eparams", toHex(encrypted).toUpperCase());
        return result;
    }

    public static Map<String, String> eapi(String url, Object payload) {
        String text = payload instanceof String ? payload.toString() : toJson(payload);
        String message = "nobody" + url + "use" + text + "md5forencrypt";
        String digest = md5(message);
        String data = url + "-36cd479b6b5-" + text + "-36cd479b6b5-" + digest;
        byte[] encrypted = aes("AES/ECB/PKCS5Padding",
                data.getBytes(StandardCharsets.UTF_8),
                EAPI_KEY,
                null,
                Cipher.ENCRYPT_MODE);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("params", toHex(encrypted).toUpperCase());
        return result;
    }

    private static String toJson(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("序列化网易云音乐请求体失败", e);
        }
    }

    private static byte[] randomSecretKey() {
        byte[] secret = new byte[16];
        for (int index = 0; index < secret.length; index++) {
            secret[index] = (byte) BASE62.charAt(ThreadLocalRandom.current().nextInt(BASE62.length()));
        }
        return secret;
    }

    private static String rsaNoPadding(byte[] text) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            String normalized = PUBLIC_KEY_PEM
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\n", "");
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(normalized))
            );
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] padded = new byte[128];
            System.arraycopy(text, 0, padded, 128 - text.length, text.length);
            return toHex(cipher.doFinal(padded));
        } catch (Exception e) {
            throw new IllegalStateException("网易云音乐 encSecKey 生成失败", e);
        }
    }

    private static byte[] aes(String transformation, byte[] content, byte[] key, byte[] iv, int mode) {
        try {
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            if (iv != null) {
                cipher.init(mode, keySpec, new IvParameterSpec(iv));
            } else {
                cipher.init(mode, keySpec);
            }
            return cipher.doFinal(content);
        } catch (Exception e) {
            throw new IllegalStateException("网易云音乐 AES 加密失败", e);
        }
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return toHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("网易云音乐 MD5 生成失败", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static byte[] reverse(byte[] value) {
        byte[] result = value.clone();
        for (int left = 0, right = result.length - 1; left < right; left++, right--) {
            byte temp = result[left];
            result[left] = result[right];
            result[right] = temp;
        }
        return result;
    }
}


