package com.chua.common.support.lang.document;

import lombok.Builder;
import lombok.Data;

import java.util.*;

/**
 * 表结构数据。
 * <p>
 * 描述数据库中的一张表（或视图）的结构信息，
 * 包含表名、注释和该表的列信息集合。
 * </p>
 *
 * @author CH
 * @since 4.0.0.41
 */
@Data
@Builder
public class TableData {

    /** 表名（或视图名） */
    private String tableName;

    /** 模式名（schema） */
    /**
     * Schema 名
     */
    private String schema;

    /** 表注释/备注 */
    private String remark;

    /** 表的所有列信息 */
    @Builder.Default
    private List<ColumnData> columns = new ArrayList<>();

    /** 外键关系（本表引用其他表） */
    @Builder.Default
    private List<RelationshipData> importedKeys = new ArrayList<>();

    /** 被引用关系（其他表引用本表） */
    @Builder.Default
    private List<RelationshipData> exportedKeys = new ArrayList<>();

    /** 对象类型：TABLE 或 VIEW */
    /**
     * 类型
     */
    private String type;
}
