package com.chua.common.support.lang.datasource.dialect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 触发器定义，描述数据库中的一个触发器。
 * <p>
 * 由方言的 {@link Dialect#getTriggers(java.sql.Connection, String)} 系列方法返回，
 * 字段包含名称、关联表、触发时机/事件以及触发器体内容。
 * 当数据库元数据无法提供关联表时，可通过 {@link #parseTableName(String)} 从触发器内容中提取。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerDefinition {

    /**
     * 用于从 CREATE TRIGGER 内容中匹配表名的正则。
     * <p>匹配 {@code ON `user`}、{@code ON "user"}、{@code ON [user]}、{@code ON user} 等写法。</p>
     */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
            "(?is)\\bON\\s+([`\"\\[\\w.]+)`?\\\"?\\]?");

    /**
     * 触发器名
     */
    private String name;

    /**
     * catalog 名称
     */
    private String catalog;

    /**
     * schema 名称
     */
    private String schema;

    /**
     * 关联表名
     */
    private String tableName;

    /**
     * 触发时机（BEFORE / AFTER / INSTEAD OF）
     */
    private String timing;

    /**
     * 触发事件（INSERT / UPDATE / DELETE）
     */
    private String event;

    /**
     * 是否逐行触发
     */
    private boolean forEachRow;

    /**
     * 触发器体内容
     */
    private String body;

    /**
     * 触发器状态（ENABLED / DISABLED）
     */
    private String status;

    /**
     * 从触发器内容中提取关联表名。
     * <p>适用于元数据未提供表名的情况，解析内容中 {@code ON 表名} 位置的表名。</p>
     *
     * @param body 触发器内容（CREATE TRIGGER 语句）
     * @return 关联表名，无法解析返回 null
     */
    public static String parseTableName(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Matcher matcher = TABLE_NAME_PATTERN.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        String table = matcher.group(1);
        // 去除引号与可能的 schema 前缀
        table = table.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        int dot = table.lastIndexOf('.');
        if (dot >= 0) {
            table = table.substring(dot + 1);
        }
        return table.isEmpty() ? null : table;
    }

    /**
     * 补齐关联表名：当前表名为空时，尝试从内容中提取。
     *
     * @return 当前实例
     */
    public TriggerDefinition fillTableNameFromBody() {
        if ((tableName == null || tableName.isEmpty()) && body != null && !body.isEmpty()) {
            String parsed = parseTableName(body);
            if (parsed != null) {
                this.tableName = parsed;
            }
        }
        return this;
    }
}
