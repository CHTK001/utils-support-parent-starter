package com.chua.common.support.lang.datasource.engine.ddl;

import com.chua.common.support.lang.datasource.dialect.Dialect;

/**
 * 方言感知接口，用于向 DDL 相关实现注入当前数据库方言。
 *
 * <p>与 {@link com.chua.datasource.support.user.DataSourceAware} 配套：
 * {@code AbstractEngine#ddl()} 解析出 DDL 管理器后，会依次向实现了本接口的实现
 * 注入默认数据源的方言，实现方因此无需自行从 JDBC URL 嗅探方言
 * （嗅探需要遍历全部已注册方言并逐个比对 URL 前缀，既慢又会在方言 URL 模板
 * 变化时静默失配）。</p>
 *
 * <p>方言缺失的引擎（如非 SQL 类型数据源）不会被注入，实现方需自行校验。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DialectAware {

    /**
     * 设置数据库方言。
     *
     * @param dialect 方言实例
     */
    void setDialect(Dialect dialect);
}
