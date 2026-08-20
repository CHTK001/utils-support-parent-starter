package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 版本号 Mock 生成器
 *
 * <p>生成语义化版本号 {@code 主.次.修订}，如 {@code 3.14.2}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"version", "version-no", "semver"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class VersionMockString implements MockString {

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int major = environment.nextInt(0, 9);
        int minor = environment.nextInt(0, 30);
        int patch = environment.nextInt(0, 100);
        return major + "." + minor + "." + patch;
    }
}