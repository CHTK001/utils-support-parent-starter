package com.chua.datasource.support.engine;

import com.chua.datasource.support.engine.fixture.FixtureOrderEntity;
import com.chua.datasource.support.engine.fixture.FixtureUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 实体表名解析单元测试。
 *
 * @author CH
 */
@DisplayName("表名解析测试")
class TableNameResolveTest {

    @Test
    @DisplayName("无注解:简单类名驼峰转下划线")
    void testDefaultSnakeCase() {
        assertEquals("fixture_user", AbstractEngine.resolveTableName(FixtureUser.class));
    }

    @Test
    @DisplayName("有 @TableName:优先使用注解值")
    void testAnnotatedTableName() {
        assertEquals("t_order", AbstractEngine.resolveTableName(FixtureOrderEntity.class));
    }

    @Test
    @DisplayName("连续大写按逐字符规则转换")
    void testSimpleLowerCase() {
        // 全小写类名直接返回
        assertEquals("abc", AbstractEngine.resolveTableName(Abc.class));
    }

    /** 全小写命名的测试夹具（定义在测试方法同文件内）。 */
    static class Abc {
    }
}
