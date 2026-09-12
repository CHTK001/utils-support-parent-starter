package com.chua.elasticsearch.support.meta;

import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.MetaIndex;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.MetaTable;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.MetaView;
import com.chua.datasource.support.meta.DefaultMetaData;
import com.chua.elasticsearch.support.engine.ElasticsearchEngine;

/**
 * Elasticsearch 元数据入口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EsMetaData extends DefaultMetaData {

    /**
      * 创建 esmeta数据 实例
     * @param engine engine
     */
    public EsMetaData(ElasticsearchEngine engine) {
        super(engine);
    }

    @Override
    /** 搜索 */
    public MetaSearch search() {
        return new EsMeta(this, (ElasticsearchEngine) engine);
    }

    @Override
    /** 搜索 */
    public MetaSearch search(String indexName) {
        return new EsMeta(this, (ElasticsearchEngine) engine);
    }
}
