package com.chua.springboot.support.api.encode;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES 响应编解码器
 * <p>
 * 与前端 wasm 加密协议保持一致：AES-128-CBC / PKCS5Padding，
 * key 为随机生成的 16 字节，IV 为 16 字节全零。
 * </p>
 *
 * @author CH
 */
public class ApiEncodeAesCodec {

    /**
     * 默认 IV（16 字节全零，与前端对齐）
     */
    private static final byte[] DEFAULT_IV = new byte[16];

    /**
     * AES-128-CBC 加密
     *
     * @param data 明文
     * @param key  16 字节密钥
     * @return 密文
     */
    public byte[] encrypt(byte[] data, byte[] key) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec iv = new IvParameterSpec(DEFAULT_IV);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv);
        return cipher.doFinal(data);
    }
}
