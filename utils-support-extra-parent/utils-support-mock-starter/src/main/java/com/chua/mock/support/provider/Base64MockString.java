package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.util.Base64;

/**
 * Base64 Mock 生成器
 *
 * <p>对随机字节做 Base64 编码生成字符串，
 * 如 {@code 0qz7mH2K4nQ9lT0o+X3sJw==}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"base64", "base64-encode"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class Base64MockString implements MockString {

    /**
     * 随机内容长度
     */
    private static final int CONTENT_LENGTH = 16;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        byte[] content = new byte[CONTENT_LENGTH];
        environment.random().nextBytes(content);
        return Base64.getEncoder().encodeToString(content);
    }
}