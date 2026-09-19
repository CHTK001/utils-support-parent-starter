package com.chua.datasource.support.ddl;

import com.chua.common.support.lang.datasource.engine.ddl.ColumnDef;
import com.chua.common.support.lang.datasource.engine.ddl.DdlProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * DDL 生成器默认实现，不支持任何方言协议。
 *
 * <p>作为 {@code ddl-provider} 扩展点的兜底注册项：{@link #supports(String)}
 * 恒返回 false，引擎查找不到匹配方言的扩展时将使用内置语法生成 DDL。
 * 各数据库 starter 可注册更高 order 的自定义实现（如 MySQL 的
 * {@code character set utf8mb4} 建库语法）来覆盖内置行为。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = DdlProvider.SPI_NAME, order = -1)
public class DefaultDdlProvider implements DdlProvider {

    @Override
    public boolean supports(String protocol) {
        return false;
    }

    @Override
    public String createDatabase(String dbName) {
        throw new UnsupportedOperationException("默认 DDL 生成器不支持建库，请注册对应方言的 DdlProvider 实现");
    }

    @Override
    public String dropDatabase(String dbName) {
        throw new UnsupportedOperationException("默认 DDL 生成器不支持删库，请注册对应方言的 DdlProvider 实现");
    }

    @Override
    public String createTable(String tableName, List<ColumnDef> columns) {
        throw new UnsupportedOperationException("默认 DDL 生成器不支持建表，请注册对应方言的 DdlProvider 实现");
    }
}
