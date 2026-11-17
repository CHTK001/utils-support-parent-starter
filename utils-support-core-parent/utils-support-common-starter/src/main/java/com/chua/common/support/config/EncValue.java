package com.chua.common.support.config;

import java.util.Base64;

/**
 * ENC(...) 单值加密格式识别与解包原语（通用底层能力）
 *
 * <p>渠道与业务模块在解密 {@code ENC(...)} 单值时，需要先做格式识别、再剥去
 * {@code ENC(...)} 包裹，二者是纯字符串级原语，与具体解密算法（AES、Crypto 门面等）
 * 无关，故统一下沉到本层，避免各模块各写一份造成重复实现（S0）。</p>
 *
 * <h3>支持的形态</h3>
 * <ul>
 *   <li>{@code ENC(base64EncryptedValue)} — 使用 Base64 校验的加密值</li>
 *   <li>{@code ENC(AES:base64EncryptedValue)} 等带算法标识的形态 — 仅做形状识别，
 *       具体算法由各模块 decryptor 处理</li>
 * </ul>
 *
 * @author CH
 * @since 2026-11-17
 */
public final class EncValue {

    /** ENC 前缀 */
    public static final String PREFIX = "ENC(";

    /** ENC 后缀 */
    public static final String SUFFIX = ")";

    private EncValue() {
    }

    /**
     * 判断值是否为 ENC(...) 形态（仅做前缀/后缀形状识别，不校验内部密文合法性）
     *
     * @param value 配置值
     * @return true 表示已包裹 ENC 加密
     */
    public static boolean isEnc(String value) {
        if (value == null || value.length() <= PREFIX.length() + SUFFIX.length()) {
            return false;
        }
        return value.startsWith(PREFIX) && value.endsWith(SUFFIX);
    }

    /**
     * 判断值是否为合法的 ENC(Base64 密文) 形态（前缀/后缀正确且内部为合法 Base64）
     *
     * @param value 配置值
     * @return true 表示已包裹合法 ENC 加密
     */
    public static boolean isEncrypted(String value) {
        if (!isEnc(value)) {
            return false;
        }
        String inner = value.substring(PREFIX.length(), value.length() - SUFFIX.length()).trim();
        return isBase64(inner);
    }

    /**
     * 判断字符串是否为合法 Base64
     *
     * @param text 待校验文本
     * @return true 表示合法
     */
    private static boolean isBase64(String text) {
        try {
            Base64.getDecoder().decode(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 剥去 ENC(...) 包裹：若为 ENC 形态返回内部密文（已去包裹、去首尾空白），
     * 否则原样返回
     *
     * @param value 配置值
     * @return 内部密文或原值
     */
    public static String unwrap(String value) {
        if (!isEnc(value)) {
            return value;
        }
        return value.substring(PREFIX.length(), value.length() - SUFFIX.length()).trim();
    }
}
