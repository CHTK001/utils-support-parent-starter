package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * UUID Mock 生成器
 *
 * <p>生成标准 32 位十六进制 UUID 字符串（带连字符），通常用于主键或唯一标识的测试数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @param environment 环境
 * @return 获取字符串的结果
 */
@Spi("uuid")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class UuidMockString implements MockString {

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return UUID.randomUUID().toString();
    }
}
