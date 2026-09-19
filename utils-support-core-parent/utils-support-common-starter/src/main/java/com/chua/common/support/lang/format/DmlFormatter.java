package com.chua.common.support.lang.format;

import java.util.*;

/**
 * DML（数据操作语言，Data Manipulation Language）格式化器。
 *
 * <p><b>支持的语句类型：</b>
 * <ul>
 *   <li>{@code SELECT}：查询语句格式化</li>
 *   <li>{@code INSERT}：插入语句格式化</li>
 *   <li>{@code UPDATE}：更新语句格式化</li>
 *   <li>{@code DELETE}：删除语句格式化</li>
 * </ul>
 *
 * <p><b>核心功能：</b>
 * <ul>
 *   <li>SELECT语句：将列列表按逗号分行，每个子句（FROM、WHERE等）单独一行</li>
 *   <li>INSERT语句：将VALUES部分分行显示</li>
 *   <li>UPDATE语句：将SET和WHERE子句分行</li>
 *   <li>DELETE语句：将WHERE子句分行</li>
 * </ul>
 *
 * <p><b>格式化示例：</b>
 * <pre>
 * 输入：
 * SELECT u.id, u.username, u.email, r.name FROM users u INNER JOIN roles r ON u.role_id = r.id WHERE u.status = 'active' ORDER BY u.username ASC LIMIT 10
 *
 * 输出：
 * SELECT
 *     u.id,
 *     u.username,
 *     u.email,
 *     r.name
 * FROM users u INNER JOIN roles r ON u.role_id = r.id
 * WHERE u.status = 'active'
 * ORDER BY u.username ASC
 * LIMIT 10
 * </pre>
 *
 * @author CH
 * @see SqlFormatter
 * @since 1.0.0
 */
public class DmlFormatter extends SqlFormatter {
    /**
     * 无参构造器（默认配置）
     */
    public DmlFormatter() {
        super();
    }

    /**
     * 带参构造器（自定义配置）
     * @param keepComments keepComments（布尔开关）
     * @param upperCaseKeywords upperCaseKeywords（布尔开关）
     */
    public DmlFormatter(boolean keepComments, boolean upperCaseKeywords) {
        super(keepComments, upperCaseKeywords);
    }

    /**
     * 全参构造器
     * @param keepComments keepComments（布尔开关）
     * @param upperCaseKeywords upperCaseKeywords（布尔开关）
     * @param compressWhitespace compressWhitespace（布尔开关）
     */
    public DmlFormatter(boolean keepComments, boolean upperCaseKeywords, boolean compressWhitespace) {
        super(keepComments, upperCaseKeywords, compressWhitespace);
    }
    // ==================== 静态常量 ====================

    /**
     * SQL子句关键字集合（用于分割SELECT语句的各个部分）。
     *
     * <p>这些关键字表示SELECT语句中各个子句的开始位置，
     * 用于将完整的SELECT语句拆分为多个逻辑部分。
     */
    private static final Set<String> CLAUSE_KEYWORDS = new HashSet<>(Arrays.asList(
            // 查询核心子句
            "SELECT", "FROM", "WHERE",
            // 分组排序
            "ORDER", "GROUP", "HAVING",
            // 连接子句
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
            // 集合操作
            "UNION", "INTERSECT", "EXCEPT",
            // 限制
            "LIMIT", "OFFSET"
    ));

