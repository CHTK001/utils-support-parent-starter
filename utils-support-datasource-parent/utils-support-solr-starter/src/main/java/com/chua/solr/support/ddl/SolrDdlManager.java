package com.chua.solr.support.ddl;

import com.chua.common.support.lang.datasource.table.ColumnDef;
import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.datasource.support.ddl.DslManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Solr DDL 管理器：把「集合（集合）/核」映射为框架统一的
 * {@link TableDef}/{@link ColumnDef} 定义体系。
 *
 * <p>概念映射：集合 ≙ 表；schema 字段 ≙ 列。
 * {@link #createTableDDL} 生成的 DDL 文本为 SolrCloud 管理动作的 JSON 表示
 * （Solr 无 SQL 方言，该 JSON 即可提交给 集合 API 执行）。</p>
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class SolrDdlManager implements DslManager {

    /**
     * Solr 集合名白名单：以字母或下划线开头，可含数字、{@code _}、{@code -}，最多限定一层
     */
    private static final java.util.regex.Pattern COLLECTION_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_\\-]{0,99}(?:\\.[A-Za-z_][A-Za-z0-9_\\-]{0,99})?");

    /**
     * Solr 客户端（绑定到 /Solr 根路径）
    */
    private final SolrClient client;

    /**
     * 默认分片数
    */
    private int numShards = 1;

    /**
     * 默认副本数
    */
    private int replicationFactor = 1;

    /**
     * 构造管理器。
     *
     * @param client 绑定 /Solr 根路径的客户端
     */
    public SolrDdlManager(SolrClient client) {
        this.client = client;
    }

    @Override
    /**
     * 类型标识
    */
    public String type() {
        return "solr";
    }

    /**
     * 设置分片数
     *
     * @param numShards numshards
     * @return numShards的结果
     */
    public SolrDdlManager numShards(int numShards) {
        this.numShards = numShards;
        return this;
    }

    /**
     * 设置副本数
     *
     * @param replicationFactor replicationfactor
     * @return replicationFactor的结果
     */
    public SolrDdlManager replicationFactor(int replicationFactor) {
        this.replicationFactor = replicationFactor;
        return this;
    }

    // ==================== 查询类 ====================

    @Override
    public List<TableDef> listTables(String catalogName, String schemaName) {
        try {
            CollectionAdminRequest.List request = new CollectionAdminRequest.List();
            CollectionAdminResponse response = request.process(client);
            Object names = response.getResponse().get("collections");
            List<TableDef> tables = new ArrayList<>();
            if (names instanceof List) {
                for (Object n : (List<?>) names) {
                    tables.add(new TableDef().setName(String.valueOf(n)).setType("COLLECTION"));
                }
            }
            return tables;
        } catch (Exception e) {
            throw new IllegalStateException("listTables failed: " + e.getMessage(), e);
        }
    }

    @Override
    public TableDef getTable(String catalogName, String schemaName, String tableName) {
        if (!collectionExists(checkCollection(tableName))) {
            return null;
        }
        TableDef def = new TableDef();
        def.setName(tableName);
        def.setType("COLLECTION");
        def.setColumns(readColumns(tableName));
        return def;
    }

    // ==================== 生成类（返回可执行的管理动作文本） ====================

    @Override
    public String createTableDDL(String catalogName, String schemaName, String tableName) {
        String collection = checkCollection(tableName);
        List<ColumnDef> columns = readColumns(collection);
        if (!columns.isEmpty()) {
            log.info("Solr 集合 {} 已存在 {} 个 schema 字段，创建请求需换名或先删除旧集合", collection, columns.size());
        }
        return "{\"create-collection\":{\"name\":\"" + json(collection)
                + "\",\"numShards\":" + numShards
                + ",\"replicationFactor\":" + replicationFactor + "}}";
    }

    @Override
    public String renameTable(String schemaName, String oldTableName, String newTableName) {
        return "{\"rename-collection\":{\"collection\":\"" + json(checkCollection(oldTableName))
                + "\",\"new\":\"" + json(checkCollection(newTableName)) + "\"}}";
    }

    @Override
    public String copyTableStructure(String schemaName, String sourceTableName, String targetTableName) {
        throw new UnsupportedOperationException(
                "Solr 的 Collections API 没有等价的“复制表结构”动作，请改用 createCollection(目标集合) + addField(目标集合, 字段, 类型) 逐项建模，"
                        + "源集合结构可用 getTable(" + sourceTableName + ") 读取");
    }

    // ==================== 执行类（超出 SPI 的增强能力） ====================

    /**
     * 真正创建集合（结构性 DDL 执行）。
     * @param collectionName 集合名称
     */
    public void createCollection(String collectionName) throws Exception {
        CollectionAdminRequest.Create.createCollection(collectionName, numShards, replicationFactor)
                .process(client);
    }

    /**
     * 向集合 模式 追加字段（等价 ALTER TABLE 添加 COLUMN）。
     * @param collectionName 集合名称
     * @param fieldName 字段名称
     * @param fieldType 字段类型
     */
    public void addField(String collectionName, String fieldName, String fieldType) throws Exception {
        new SchemaRequest.AddField(java.util.Map.of("name", fieldName, "type", fieldType))
                .process(client, collectionName);
    }

    /**
     * 删除集合。
     * @param collectionName 集合名称
     */
    public void dropCollection(String collectionName) throws Exception {
        CollectionAdminRequest.deleteCollection(collectionName).process(client);
    }

    // ==================== 内部 ====================

    /**
     * 校验集合并返回可用于拼接请求体的名称。
     *
     * @param collectionName 集合名称
     * @return 通过校验的集合名称
     */
    private static String checkCollection(String collectionName) {
        if (collectionName == null || collectionName.isEmpty()) {
            throw new IllegalArgumentException("Solr 集合名不能为空");
        }
        if (!COLLECTION_PATTERN.matcher(collectionName).matches()) {
            throw new IllegalArgumentException("非法的 Solr 集合名: " + collectionName);
        }
        return collectionName;
    }

    /**
     * 按 JSON 字符串规则转义，避免标识符中的引号或反斜杠破坏请求体结构。
     *
     * @param value 原始字符串
     * @return 转义后的字符串（不含首尾引号）
     */
    private static String json(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 判断集合是否存在。
     *
     * @param collectionName 集合名称
     * @return 存在返回 true
     */
    private boolean collectionExists(String collectionName) {
        try {
            CollectionAdminResponse response = new CollectionAdminRequest.List().process(client);
            Object names = response.getResponse().get("collections");
            return names instanceof List && ((List<?>) names).contains(collectionName);
        } catch (Exception e) {
            throw new IllegalStateException("collectionExists failed: " + e.getMessage(), e);
        }
    }

    /**
     * 读取columns。
     * @param tableName table名称
     * @return 读取columns的结果
     */
    private List<ColumnDef> readColumns(String tableName) {
        List<ColumnDef> columns = new ArrayList<>();
        try {
            SchemaResponse.FieldsResponse resp =
                    new SchemaRequest.Fields().process(client, tableName);
            for (Map<String, Object> f : resp.getFields()) {
                ColumnDef c = new ColumnDef()
                        .setName(String.valueOf(f.get("name")))
                        .setType(String.valueOf(f.get("type")));
                Object multiValued = f.get("multiValued");
                if (multiValued instanceof Boolean mv) {
                    c.setNullable(!Boolean.TRUE.equals(mv));
                }
                columns.add(c);
            }
        } catch (IllegalArgumentException ignored) {
            /* 集合不存在 */
        } catch (Exception e) {
            throw new IllegalStateException("readColumns failed: " + e.getMessage(), e);
        }
        return columns;
    }

    /**
     * 便捷工厂
     *
     * @param solrBaseUrl Solrbaseurl
     * @return 的的结果
     */
    public static SolrDdlManager of(String solrBaseUrl) {
        return new SolrDdlManager(new HttpSolrClient.Builder(solrBaseUrl).build());
    }
}
