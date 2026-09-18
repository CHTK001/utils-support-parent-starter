package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 英文姓名 Mock 生成器
*
* <p>由常用英文名（First Name）与英文姓氏（Last Name）池组合生成，
* 如「James Smith」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"en-name", "english-name"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class EnglishNameMockString implements MockString {

    /**
    * 英文名池
    */
    private static final String[] FIRST_NAMES = {
            "James", "John", "Robert", "Michael", "William", "David", "Richard", "Joseph",
            "Thomas", "Charles", "Olivia", "Emma", "Ava", "Sophia", "Isabella", "Mia",
            "Amelia", "Harper", "Evelyn", "Abigail"
    };
    /**
    * 英文姓氏池
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String[] LAST_NAMES = {
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
            "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(FIRST_NAMES) + " " + environment.randomOf(LAST_NAMES);
    }
}
