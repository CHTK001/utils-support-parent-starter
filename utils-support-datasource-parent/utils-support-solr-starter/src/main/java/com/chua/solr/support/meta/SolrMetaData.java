package com.chua.solr.support.meta;

import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.MetaIndex;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.MetaTable;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.MetaView;
import com.chua.datasource.support.meta.DefaultMetaData;
import com.chua.solr.support.engine.SolrEngine;

/**
 * Solr 元数据入口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SolrMetaData extends DefaultMetaData {

    public SolrMetaData(SolrEngine engine) {
        super(engine);
    }

    @Override
    public MetaSearch search() {
        return new SolrMeta(this, (SolrEngine) engine);
    }

    @Override
    public MetaSearch search(String indexName) {
        return new SolrMeta(this, (SolrEngine) engine);
    }
}
