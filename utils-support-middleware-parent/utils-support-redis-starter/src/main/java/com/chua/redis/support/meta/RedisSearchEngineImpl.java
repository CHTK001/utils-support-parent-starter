package com.chua.redis.support.meta;

import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.redis.support.engine.RediSearchEngine;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.SafeEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* Redis redi搜索 搜索引擎实现。
*
* @author CH
* @since 4.0.0.42
 */
public class RedisSearchEngineImpl implements SearchEngine {

    /** 引擎 */
    private final RediSearchEngine engine;

    /**
    * 创建 redis搜索engineimpl 实例
    * @param engine engine
    */
    public RedisSearchEngineImpl(RediSearchEngine engine) {
        this.engine = engine;
    }

    @Override
    /** 类型 */
    public String type() {
        return "redis";
    }

    @Override
    /** 列表索引 */
    public List<String> listIndexes() {
        List<String> indexes = new ArrayList<>();
        try (Jedis jedis = engine.getPoolPublic(engine.getDefaultDataSourceName()).getResource()) {
            Object response = jedis.sendCommand(() -> SafeEncoder.encode("FT._LIST"));
            if (response instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        indexes.add(s);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("列出 RedisSearch 索引失败", e);
        }
        return indexes;
    }

    @Override
    /** 获取索引 */
    public SearchIndexDef getIndex(String indexName) {
        try (Jedis jedis = engine.getPoolPublic(engine.getDefaultDataSourceName()).getResource()) {
            Object response = jedis.sendCommand(() -> SafeEncoder.encode("FT.INFO"), SafeEncoder.encode(indexName));
            if (response instanceof List<?> infoList) {
                SearchIndexDef def = new SearchIndexDef();
                def.setName(indexName);
                List<SearchFieldDef> fields = new ArrayList<>();
                for (int i = 0; i < infoList.size(); i++) {
                    Object item = infoList.get(i);
                    if ("attributes".equals(item)) {
                        List<?> attrs = (List<?>) infoList.get(i + 1);
                        for (int j = 0; j < attrs.size(); j++) {
                            Object attr = attrs.get(j);
                            if (attr instanceof List<?> attrList && attrList.size() >= 2) {
                                SearchFieldDef field = new SearchFieldDef();
                                field.setName(attrList.getFirst().toString());
                                field.setType(attrList.get(1).toString());
                                if (attrList.size() > 2) {
                                    field.setWeight(Double.parseDouble(attrList.get(2).toString()));
                                }
                                fields.add(field);
                            }
                        }
                        break;
                    }
                }
                def.setFields(fields);
                return def;
            }
        } catch (Exception e) {
            throw new RuntimeException("获取 RedisSearch 索引定义失败: " + indexName, e);
        }
        return null;
    }

    @Override
    /** 创建索引 */
    public boolean createIndex(SearchIndexDef indexDef) {
        if (indexDef == null || indexDef.getName() == null) {
            throw new IllegalArgumentException("索引定义不能为空");
        }
        try (Jedis jedis = engine.getPoolPublic(engine.getDefaultDataSourceName()).getResource()) {
            StringBuilder sb = new StringBuilder();
            sb.append("FT.CREATE ").append(indexDef.getName()).append(" ON HASH");
            if (indexDef.getSettings() != null && indexDef.getSettings().containsKey("prefix")) {
                Object prefix = indexDef.getSettings().get("prefix");
                sb.append(" PREFIX 1 ").append(prefix);
            } else {
                sb.append(" PREFIX 1 ").append(indexDef.getName());
            }
            sb.append(" SCHEMA");
            if (indexDef.getFields() != null) {
                for (SearchFieldDef field : indexDef.getFields()) {
                    sb.append(" ").append(field.getName()).append(" ").append(field.getType());
                    if (field.getWeight() != 1.0) {
                        sb.append(" WEIGHT ").append((int) field.getWeight());
                    }
                }
            }
            jedis.sendCommand(() -> SafeEncoder.encode(sb.toString()));
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建 RedisSearch 索引失败: " + indexDef.getName(), e);
        }
    }

    @Override
    /** 删除索引 */
    public boolean deleteIndex(String indexName) {
        try (Jedis jedis = engine.getPoolPublic(engine.getDefaultDataSourceName()).getResource()) {
            jedis.sendCommand(() -> SafeEncoder.encode("FT.DROPINDEX"), SafeEncoder.encode(indexName));
            return true;
        } catch (Exception e) {
            throw new RuntimeException("删除 RedisSearch 索引失败: " + indexName, e);
        }
    }

    @Override
    /** 获取客户端 */
    public Object getClient() {
        return engine.getPoolPublic(engine.getDefaultDataSourceName());
    }
}
