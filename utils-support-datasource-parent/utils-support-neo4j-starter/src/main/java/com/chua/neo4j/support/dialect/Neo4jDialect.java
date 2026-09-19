package com.chua.neo4j.support.dialect;

import com.chua.datasource.support.dialect.SqlDialect;

/**
 * Neo4j Cypher 方言（配置驱动）。
 * <p>读取 {@code META-INF/dialect-env/neo4j.env}：查询语言 cypher，
 * 原生分页模板为 {@code {sql} SKIP {offset} LIMIT {limit}}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Neo4jDialect extends SqlDialect {

    /**
     * 构造方法。
     */
    public Neo4jDialect() {
        super("neo4j");
    }
}
