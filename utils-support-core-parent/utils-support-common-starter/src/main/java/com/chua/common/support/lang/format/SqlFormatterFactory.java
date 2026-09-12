package com.chua.common.support.lang.format;


/**
* SQL格式化器工厂类。
*
* <p><b>功能：</b>
* 根据SQL语句类型自动选择合适的格式化器实现，
* 并提供便捷的配置方法。
*
* <p><b>使用示例：</b>
* <pre>
* // 自动选择格式化器
* Formatter formatter = SqlFormatterFactory.getFormatter(sql);
* String formatted = formatter.format(sql);
*
* // 使用自定义配置的DDL格式化器
* Formatter ddlFormatter = SqlFormatterFactory.getDdlFormatter(true, true);
* </pre>
*
* @author CH
* @since 1.0.0
 */
public class SqlFormatterFactory {

    /**
    * 私有构造函数，防止实例化。
    *
    * <p>该类为纯工具类，所有方法均为静态方法，
    * 不应被实例化。
     */
    private SqlFormatterFactory() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
    * 判断是否为DDL语句类型
    *
    * @param upper 大写的SQL语句
    * @return 是否为DDL语句
     */
    private static boolean isDdlStatement(String upper) {
        return upper.startsWith("CREATE") ||
                upper.startsWith("ALTER") ||
                upper.startsWith("DROP") ||
                upper.startsWith("RENAME") ||
                upper.startsWith("TRUNCATE");
    }

    /**
    * 根据SQL语句类型自动选择合适的格式化器。
    *
    * <p><b>选择规则：</b>
    * <ul>
    *   <li>如果SQL以 CREATE、ALTER、DROP、RENAME、TRUNCATE 开头 → 使用 {@link DdlFormatter}</li>
    *   <li>其他情况（SELECT、INSERT、UPDATE、DELETE等） → 使用 {@link DmlFormatter}</li>
    * </ul>
    *
    * <p><b>注意事项：</b>
    * <ul>
    *   <li>如果SQL为 null 或空，返回 null</li>
    *   <li>只根据SQL开头判断，不做完整语法分析</li>
    *   <li>对于包含注释的SQL，需要先去除注释再判断</li>
    * </ul>
    *
    * @param sql SQL语句
    * @return 对应的格式化器实例，如果无法识别返回null
     */
    public static SqlFormatter getFormatter(String sql) {
        // 空值检查
        if (sql == null) {
            return null;
        }

        // 去除首尾空白
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // 获取SQL的前几个单词，用于判断类型
        // 注意：需要去除可能的注释前缀
        String upper = trimmed.toUpperCase();

        // 去除前导注释（-- 或 /*）
        // 简单处理：跳过注释，查找第一个非注释单词
        // 这里简化处理，实际实现可能需要更复杂的注释解析

        // 判断是否为DDL语句
        if (isDdlStatement(upper)) {
            // DDL语句 → 使用DDL格式化器
            return new DdlFormatter();
        } else {
            // DML语句（SELECT、INSERT、UPDATE、DELETE等） → 使用DML格式化器
            return new DmlFormatter();
        }
    }

    /**
    * 获取自定义配置的DDL格式化器。
    *
    * <p><b>配置参数说明：</b>
    * <ul>
    *   <li>keepComments：是否保留SQL注释（-- 和 ）</li>
    *   <li>upperCaseKeywords：是否将关键字转换为大写</li>
    * </ul>
    *
    * @param keepComments      是否保留注释
    * @param upperCaseKeywords 是否大写关键字
    * @return 配置后的DDL格式化器
     */
    public static SqlFormatter getDdlFormatter(boolean keepComments, boolean upperCaseKeywords) {
        return new DdlFormatter(keepComments, upperCaseKeywords);
    }

    /**
    * 获取自定义配置的DML格式化器。
    *
    * @param keepComments      是否保留注释
    * @param upperCaseKeywords 是否大写关键字
    * @return 配置后的DML格式化器
     */
    public static SqlFormatter getDmlFormatter(boolean keepComments, boolean upperCaseKeywords) {
        return new DmlFormatter(keepComments, upperCaseKeywords);
    }

    /**
    * 获取完全自定义配置的格式化器（通过指定类型）。
    *
    * <p><b>使用场景：</b>
    * 当需要同时控制格式化器的类型和所有配置参数时使用。
    *
    * @param type               格式化器类型（"DDL" 或 "DML"）
    * @param keepComments       是否保留注释
    * @param upperCaseKeywords  是否大写关键字
    * @param compressWhitespace 是否压缩空白
    * @return 配置后的格式化器
    * @throws IllegalArgumentException 如果type参数无效
     */
    public static SqlFormatter getFormatter(String type,
                                            boolean keepComments,
                                            boolean upperCaseKeywords,
                                            boolean compressWhitespace) {
        if ("DDL".equalsIgnoreCase(type)) {
            return new DdlFormatter(keepComments, upperCaseKeywords, compressWhitespace);
        } else if ("DML".equalsIgnoreCase(type)) {
            return new DmlFormatter(keepComments, upperCaseKeywords, compressWhitespace);
        } else {
            throw new IllegalArgumentException("Invalid formatter type: " + type + ". Expected 'DDL' or 'DML'");
        }
    }
}