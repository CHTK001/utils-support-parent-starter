package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;

import java.util.List;

/**
* 搜索引擎 SPI 接口。
* <p>
* 抽象搜索引擎的通用能力，与 {@link MetaSearch} 的区别在于：
* </p>
* <ul>
*   <li>{@link MetaSearch} — 面向用户的元数据操作接口（链式 API）</li>
*   <li>{@link SearchEngine} — 面向引擎实现的底层能力接口</li>
* </ul>
* <p>
* 各搜索引擎通过实现此接口暴露底层能力，再由 {@link MetaSearch} 封装为统一的链式 API。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 通过 Engine 使用（推荐）
* SearchIndexDef idx = engine.meta().search().create("product").execute();
*
* // 直接使用 SearchEngine（高级用法）
* SearchEngine searchEngine = ServiceProvider.of(SearchEngine.class).getExtension("elasticsearch");
* List<String> indexes = searchEngine.listIndexes();
* SearchIndexDef def = searchEngine.getIndex("product");
* searchEngine.createIndex(def);
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface SearchEngine {

    /**
    * 返回搜索引擎类型标识（SPI 扩展键）。
    *
    * @return 类型名（如 {@code elasticsearch}、{@code solr}、{@code redis}）
    */
    String type();

    /**
    * 列出所有索引名。
    *
    * @return 索引名列表
    */
    List<String> listIndexes();

    /**
    * 获取索引定义（含字段、映射、设置）。
    *
    * @param indexName 索引名
    * @return 索引定义
    */
    SearchIndexDef getIndex(String indexName);

    /**
    * 创建索引。
    *
    * @param indexDef 索引定义
    * @return true 创建成功
    */
    boolean createIndex(SearchIndexDef indexDef);

    /**
    * 删除索引。
    *
    * @param indexName 索引名
    * @return true 删除成功
    */
    boolean deleteIndex(String indexName);

    /**
    * 获取底层客户端实例（用于高级操作）。
    *
    * @return 客户端对象（类型因搜索引擎而异）
    */
    Object getClient();
}
