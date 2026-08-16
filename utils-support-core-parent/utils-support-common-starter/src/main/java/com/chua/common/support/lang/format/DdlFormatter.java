package com.chua.common.support.lang.format;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL（数据定义语言，Data Definition Language）格式化器。
 *
 * <p><b>支持的语句类型：</b>
 * <ul>
 *   <li>{@code CREATE TABLE}：建表语句格式化，支持列定义、约束定义</li>
 *   <li>{@code ALTER TABLE}：修改表语句格式化</li>
 *   <li>{@code DROP TABLE}：删除表语句格式化</li>
 *   <li>{@code CREATE INDEX}：创建索引语句格式化</li>
 * </ul>
 *
 * <p><b>核心功能：</b>
 * <ul>
 *   <li>将 CREATE TABLE 的列定义逐个换行并缩进</li>
 *   <li>将约束定义（PRIMARY KEY、FOREIGN KEY、UNIQUE等）单独列出</li>
 *   <li>统一关键字大小写</li>
 *   <li>自动识别并保留注释</li>
 * </ul>
 *
 * <p><b>格式化示例：</b>
 * <pre>
 * 输入：
 * CREATE TABLE users (id INT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(50) NOT NULL, email VARCHAR(100) UNIQUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
 *
 * 输出：
 * CREATE TABLE users (
 *     id INT PRIMARY KEY AUTO_INCREMENT,
 *     username VARCHAR(50) NOT NULL,
 *     email VARCHAR(100) UNIQUE,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 * </pre>
 *
 * @author CH
 * @see SqlFormatter
 * @since 1.0.0
 */
public class DdlFormatter extends SqlFormatter {
    /**
     * 无参构造器（默认配置）
     */
    public DdlFormatter() {
        super();
    }

    /**
     * 带参构造器（自定义配置）
     */
    public DdlFormatter(boolean keepComments, boolean upperCaseKeywords) {
        super(keepComments, upperCaseKeywords);
    }

    /**
     * 全参构造器（自定义所有配置）
     */
    public DdlFormatter(boolean keepComments, boolean upperCaseKeywords, boolean compressWhitespace) {
        super(keepComments, upperCaseKeywords, compressWhitespace);
    }
    // ==================== 正则表达式常量 ====================

    /**
     * 匹配 CREATE TABLE 语句的正则表达式。
     *
     * <p><b>分组说明：</b>
     * <ul>
     *   <li>group(1)：表名</li>
     *   <li>group(2)：列定义和约束定义部分（括号内的内容）</li>
     * </ul>
     *
     * <p><b>匹配示例：</b>
     * {@code CREATE TABLE users (id INT, name VARCHAR(50));}
     * → group(1) = "users", group(2) = "id INT, name VARCHAR(50)"
     */
    private static final Pattern CREATE_TABLE_PATTERN =
            Pattern.compile("(?i)CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)\\s*;?\\s*$", Pattern.DOTALL);

    /**
     * 匹配 ALTER TABLE 语句的正则表达式。
     *
     * <p><b>分组说明：</b>
     * <ul>
     *   <li>group(1)：表名</li>
     *   <li>group(2)：操作部分（ADD、DROP、MODIFY等）</li>
     * </ul>
     */
    private static final Pattern ALTER_TABLE_PATTERN =
            Pattern.compile("(?i)ALTER\\s+TABLE\\s+(\\w+)\\s+(.*?);?\\s*$", Pattern.DOTALL);

    /**
     * 匹配 CREATE INDEX 语句的正则表达式。
     */
    private static final Pattern CREATE_INDEX_PATTERN =
            Pattern.compile("(?i)CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\((.*?)\\)\\s*;?\\s*$", Pattern.DOTALL);

    /**
     * 用于判断是否为约束的预定义模式。
     *
     * <p>约束类型包括：
     * <ul>
     *   <li>PRIMARY KEY（主键）</li>
     *   <li>FOREIGN KEY（外键）</li>
     *   <li>UNIQUE（唯一约束）</li>
     *   <li>CHECK（检查约束）</li>
     *   <li>CONSTRAINT（命名约束）</li>
     * </ul>
     */
    private static final Pattern CONSTRAINT_PATTERN =
            Pattern.compile("(?i)(CONSTRAINT\\s+\\w+\\s+)?(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE|CHECK)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "PRIMARY KEY", "FOREIGN KEY", "UNIQUE", "CHECK", "CONSTRAINT"
    );

    // ==================== 主方法 ====================

    /**
     * 执行DDL格式化（实现父类抽象方法）。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>清理SQL文本（去除多余空白）</li>
     *   <li>根据语句类型（CREATE/ALTER/DROP）分发到对应的格式化方法</li>
     *   <li>如果无法识别语句类型，返回原始SQL</li>
     * </ol>
     *
     * <p><b>扩展点：</b>
     * 如果需要在子类中支持更多DDL类型，可以重写此方法，
     * 添加新的语句类型判断分支。
     *
     * @param source 预处理后的SQL字符串
     * @return 格式化后的DDL语句
     */
    @Override
    protected String doFormat(String source) {
        // 1. 清理输入：去除多余空白，规范化空格
        String sql = normalizeSql(source);
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        // 2. 判断SQL类型并分发到对应的格式化方法
        String upper = sql.toUpperCase();

        if (upper.startsWith("CREATE TABLE")) {
            // CREATE TABLE 语句 → 最复杂的格式化逻辑
            return formatCreateTable(sql);

        } else if (upper.startsWith("ALTER TABLE")) {
            // ALTER TABLE 语句 → 中等复杂度的格式化
            return formatAlterTable(sql);

        } else if (upper.startsWith("DROP TABLE")) {
            // DROP TABLE 语句 → 简单格式化
            return formatDropTable(sql);

        } else if (upper.startsWith("CREATE INDEX") || upper.startsWith("CREATE UNIQUE INDEX")) {
            // CREATE INDEX 语句 → 索引格式化
            return formatCreateIndex(sql);

        } else if (upper.startsWith("TRUNCATE TABLE")) {
            // TRUNCATE TABLE 语句 → 最简单的格式化
            return formatTruncateTable(sql);
        }

        // 如果无法识别语句类型，返回原始SQL（不做任何修改）
        return source;
    }

    // ==================== CREATE TABLE 格式化 ====================

    /**
     * 格式化 CREATE TABLE 语句。
     *
     * <p><b>详细步骤：</b>
     * <ol>
     *   <li>使用正则表达式匹配并提取表名和列定义部分</li>
     *   <li>如果匹配失败，返回原始SQL（说明不是标准的CREATE TABLE格式）</li>
     *   <li>解析列定义部分，区分列定义和约束定义</li>
     *   <li>格式化列定义：每个列单独一行，缩进一级</li>
     *   <li>格式化约束定义：每个约束单独一行，缩进一级</li>
     *   <li>组装成完整的 CREATE TABLE 语句</li>
     * </ol>
     *
     * <p><b>边界情况处理：</b>
     * <ul>
     *   <li>如果表名包含数据库前缀（如 db.table），正则需要调整</li>
     *   <li>如果列定义中包含嵌套括号（如 VARCHAR(255)），解析时需要保护</li>
     *   <li>如果列定义中包含注释（COMMENT），需要保留</li>
     * </ul>
     *
     * @param sql 原始 CREATE TABLE 语句
     * @return 格式化后的 CREATE TABLE 语句
     */
    private String formatCreateTable(String sql) {
        // 1. 使用正则匹配
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            // 如果正则匹配失败，返回原始SQL（避免破坏语法）
            return sql;
        }

        // 2. 提取关键信息
        // 表名
        String tableName = matcher.group(2);
        // 列定义和约束定义
        String columnsPart = matcher.group(3);

        // 3. 解析列定义部分
        //    将列定义和约束定义分开存储
        // );    列定义列表
        List<String> columnDefs = new ArrayList<>();
        List<String> constraintDefs = new ArrayList<>();
        parseColumnDefinitions(columnsPart, columnDefs, constraintDefs);

        // 4. 构建格式化后的SQL
        StringBuilder result = new StringBuilder();

        // 4.1 添加 CREATE TABLE 表名
        result.append(formatKeyword("CREATE TABLE"))
                .append(" ")
                .append(tableName)
                .append(" (\n");

        // 表体缩进一级
        int indentLevel = 1;


        // 4.2 添加列定义（每个列单独一行）
        for (int i = 0; i < columnDefs.size(); i++) {
            String def = columnDefs.get(i).trim();
            result.append(indent(indentLevel))
                    .append(formatColumnDefinition(def));

            // 判断是否需要添加逗号：
            // - 如果不是最后一个列定义，添加逗号
            // - 如果还有约束定义，当前列也需要逗号
            if (i < columnDefs.size() - 1 || !constraintDefs.isEmpty()) {
                result.append(",");
            }
            result.append("\n");
        }

        // 4.3 添加约束定义（每个约束单独一行）
        for (int i = 0; i < constraintDefs.size(); i++) {
            String def = constraintDefs.get(i).trim();
            result.append(indent(indentLevel))
                    .append(formatConstraintDefinition(def));

            // 如果不是最后一个约束，添加逗号
            if (i < constraintDefs.size() - 1) {
                result.append(",");
            }
            result.append("\n");
        }

        // 4.4 关闭括号和添加分号
        result.append(");");

        return result.toString();
    }

    /**
     * 解析列定义部分，将列定义和约束定义分离。
     *
     * <p><b>解析算法：</b>
     * 采用字符级遍历，逐字符扫描并保持括号深度和字符串状态。
     *
     * <p><b>状态机说明：</b>
     * <ul>
     *   <li><b>parenDepth</b>：当前括号嵌套深度，用于识别函数参数中的逗号</li>
     *   <li><b>inSingleQuote</b>：是否在单引号字符串内（如 COMMENT '注释'）</li>
     *   <li><b>inDoubleQuote</b>：是否在双引号字符串内（如标识符 "col"）</li>
     * </ul>
     *
     * <p><b>分割规则：</b>
     * 当遇到逗号且满足以下条件时，表示一个定义结束：
     * <ul>
     *   <li>不在括号内（parenDepth == 0）</li>
     *   <li>不在字符串内（inSingleQuote == false && inDoubleQuote == false）</li>
     * </ul>
     *
     * <p><b>分类规则：</b>
     * 判断一个定义是列定义还是约束定义：
     * <ul>
     *   <li>如果包含 PRIMARY KEY、FOREIGN KEY、UNIQUE、CHECK、CONSTRAINT → 约束定义</li>
     *   <li>否则 → 列定义</li>
     * </ul>
     *
     * @param columnsPart 列定义部分的原始字符串（括号内的内容）
     * @param columnDefs 输出参数：存储解析出的列定义
     * @param constraintDefs 输出参数：存储解析出的约束定义
     */
    private void parseColumnDefinitions(String columnsPart,
                                        List<String> columnDefs,
                                        List<String> constraintDefs) {
        // 防御性检查：如果输入为空，直接返回
        if (columnsPart == null || columnsPart.isEmpty()) {
            return;
        }

        // 使用StringBuilder缓存当前正在构建的定义
        StringBuilder current = new StringBuilder();

        // 状态变量
        // // 当前括号嵌套深度
        int parenDepth = 0;  
        // alse; // 是否在单引号字符串内
        boolean inSingleQuote = false;
        // alse; // 是否在双引号字符串内
        boolean inDoubleQuote = false;


        // 逐字符扫描
        for (char c : columnsPart.toCharArray()) {
            // ----- 处理字符串状态（必须最先处理） -----
            // 单引号切换（不在双引号内时）
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                continue;
            }
            // 双引号切换（不在单引号内时）
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
                continue;
            }

            // 如果在字符串内，直接追加字符，不做任何处理
            if (inSingleQuote || inDoubleQuote) {
                current.append(c);
                continue;
            }

            // ----- 处理括号（不在字符串内） -----
            if (c == '(') {
                // parenDepth++; // 进入更深一层括号
              
                current.append(c);
            } else if (c == ')') {
                // parenDepth--; // 退出当前括号层
              
                current.append(c);
            }
            // ----- 处理逗号分隔符（核心逻辑） -----
            else if (c == ',' && parenDepth == 0) {
                // 只有在括号外且不是字符串内的逗号才是分隔符
                String part = current.toString().trim();
                if (!part.isEmpty()) {
                    // 判断是列定义还是约束定义
                    if (isConstraint(part)) {
                        constraintDefs.add(part);
                    } else {
                        columnDefs.add(part);
                    }
                }
                // 重置缓存，开始构建下一个定义
                current = new StringBuilder();
            }
            // ----- 普通字符 -----
            else {
                current.append(c);
            }
        }

        // 处理最后一个部分（文件末尾没有逗号）
        String lastPart = current.toString().trim();
        if (!lastPart.isEmpty()) {
            if (isConstraint(lastPart)) {
                constraintDefs.add(lastPart);
            } else {
                columnDefs.add(lastPart);
            }
        }
    }

    /**
     * 判断一个定义是否为约束定义。
     *
     * <p><b>判断依据：</b>
     * 检查定义中是否包含以下关键字（不区分大小写）：
     * <ul>
     *   <li>{@code PRIMARY KEY} - 主键约束</li>
     *   <li>{@code FOREIGN KEY} - 外键约束</li>
     *   <li>{@code UNIQUE} - 唯一约束</li>
     *   <li>{@code CHECK} - 检查约束</li>
     *   <li>{@code CONSTRAINT} - 命名约束</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>
     * isConstraint("PRIMARY KEY (id)")       // true
     * isConstraint("CONSTRAINT fk FOREIGN KEY (user_id) REFERENCES users(id)") // true
     * isConstraint("id INT NOT NULL")        // false（这是列定义）
     * </pre>
     *
     * @param part 定义字符串
     * @return 如果是约束定义返回true，否则返回false
     */
    private boolean isConstraint(String part) {
        if (part == null || part.isEmpty()) {
            return false;
        }
        String upper = part.toUpperCase();
        return CONSTRAINT_KEYWORDS.stream().anyMatch(upper::contains);
    }

    /**
     * 格式化单个列定义。
     *
     * <p><b>处理逻辑：</b>
     * 目前保持原始格式不变，仅做简单的去空格处理。
     *
     * <p><b>可扩展点：</b>
     * 可以在此方法中添加列定义的规范化，例如：
     * <ul>
     *   <li>将数据类型统一为小写或大写</li>
     *   <li>将 NOT NULL 规范化</li>
     *   <li>将 DEFAULT 值格式化</li>
     * </ul>
     *
     * @param def 原始列定义字符串
     * @return 格式化后的列定义
     */
    private String formatColumnDefinition(String def) {
        if (def == null) {
            return "";
        }
        // 简单处理：去除多余空白
        return def.replaceAll("\\s+", " ");
    }

    /**
     * 格式化单个约束定义。
     *
     * <p><b>处理逻辑：</b>
     * 将约束定义中的关键字统一为大写或小写（根据配置）。
     *
     * <p><b>支持的约束类型：</b>
     * <ul>
     *   <li>PRIMARY KEY → PRIMARY KEY</li>
     *   <li>FOREIGN KEY → FOREIGN KEY</li>
     *   <li>UNIQUE → UNIQUE</li>
     *   <li>CHECK → CHECK</li>
     *   <li>CONSTRAINT → CONSTRAINT</li>
     *   <li>REFERENCES → REFERENCES（外键引用）</li>
     * </ul>
     *
     * @param def 原始约束定义字符串
     * @return 格式化后的约束定义
     */
    private String formatConstraintDefinition(String def) {
        if (def == null) {
            return "";
        }

        String result = def;
        // 使用正则替换，将关键字统一格式化
        // 注意：使用 (?i) 表示不区分大小写匹配
        result = result.replaceAll("(?i)PRIMARY\\s+KEY", formatKeyword("PRIMARY KEY"));
        result = result.replaceAll("(?i)FOREIGN\\s+KEY", formatKeyword("FOREIGN KEY"));
        result = result.replaceAll("(?i)CONSTRAINT", formatKeyword("CONSTRAINT"));
        result = result.replaceAll("(?i)UNIQUE", formatKeyword("UNIQUE"));
        result = result.replaceAll("(?i)CHECK", formatKeyword("CHECK"));
        result = result.replaceAll("(?i)REFERENCES", formatKeyword("REFERENCES"));

        // 去除多余空白
        return result.replaceAll("\\s+", " ");
    }

    // ==================== ALTER TABLE 格式化 ====================

    /**
     * 格式化 ALTER TABLE 语句。
     *
     * <p><b>处理逻辑：</b>
     * <ol>
     *   <li>提取表名和操作列表</li>
     *   <li>将多个操作按逗号分割，每个操作单独一行</li>
     *   <li>每个操作缩进一级</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入：ALTER TABLE users ADD COLUMN age INT, DROP COLUMN old_field
     * 输出：
     * ALTER TABLE users
     *     ADD COLUMN age INT,
     *     DROP COLUMN old_field
     * </pre>
     *
     * @param sql 原始 ALTER TABLE 语句
     * @return 格式化后的 ALTER TABLE 语句
     */
    private String formatAlterTable(String sql) {
        // 1. 正则匹配
        Matcher matcher = ALTER_TABLE_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            // 匹配失败，返回原始SQL
            return sql;
        }

        // 2. 提取表名和操作部分
        String tableName = matcher.group(1);
        String operations = matcher.group(2);

        // 3. 分割操作（按逗号分割，但需要保护括号内的逗号）
        List<String> opList = splitPreservingParens(operations);
        if (opList.isEmpty()) {
            return sql;
        }

        // 4. 构建格式化结果
        StringBuilder result = new StringBuilder();
        result.append(formatKeyword("ALTER TABLE"))
                .append(" ")
                .append(tableName)
                .append("\n");

        int indentLevel = 1;
        for (int i = 0; i < opList.size(); i++) {
            String op = opList.get(i).trim();
            result.append(indent(indentLevel))
                    .append(formatKeyword(op));

            // 如果不是最后一个操作，添加逗号
            if (i < opList.size() - 1) {
                result.append(",");
            }
            result.append("\n");
        }

        return result.toString().trim();
    }

    // ==================== 其他DDL语句格式化 ====================

    /**
     * 格式化 DROP TABLE 语句。
     *
     * <p><b>处理逻辑：</b>
     * 将关键字统一格式化，并压缩多余空白。
     *
     * <p><b>示例：</b>
     * 输入：DROP TABLE   users
     * 输出：DROP TABLE users
     *
     * @param sql 原始 DROP TABLE 语句
     * @return 格式化后的 DROP TABLE 语句
     */
    private String formatDropTable(String sql) {
        if (sql == null) {
            return null;
        }

        // 1. 替换关键字为统一格式
        String result = sql.replaceAll("(?i)DROP\\s+TABLE", formatKeyword("DROP TABLE"));
        // 2. 压缩多余空白
        result = result.replaceAll("\\s+", " ");

        return result.trim();
    }

    /**
     * 格式化 CREATE INDEX 语句。
     *
     * <p><b>处理逻辑：</b>
     * 将关键字统一格式化，并在关键字前后添加适当的空格。
     *
     * <p><b>示例：</b>
     * 输入：CREATE UNIQUE INDEX idx_user_name ON users (username)
     * 输出：CREATE UNIQUE INDEX idx_user_name ON users (username)
     *
     * @param sql 原始 CREATE INDEX 语句
     * @return 格式化后的 CREATE INDEX 语句
     */
    private String formatCreateIndex(String sql) {
        if (sql == null) {
            return null;
        }

        String result = sql;
        // 替换关键字
        result = result.replaceAll("(?i)CREATE\\s+(UNIQUE\\s+)?INDEX",
                formatKeyword("CREATE") + " " + formatKeyword("INDEX"));
        result = result.replaceAll("(?i)ON", " " + formatKeyword("ON") + " ");

        // 压缩多余空白
        result = result.replaceAll("\\s+", " ");

        return result.trim();
    }

    /**
     * 格式化 TRUNCATE TABLE 语句。
     *
     * <p><b>处理逻辑：</b>
     * 最简单的格式化，仅统一关键字并压缩空白。
     *
     * @param sql 原始 TRUNCATE TABLE 语句
     * @return 格式化后的 TRUNCATE TABLE 语句
     */
    private String formatTruncateTable(String sql) {
        if (sql == null) {
            return null;
        }

        String result = sql.replaceAll("(?i)TRUNCATE\\s+TABLE",
                formatKeyword("TRUNCATE TABLE"));
        result = result.replaceAll("\\s+", " ");

        return result.trim();
    }
}