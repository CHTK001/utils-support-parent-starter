package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 域名 Mock 生成器
 *
 * <p>由拼音风格单词与顶级域名池组合生成，如 {@code daxingcloud.com}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"domain", "domain-name"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class DomainMockString implements MockString {

    /**
     * 名称词池
     */
    private static final String[] WORDS = {
            "baidu", "ali", "tencent", "huawei", "xiaomi", "meituan", "didi", "bytedance",
            "jingdong", "pinduoduo", "netease", "sina", "zhihu", "bilibili", "daxing",
            "cloud", "tech", "smart", "nova", "apex", "flux", "zenith"
    };
    /**
     * 顶级域名池
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final String[] TLDS = {"com", "cn", "net", "org", "io", "xyz", "top", "tech", "cloud", "online"};

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(WORDS) + "." + environment.randomOf(TLDS);
    }
}
