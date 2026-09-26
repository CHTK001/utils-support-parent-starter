package com.chua.common.support.lang.datasource.engine.ddl;

/**
 * 引擎感知接口，用于向 DDL 实现注入所属 {@link com.chua.common.support.lang.datasource.engine.Engine}。
 *
 * <p>与 {@link DialectAware} 配套，覆盖非 JDBC 数据源的 DDL 实现：
 * 这类实现（如搜索引擎集合管理）不需要 {@code DataSource}，而是从所属引擎取
 * 自己的客户端（Solr client、ES client、图数据库 driver 等）。</p>
 *
 * <p>实现类据此自行解包引擎，不必让引擎为每种实现硬编码一个 {@code new}：
 * {@code Engine#ddl()} 解析出实现后会注入本引擎，实现方再按需转型取值。</p>
 *
 * <p>实现方需自行校验引擎类型是否匹配，转型失败应抛出可定位异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EngineAware {

    /**
     * 设置所属引擎。
     *
     * @param engine 所属引擎实例
     */
    void setEngine(com.chua.common.support.lang.datasource.engine.Engine engine);
}
