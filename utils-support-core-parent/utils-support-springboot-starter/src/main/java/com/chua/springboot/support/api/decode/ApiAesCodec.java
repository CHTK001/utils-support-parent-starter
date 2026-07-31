package com.chua.springboot.support.api.decode;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * AES 请求编解码器
 * <p>
 * 与前端 wasm 加密协议保持一致：
 * AES-128-CBC / PKCS5Padding，key 为前端随机生成的 16 字节（x-ck 头携带 base64），
 * IV 为 16 字节全零。
 * </p>
 *
 * @author CH
 */
public class ApiAesCodec {

    /**
     * 默认 IV（16 字节全零，与前端对齐）
     */
    private static final byte[] DEFAULT_IV = new byte[16];

    /**
     * 使用 base64 密钥解密
     *
     * @param data       加密后的密文
     * @param base64Key  base64 编码的 16 字节密钥（x-ck 头值）
     * @return 解密后的明文
     */
    public byte[] decrypt(byte[] data, String base64Key) {
        if (data == null || base64Key == null) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec iv = new IvParameterSpec(DEFAULT_IV);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("AES 请求解密失败: " + e.getMessage(), e);
        }
    }
}
