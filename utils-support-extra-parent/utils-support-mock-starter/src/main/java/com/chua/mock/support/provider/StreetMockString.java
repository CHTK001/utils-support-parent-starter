package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 街道 Mock 生成器
*
* <p>从常见中国街道名池中随机返回一个街道名称，如「人民路」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("street")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class StreetMockString implements MockString {

    /**
    * 街道名池
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String[] STREETS = {
            "人民路", "中山路", "解放路", "建设路", "和平路", "新华路", "青年路", "朝阳路",
            "长江路", "黄河路", "文化路", "体育路", "东湖路", "西湖路", "南京路", "北京路",
            "上海路", "高新路", "文华路", "学府路", "迎宾大道", "滨江大道", "世纪大道", "科技路"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(STREETS);
    }
}
