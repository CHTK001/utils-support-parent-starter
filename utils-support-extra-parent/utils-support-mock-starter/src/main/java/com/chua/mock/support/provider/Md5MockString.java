package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5 Mock 生成器
 *
 * <p>对随机内容计算 MD5，生成 32 位小写十六进制摘要，
 * 如 {@code d41d8cd98f00b204e9800998ecf8427e}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"md5", "md5-hex", "digest"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class Md5MockString implements MockString {

    /**
     * 随机内容长度
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final int CONTENT_LENGTH = 16;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        byte[] content = new byte[CONTENT_LENGTH];
        environment.random().nextBytes(content);
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return hex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 MD5 算法", e);
        }
    }

    /**
     * 将字节数组转为小写十六进制字符串。
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b & 0xFF));
        }
        return builder.toString();
    }
}