package com.chua.crypto.support.config;

import com.chua.crypto.support.Crypto;
import com.chua.crypto.support.CryptoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * 配置文件加解密器
 *
 * <p>支持两种粒度：
 *
 * <h2>1. 整文件加密</h2>
 * 加密后文件首行为固定标记行，正文为 基础64 密文：
 * <pre>
 * #!CHKF-CONFIG:1
 * hR2Pf...（Base64）
 * </pre>
 * 适用于 application.yml / application.属性 等整体敏感的配置；
 * springboot 环境下由 环境post处理器 在启动期透明解密装载。
 *
 * <h2>2. 单值加密（ENC(...) 包裹）</h2>
 * 配置中的敏感值可单独加密为 {@code ENC(Base64密文)}，其余配置保持明文可读，
 * 例如：{@code spring.datasource.password=ENC(hR2Pf...)}
 *
 * @author CH
 * @since 2026-08-26
 */
public final class ConfigFileCipher {

    /**
     * 整文件加密标记行
     */
    public static final String MARKER = "#!CHKF-CONFIG:1";

    /**
     * 单值加密前缀
     */
    public static final String VALUE_PREFIX = "ENC(";

    /**
     * 单值加密后缀
     */
    public static final String VALUE_SUFFIX = ")";

    /**
     * 私有构造
     */
    private ConfigFileCipher() {
    }

    /**
     * 判断配置文件是否已整文件加密
     *
     * @param file 配置文件
     * @return true 表示首行为加密标记
     */
    public static boolean isEncrypted(Path file) {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            byte[] head = new byte[MARKER.length()];
            try (var in = Files.newInputStream(file)) {
                int read = in.readNBytes(head, 0, head.length);
                if (read < head.length) {
                    return false;
                }
            }
            return MARKER.equals(new String(head, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 整文件加密（UTF-8 文本文件）。原文件按需备份为 *.bak，原位置写入密文。
     *
     * @param file      明文配置文件
     * @param crypto    已初始化的加密门面
     * @param keepBackup 是否保留明文备份(*.bak)
     * @return 写入的密文文件路径
     */
    public static Path encryptFile(Path file, Crypto crypto, boolean keepBackup) {
        requireInitialized(crypto);
        try {
            String plain = Files.readString(file, StandardCharsets.UTF_8);
            if (keepBackup) {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            String cipherText = MARKER + System.lineSeparator()
                    + crypto.encryptToString(plain) + System.lineSeparator();
            Files.writeString(file, cipherText, StandardCharsets.UTF_8);
            return file;
        } catch (CryptoException e) {
            throw e;
        } catch (IOException e) {
            throw new CryptoException("配置文件加密失败: " + file, e);
        }
    }

    /**
     * 解密整文件加密的配置，返回明文内容（不改动磁盘文件）
     *
     * @param file   密文配置文件
     * @param crypto 已初始化的加密门面
     * @return 明文内容
     */
    public static String decryptFile(Path file, Crypto crypto) {
        requireInitialized(crypto);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!content.startsWith(MARKER)) {
                // 未加密文件原样返回
                return content;
            }
            String body = content.substring(MARKER.length()).trim();
            return crypto.decryptToString(body);
        } catch (CryptoException e) {
            throw e;
        } catch (IOException e) {
            throw new CryptoException("配置文件读取失败: " + file, e);
        }
    }

    /**
     * 解密并将明文写出到目标路径
     *
     * @param source 密文配置文件
     * @param target 明文输出路径
     * @param crypto 已初始化的加密门面
     * @return 输出路径
     */
    public static Path decryptToFile(Path source, Path target, Crypto crypto) {
        try {
            Files.writeString(target, decryptFile(source, crypto), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new CryptoException("配置明文写出失败: " + target, e);
        }
    }

    /**
     * 加密单个配置值并包裹 ENC(...)
     *
     * @param value  明文值
     * @param crypto 已初始化的加密门面
     * @return ENC(Base64密文)
     */
    public static String encryptValue(String value, Crypto crypto) {
        requireInitialized(crypto);
        return VALUE_PREFIX + crypto.encryptToString(value) + VALUE_SUFFIX;
    }

    /**
     * 解密单个配置值：形如 {@code ENC(...)} 时解密返回明文，否则原样返回
     *
     * @param value  配置原始值
     * @param crypto 已初始化的加密门面
     * @return 明文值或原值
     */
    public static String decryptValue(String value, Crypto crypto) {
        if (!isEncryptedValue(value)) {
            return value;
        }
        requireInitialized(crypto);
        String body = value.substring(VALUE_PREFIX.length(), value.length() - VALUE_SUFFIX.length()).trim();
        return crypto.decryptToString(body);
    }

    /**
     * 判断值是否为 ENC(...) 形态
     *
     * @param value 配置值
     * @return true 表示已包裹加密
     */
    public static boolean isEncryptedValue(String value) {
        if (value == null || value.length() <= VALUE_PREFIX.length() + VALUE_SUFFIX.length()) {
            return false;
        }
        return value.startsWith(VALUE_PREFIX) && value.endsWith(VALUE_SUFFIX)
                && isBase64(value.substring(VALUE_PREFIX.length(), value.length() - VALUE_SUFFIX.length()));
    }

    /**
     * 校验字符串是否为合法 基础64
     *
     * @param text 待校验文本
     * @return true 表示合法
     */
    private static boolean isBase64(String text) {
        try {
            Base64.getDecoder().decode(text.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 校验加密门面已初始化
     *
     * @param crypto 加密门面
     */
    private static void requireInitialized(Crypto crypto) {
        if (crypto == null || !crypto.isInitialized()) {
            throw new CryptoException("加密门面尚未初始化，请先调用 initialize()/build()");
        }
    }
}