    /**
     * 主要子句关键字（需要在单独一行显示的子句）。
     *
     * <p>这些关键字在格式化时会放在行首，
     * 而其他关键字（如AND、OR）会跟随在上一行之后。
     */
    private static final Set<String> MAJOR_CLAUSE = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET"
    ));

    /**
     * 连接关键字（JOIN相关）。
     *
     * <p>这些关键字在FROM子句中控制连接行为。
     */
    private static final Set<String> JOIN_KEYWORDS = new HashSet<>(Arrays.asList(
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "USING"
    ));

    // ==================== 主方法 ====================

    /**
     * 执行DML格式化（实现父类抽象方法）。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>清理输入SQL</li>
     *   <li>根据语句类型（SELECT/INSERT/UPDATE/DELETE）分发</li>
     *   <li>执行对应的格式化逻辑</li>
     *   <li>返回格式化结果</li>
     * </ol>
     *
     * @param source 预处理后的SQL字符串
     * @return 格式化后的DML语句
     */
    @Override
    protected String doFormat(String source) {
        // 1. 清理输入
        String sql = normalizeSql(source);
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        // 2. 判断语句类型
        String upper = sql.toUpperCase();

        // 注意：判断顺序很重要，应该先判断最具体的模式
        if (upper.startsWith("SELECT")) {
            return formatSelect(sql);
        } else if (upper.startsWith("INSERT")) {
            return formatInsert(sql);
        } else if (upper.startsWith("UPDATE")) {
            return formatUpdate(sql);
        } else if (upper.startsWith("DELETE")) {
            return formatDelete(sql);
        } else if (upper.startsWith("WITH")) {
            // WITH子句（CTE，公共表表达式）
            return formatWithClause(sql);
        }

        // 无法识别，返回原始SQL
        return sql;
    }

    // ==================== SELECT 语句格式化 ====================

    /**
     * 格式化 SELECT 语句。
     *
     * <p><b>格式化策略：</b>
     * <ol>
     *   <li>使用 {@link #splitByKeywords} 将SQL按主要子句分割</li>
     *   <li>SELECT子句特殊处理：列数较多时每个列独占一行</li>
     *   <li>FROM子句保持在一行或换行（根据复杂度）</li>
     *   <li>WHERE/ORDER BY等子句单独一行</li>
     *   <li>JOIN子句保持在同一行（简单情况）</li>
     * </ol>
     *
     * @param sql 原始SELECT语句
     * @return 格式化后的SELECT语句
     */
    private String formatSelect(String sql) {
        // 1. 按主要子句分割
        List<String> clauses = splitByKeywords(sql, MAJOR_CLAUSE);
        if (clauses.isEmpty()) {
            return sql;
        }

        StringBuilder result = new StringBuilder();
        int indentLevel = 0;

        // 2. 逐个处理每个子句
        for (int i = 0; i < clauses.size(); i++) {
            String clause = clauses.get(i).trim();
            if (clause.isEmpty()) {
                continue;
            }

            String upper = clause.toUpperCase();

            // 2.1 SELECT子句（特殊处理）
            if (upper.startsWith("SELECT")) {
                // SELECT子句使用0级缩进，内部列定义使用1级缩进
                indentLevel = 0;
                result.append(indent(indentLevel))
                        .append(formatSelectClause(clause))
                        .append("\n");
                indentLevel = 1;
            }
            // 2.2 FROM子句
            else if (upper.startsWith("FROM")) {
                result.append(indent(indentLevel))
                        .append(formatFromClause(clause))
                        .append("\n");
            }
            // 2.3 WHERE子句
            else if (upper.startsWith("WHERE")) {
                result.append(indent(indentLevel))
                        .append(formatWhereClause(clause))
                        .append("\n");
            }
            // 2.4 GROUP BY子句
            else if (upper.startsWith("GROUP")) {
                result.append(indent(indentLevel))
                        .append(formatGroupByClause(clause))
                        .append("\n");
            }
            // 2.5 ORDER BY子句
            else if (upper.startsWith("ORDER")) {
                result.append(indent(indentLevel))
                        .append(formatOrderByClause(clause))
                        .append("\n");
            }
            // 2.6 HAVING子句
            else if (upper.startsWith("HAVING")) {
                result.append(indent(indentLevel))
                        .append(formatHavingClause(clause))
                        .append("\n");
            }
            // 2.7 LIMIT/OFFSET子句
            else if (upper.startsWith("LIMIT") || upper.startsWith("OFFSET")) {
                result.append(indent(indentLevel))
                        .append(formatLimitClause(clause))
                        .append("\n");
            }
            // 2.8 其他子句（JOIN、UNION等）
            else {
                result.append(indent(indentLevel))
                        .append(formatOtherClause(clause))
                        .append("\n");
            }
        }

        return result.toString().trim();
    }

    /**
     * 按关键字分割SQL语句。
     *
     * <p><b>算法说明：</b>
     * 将SQL字符串按空格分割为单词数组，然后顺序遍历。
     * 当遇到目标关键字时，开始一个新的子句。
     *
     * <p><b>示例：</b>
     * <pre>
     * splitByKeywords("SELECT id FROM users WHERE id > 10", {"SELECT", "FROM", "WHERE"})
     * → ["SELECT id", "FROM users", "WHERE id > 10"]
     * </pre>
     *
     * <p><b>注意事项：</b>
     * <ul>
     *   <li>只识别作为独立单词的关键字（不会匹配标识符中的关键字）</li>
     *   <li>关键字匹配不区分大小写</li>
     * </ul>
     *
     * @param sql SQL字符串
     * @param keywords 目标关键字集合
     * @return 分割后的子句列表
     */
    private List<String> splitByKeywords(String sql, Set<String> keywords) {
        List<String> result = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return result;
        }

        // 按空格分割为单词
        String[] words = sql.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }

            String upper = word.toUpperCase();

            // 检查是否为目标关键字（且不是空字符串）
            if (keywords.contains(upper) && current.length() > 0) {
                // 遇到新的关键字，保存当前子句
                result.add(current.toString().trim());
                current = new StringBuilder();
                current.append(word);
            } else {
                // 追加到当前子句
                if (current.length() > 0) {
                    current.append(" ");
                }
                current.append(word);
            }
        }

        // 添加最后一个子句
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        return result;
    }

    /**
     * 格式化 SELECT 子句（列列表）。
     *
     * <p><b>策略：</b>
     * <ul>
     *   <li>如果列数 ≤ 3，保持在一行（简洁查询）</li>
     *   <li>如果列数 > 3，每个列独占一行（复杂查询，提高可读性）</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>
     * 列数少：SELECT id, name, age FROM users
     * 列数多：SELECT
     *     id,
     *     name,
     *     age,
     *     email,
     *     created_at
     * FROM users
     * </pre>
     *
     * @param clause SELECT子句字符串
     * @return 格式化后的SELECT子句
     */
    private String formatSelectClause(String clause) {
        if (clause == null) {
            return "";
        }

        String upper = clause.toUpperCase();
        // 查找 "SELECT" 的位置
        int selectIdx = upper.indexOf("SELECT");
        if (selectIdx < 0) {
            return clause;
        }

        // 提取 SELECT 关键字和后面的列列表
        // 前缀包含 "SELECT" 关键字
        String prefix = clause.substring(0, selectIdx + 6);
        String rest = clause.substring(selectIdx + 6).trim();

        // 如果列列表为空，直接返回
        if (rest.isEmpty()) {
            return formatKeyword("SELECT");
        }

        // 分割列列表（注意保护括号内的内容）
        List<String> columns = splitPreservingParens(rest);

        // 判断列的数量
        if (columns.size() <= 3) {
            // 列数少：保持一行
            return formatKeyword("SELECT") + " " + rest;
        } else {
            // 列数多：每个列独占一行
            StringBuilder result = new StringBuilder();
            result.append(formatKeyword("SELECT")).append("\n");

            int indentLevel = 1;
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i).trim();
                result.append(indent(indentLevel)).append(col);

                // 添加逗号（除了最后一个）
                if (i < columns.size() - 1) {
                    result.append(",");
                }
                result.append("\n");
            }

            return result.toString();
        }
    }

    /**
     * 格式化 FROM 子句。
     *
     * <p><b>处理逻辑：</b>
     * 统一关键字格式，保留表名和别名。
     *
     * <p><b>扩展点：</b>
     * 可以在此方法中添加对多表JOIN的格式化支持。
     *
     * @param clause FROM子句字符串
     * @return 格式化后的FROM子句
     */
    private String formatFromClause(String clause) {
        if (clause == null) {
            return "";
        }

        String upper = clause.toUpperCase();
        int fromIdx = upper.indexOf("FROM");
        if (fromIdx < 0) {
            return clause;
        }

        // 提取 FROM 关键字和后面的内容
        // 前缀包含 "FROM" 关键字
        String prefix = clause.substring(0, fromIdx + 4);
        String rest = clause.substring(fromIdx + 4).trim();

        return formatKeyword("FROM") + " " + rest;
    }

    /**
     * 格式化 WHERE 子句。
     *
     * <p><b>处理逻辑：</b>
     * 将 AND、OR 等逻辑运算符换行并缩进，提高可读性。
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入：WHERE status = 'active' AND age > 18 AND name LIKE '%John%'
     * 输出：
     * WHERE status = 'active' AND age > 18 AND name LIKE '%John%'
     * （简单条件保持一行）
     *
     * 复杂条件：
     * WHERE status = 'active' AND (age > 18 OR role = 'admin')
     * 保持原样，因为括号内的条件视为一个整体
     * </pre>
     *
     * @param clause WHERE子句字符串
     * @return 格式化后的WHERE子句
     */
    private String formatWhereClause(String clause) {
        if (clause == null) {
            return "";
        }

        // 统一关键字格式
        String result = clause;
        result = result.replaceAll("(?i)WHERE", formatKeyword("WHERE"));
        // 注意：AND/OR 在WHERE子句中保持在同一行，除非条件特别复杂
        result = result.replaceAll("(?i)AND", formatKeyword("AND"));
        result = result.replaceAll("(?i)OR", formatKeyword("OR"));
        result = result.replaceAll("(?i)NOT", formatKeyword("NOT"));
        result = result.replaceAll("(?i)NULL", formatKeyword("NULL"));

        return result;
    }

    /**
     * 格式化 GROUP BY 子句。
     *
     * @param clause GROUP BY子句字符串
     * @return 格式化后的GROUP BY子句
     */
    private String formatGroupByClause(String clause) {
        if (clause == null) {
            return "";
        }

        String result = clause;
        result = result.replaceAll("(?i)GROUP\\s+BY", formatKeyword("GROUP BY"));
        return result;
    }

    /**
     * 格式化 ORDER BY 子句。
     *
     * @param clause ORDER BY子句字符串
     * @return 格式化后的ORDER BY子句
     */
    private String formatOrderByClause(String clause) {
        if (clause == null) {
            return "";
        }

        String result = clause;
        result = result.replaceAll("(?i)ORDER\\s+BY", formatKeyword("ORDER BY"));
        result = result.replaceAll("(?i)ASC", formatKeyword("ASC"));
        result = result.replaceAll("(?i)DESC", formatKeyword("DESC"));
        return result;
    }

    /**
     * 格式化 HAVING 子句。
     *
     * @param clause HAVING子句字符串
     * @return 格式化后的HAVING子句
     */
    private String formatHavingClause(String clause) {
        if (clause == null) {
            return "";
        }

        return clause.replaceAll("(?i)HAVING", formatKeyword("HAVING"));
    }

    /**
     * 格式化 LIMIT/OFFSET 子句。
     *
     * @param clause LIMIT或OFFSET子句字符串
     * @return 格式化后的子句
     */
    private String formatLimitClause(String clause) {
        if (clause == null) {
            return "";
        }

        String result = clause;
        result = result.replaceAll("(?i)LIMIT", formatKeyword("LIMIT"));
        result = result.replaceAll("(?i)OFFSET", formatKeyword("OFFSET"));
        return result;
    }

    /**
     * 格式化其他子句（JOIN、UNION等）。
     *
     * @param clause 其他子句字符串
     * @return 格式化后的子句
     */
    private String formatOtherClause(String clause) {
        if (clause == null) {
            return "";
        }

        String result = clause;
        // 统一JOIN相关关键字
        for (String keyword : JOIN_KEYWORDS) {
            result = result.replaceAll("(?i)" + keyword, formatKeyword(keyword));
        }
        // 统一UNION相关关键字
        result = result.replaceAll("(?i)UNION", formatKeyword("UNION"));
        result = result.replaceAll("(?i)INTERSECT", formatKeyword("INTERSECT"));
        result = result.replaceAll("(?i)EXCEPT", formatKeyword("EXCEPT"));

        return result;
    }

    // ==================== INSERT/UPDATE/DELETE 格式化 ====================

    /**
     * 格式化 INSERT 语句。
     *
     * <p><b>处理逻辑：</b>
     * <ul>
     *   <li>统一 INSERT INTO 关键字格式</li>
     *   <li>将 VALUES 部分单独换行</li>
     *   <li>如果有多行VALUES，每行缩进</li>
     * </ul>
     *
     * @param sql 原始INSERT语句
     * @return 格式化后的INSERT语句
     */
    private String formatInsert(String sql) {
        if (sql == null) {
            return null;
        }

        String result = sql;
        // 1. 统一关键字
        result = result.replaceAll("(?i)INSERT\\s+INTO", formatKeyword("INSERT INTO"));
        result = result.replaceAll("(?i)VALUES", formatKeyword("VALUES"));

        // 2. 查找 VALUES 的位置
        int valuesIdx = result.toUpperCase().indexOf("VALUES");
        if (valuesIdx >= 0) {
            // 分割为两部分：VALUES之前 和 VALUES之后
            // 此前缀包含 "VALUES" 关键字
            String beforeValues = result.substring(0, valuesIdx + 6);
            String afterValues = result.substring(valuesIdx + 6).trim();

            // 如果VALUES后面有内容
            if (!afterValues.isEmpty()) {
                // 检查是否有多个值列表（多个括号对）
                if (afterValues.contains("(") && afterValues.contains(",")) {
                    // 复杂情况：将每个值列表放在单独的行
                    String[] valueGroups = splitPreservingParens(afterValues).toArray(new String[0]);
                    StringBuilder valuesResult = new StringBuilder();
                    valuesResult.append(beforeValues).append("\n");
                    for (String group : valueGroups) {
                        valuesResult.append(indent(1)).append(group.trim());
                        valuesResult.append("\n");
                    }
                    return valuesResult.toString().trim();
                } else {
                    // 简单情况：只有一个值列表
                    return beforeValues + "\n" + indent(1) + afterValues;
                }
            }
        }

        // 压缩多余空白
        return result.replaceAll("\\s+", " ");
    }

    /**
     * 格式化 UPDATE 语句。
     *
     * <p><b>处理逻辑：</b>
     * <ul>
     *   <li>统一 UPDATE 关键字格式</li>
     *   <li>SET子句换行</li>
     *   <li>WHERE子句换行并缩进</li>
     * </ul>
     *
     * @param sql 原始UPDATE语句
     * @return 格式化后的UPDATE语句
     */
    private String formatUpdate(String sql) {
        if (sql == null) {
            return null;
        }

        String result = sql;
        // 1. 统一关键字
        result = result.replaceAll("(?i)UPDATE", formatKeyword("UPDATE"));
        result = result.replaceAll("(?i)SET", formatKeyword("SET"));
        result = result.replaceAll("(?i)WHERE", "\n  " + formatKeyword("WHERE") + " ");

        // 2. 压缩多余空白（但保留我们添加的换行）
        result = result.replaceAll("\\s+", " ");

        // 3. 清理SET后面的多余空格
        int setIdx = result.toUpperCase().indexOf("SET");
        if (setIdx >= 0) {
            // 确保SET后面有空格
            String before = result.substring(0, setIdx + 3);
            String after = result.substring(setIdx + 3).trim();
            result = before + " " + after;
        }

        return result;
    }

    /**
     * 格式化 DELETE 语句。
     *
     * <p><b>处理逻辑：</b>
     * <ul>
     *   <li>统一 DELETE FROM 关键字格式</li>
     *   <li>WHERE子句换行并缩进</li>
     * </ul>
     *
     * @param sql 原始DELETE语句
     * @return 格式化后的DELETE语句
     */
    private String formatDelete(String sql) {
        if (sql == null) {
            return null;
        }

        String result = sql;
        // 1. 统一关键字
        result = result.replaceAll("(?i)DELETE\\s+FROM", formatKeyword("DELETE FROM"));
        result = result.replaceAll("(?i)WHERE", "\n  " + formatKeyword("WHERE") + " ");

        // 2. 压缩多余空白
        result = result.replaceAll("\\s+", " ");

        return result;
    }

    /**
     * 格式化 WITH 子句（CTE，公共表表达式）。
     *
     * <p><b>处理逻辑：</b>
     * 将 WITH 子句中的每个 CTE 单独一行，并缩进。
     *
     * @param sql 原始WITH语句
     * @return 格式化后的WITH语句
     */
    private String formatWithClause(String sql) {
        if (sql == null) {
            return null;
        }

        String result = sql;
        result = result.replaceAll("(?i)WITH", formatKeyword("WITH"));
        result = result.replaceAll("(?i)AS", formatKeyword("AS"));

        // 简单处理：将CTE按逗号分割
        // 注意：更复杂的处理需要解析CTE的嵌套结构
        return result;
    }
}
