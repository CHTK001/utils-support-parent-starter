package com.chua.redis.support.meta;

import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.MetaIndex;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.MetaTable;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.MetaView;
import com.chua.datasource.support.meta.DefaultMetaData;
import com.chua.redis.support.engine.RediSearchEngine;

/**
 * Redis RediSearch 元数据入口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RedisSearchMetaData extends DefaultMetaData {

    public RedisSearchMetaData(RediSearchEngine engine) {
        super(engine);
    }

    @Override
    public MetaSearch search() {
        return new RedisSearchMeta(this, (RediSearchEngine) engine);
    }

    @Override
    public MetaSearch search(String indexName) {
        return new RedisSearchMeta(this, (RediSearchEngine) engine);
    }
}
