package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * URL Mock 生成器
 *
 * <p>由协议、域名与随机路径组合生成，如
 * {@code https://daxingcloud.com/api/user/123}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"url", "web-url", "link"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class UrlMockString implements MockString {

    /**
     * 协议池
     */
    private static final String[] PROTOCOLS = {"https", "http"};
    /**
     * 路径段池
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final String[] PATHS = {
            "api", "user", "order", "product", "login", "home", "detail", "search",
            "list", "admin", "file", "download", "upload", "callback"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(40);
        builder.append(environment.randomOf(PROTOCOLS)).append("://www.").append(new DomainMockString().getString(environment));
        builder.append('/').append(environment.randomOf(PATHS));
        if (environment.nextInt(2) == 0) {
            builder.append('/').append(environment.nextInt(1, 10000));
        }
        return builder.toString();
    }
}