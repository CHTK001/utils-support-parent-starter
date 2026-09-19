package com.chua.elasticsearch.support.dialect;

import com.chua.datasource.support.dialect.SqlDialect;

/**
 * Elasticsearch 方言（配置驱动）。
 * <p>读取 {@code META-INF/dialect-env/elasticsearch.env}：非 SQL 查询语言、
 * 不支持 SQL 式分页（from/size 由引擎侧处理），仅承载能力探测元数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ElasticsearchDialect extends SqlDialect {

    /**
     * 构造方法。
     */
    public ElasticsearchDialect() {
        super("elasticsearch");
    }
}
