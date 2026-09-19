package com.chua.solr.support.ddl;

import com.chua.common.support.lang.datasource.table.ColumnDef;
import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.datasource.support.ddl.DslManager;
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
public class SolrDdlManager implements DslManager {

    /** Solr 客户端（绑定到 /Solr 根路径） */
    private final SolrClient client;

    /** 默认分片数 */
    private int numShards = 1;

    /** 默认副本数 */
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
    /** 类型标识 */
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
        List<ColumnDef> columns = readColumns(tableName);
        if (columns.isEmpty()) {
            return null;
        }
        TableDef def = new TableDef();
        def.setName(tableName);
        def.setType("COLLECTION");
        def.setColumns(columns);
        return def;
    }

    // ==================== 生成类（返回可执行的管理动作文本） ====================

    @Override
    public String createTableDDL(String catalogName, String schemaName, String tableName) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"create-collection\":{")
                .append("\"name\":\"").append(tableName).append("\",")
                .append("\"numShards\":").append(numShards).append(",")
                .append("\"replicationFactor\":").append(replicationFactor).append("}}");
        List<ColumnDef> columns = readColumns(tableName);
        if (!columns.isEmpty()) {
            sb.append("\n/* 已存在 schema 字段: ");
            for (ColumnDef c : columns) {
                sb.append(c.getName()).append(':').append(c.getType()).append(' ');
            }
            sb.append("*/");
        }
        return sb.toString();
    }

    @Override
    public String renameTable(String schemaName, String oldTableName, String newTableName) {
        return "{\"rename-collection\":{\"" + oldTableName + "\":\"" + newTableName + "\"}}";
    }

    @Override
    public String copyTableStructure(String schemaName, String sourceTableName, String targetTableName) {
        return "{\"create-collection\":{\"name\":\"" + targetTableName
                + "\",\"baseConfigSet\":\"_default\",\"clone-from\":\"" + sourceTableName + "\"}}";
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
