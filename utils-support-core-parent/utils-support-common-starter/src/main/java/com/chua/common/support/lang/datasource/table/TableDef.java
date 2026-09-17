package com.chua.common.support.lang.datasource.table;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
* 表定义，描述数据库中的一张表。
* <p>
* 包含表名、模式名、注释、引擎、字符集、列集合、主键、索引、
* 以及创建时间、更新时间、近似行数等扩展信息。
* 配合 {@link DdlBuilder} 和 {@link DdlBuilder.TableDdlRenderer} 使用，
* 由方言渲染器将表定义转换为实际的 CREATE TABLE / ALTER TABLE SQL 语句。
* </p>
* <p>
* 属性说明：
* <ul>
*   <li>{@code name} — 表名</li>
*   <li>{@code catalog} — catalog 名称</li>
*   <li>{@code schema} — 数据库模式名（Schema，适用于 PostgreSQL 等）</li>
*   <li>{@code type} — 表类型（TABLE / VIEW）</li>
*   <li>{@code comment} — 表注释</li>
*   <li>{@code engine} — 数据库引擎（如 InnoDB，仅 MySQL 系列）</li>
*   <li>{@code charset} — 字符集（如 utf8mb4）</li>
*   <li>{@code collate} — 排序规则（如 utf8mb4_general_ci）</li>
*   <li>{@code columns} — 列定义列表，通过 {@link DdlBuilder#column(String, String)} 链式添加</li>
*   <li>{@code primaryKeys} — 联合主键的列名数组</li>
*   <li>{@code indexes} — 索引元数据列表</li>
*   <li>{@code createTime} — 创建时间</li>
*   <li>{@code updateTime} — 更新时间</li>
*   <li>{@code rowCount} — 近似行数</li>
* </ul>
* </p>
*
* @author CH
* @since 2024/12/12
 */
@Data
@Accessors(chain = true)
public class TableDef {

    /**
    * 表名
    */
    private String name;

    /**
    * catalog 名称
    */
    private String catalog;

    /**
    * 数据库模式名（Schema）
    */
    private String schema;

    /**
    * 表类型（TABLE / VIEW / SYSTEM TABLE 等）
    */
    private String type = "TABLE";

    /**
    * 表注释
    */
    private String comment;

    /**
    * 数据库引擎（如 InnoDB）
    */
    private String engine;

    /**
    * 字符集（如 utf8mb4）
    */
    private String charset;

    /**
    * 排序规则（如 utf8mb4_general_ci）
    */
    private String collate;

    /**
    * 列定义列表
    */
    private List<ColumnDef> columns = new ArrayList<>();

    /**
    * 联合主键的列名数组
    */
    private String[] primaryKeys;

    /**
    * 索引元数据列表
    */
    private List<IndexMetadata> indexes = new ArrayList<>();

    /**
    * 创建时间
    */
    private Date createTime;

    /**
    * 更新时间
    */
    private Date updateTime;

    /**
    * 近似行数
    */
    private Long rowCount;
}
