package com.chua.common.support.lang.document;

import com.chua.common.support.lang.document.ColumnData;
import com.chua.common.support.lang.document.DocumentData;
import com.chua.common.support.lang.document.DocumentExportConfig;
import com.chua.common.support.lang.document.DocumentParser;
import com.chua.common.support.lang.document.DocumentTemplateType;
import com.chua.common.support.lang.document.RelationshipData;
import com.chua.common.support.lang.document.TableData;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 文档导出器 SPI 接口。
*
* <p>将 {@link DocumentData} 导出为指定格式的文件。</p>
*
* <p>与 {@link DocumentParser} 的关系：</p>
* <ul>
*   <li>{@code DocumentParser} — 数据源 → {@code DocumentData}</li>
*   <li>{@code DocumentProvider} — {@code DocumentData} → 文件（Word/PDF/MD/HTML）</li>
* </ul>
*
* <p>推荐使用链式 API：</p>
* <pre>{@code
* DocumentExporter.of(data)
*     .format("html")
*     .template(DocumentTemplateType.SWAGGER)
*     .output(new File("out/db.html"))
*     .export();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi
public interface DocumentProvider {

    /**
    * 通过 SPI 创建导出器实例。
    *
    * @param type 导出格式（"word"、"pdf"、"markdown"、"html"）
    * @return DocumentProvider 实例
     */
    static DocumentProvider create(String type) {
        return ServiceProvider.of(DocumentProvider.class).getExtension(type);
    }

    /**
    * 获取导出格式名称。
    *
    * @return 格式名称
     */
    String getType();

    /**
    * 获取支持的文件扩展名。
    *
    * @return 扩展名数组
     */
    String[] getExtensions();

    /**
    * 导出文档数据到文件（默认模板）。
    *
    * @param data       文档数据
    * @param outputFile 输出文件
     */
    default void export(DocumentData data, File outputFile) {
        export(data, outputFile, DocumentExportConfig.builder()
                .format(getType())
                .templateType(DocumentTemplateType.DEFAULT)
                .outputFile(outputFile)
                .build());
    }

    /**
    * 按导出配置渲染文档。
    *
    * @param data       文档数据
    * @param outputFile 输出文件
    * @param config     导出配置（模板类型、自定义模板等）
     */
    void export(DocumentData data, File outputFile, DocumentExportConfig config);

    /**
    * 将表关系转换为关系图 JSON（节点 + 边）。
    *
    * <p>节点包含表名、备注、schema、类型、主键列、外键列；</p>
    * <p>边包含来源表、目标表、来源列、目标列及更新/删除规则。</p>
    *
    * @param data 文档数据
    * @return 关系图 JSON 字符串
     */
    static String toRelationshipJson(DocumentData data) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        if (data.getTables() != null) {
            for (TableData table : data.getTables()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", table.getTableName());
                node.put("label", (table.getRemark() != null && !table.getRemark().isEmpty())
                        ? table.getRemark() : table.getTableName());
                node.put("schema", table.getSchema());
                node.put("type", table.getType() != null ? table.getType() : "TABLE");

                List<String> pkCols = new ArrayList<>();
                List<String> fkCols = new ArrayList<>();
                if (table.getColumns() != null) {
                    for (ColumnData col : table.getColumns()) {
                        if (col.isPrimaryKey()) {
                            pkCols.add(col.getColumnName());
                        }
                    }
                }
                if (table.getImportedKeys() != null) {
                    for (RelationshipData r : table.getImportedKeys()) {
                        if (!fkCols.contains(r.getFkColumnName())) {
                            fkCols.add(r.getFkColumnName());
                        }
                    }
                }
                node.put("pk", pkCols);
                node.put("fk", fkCols);
                nodes.add(node);
            }

            for (TableData table : data.getTables()) {
                if (table.getExportedKeys() != null) {
                    for (RelationshipData r : table.getExportedKeys()) {
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("from", r.getFkTableName());
                        edge.put("to", r.getPkTableName());
                        edge.put("fromColumn", r.getFkColumnName());
                        edge.put("toColumn", r.getPkColumnName());
                        edge.put("updateRule", r.getUpdateRule());
                        edge.put("deleteRule", r.getDeleteRule());
                        edges.add(edge);
                    }
                }
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return Json.toJson(graph);
    }
}
