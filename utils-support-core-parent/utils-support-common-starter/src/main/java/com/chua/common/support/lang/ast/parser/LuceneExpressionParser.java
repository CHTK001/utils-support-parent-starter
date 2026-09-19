package com.chua.common.support.lang.ast.parser;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.spi.annotations.Spi;

/**
 * Lucene 查询表达式解析器
 *
 * <p>支持 Lucene 查询语法解析和生成。
 *
 * <h3>支持的语法</h3>
 * <pre>
 *   name:张三 AND age:[18 TO *]
 *   status:active OR role:admin
 *   -deleted:true AND title:测试
 *   price:[100 TO 500]
 * </pre>
 *
 * <h3>Lucene 特有语法映射</h3>
 * <ul>
 *   <li>AND → BTreeNode.logic("AND", ...)</li>
 *   <li>OR → BTreeNode.logic("OR", ...)</li>
 *   <li>-field:value → BTreeNode.not(compare("=", column, value))</li>
 *   <li>field:[min TO max] → BTreeNode.compare("BETWEEN", ...)</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi("lucene")
public class LuceneExpressionParser implements ExpressionParser {

    /**
     * 类型
     */
    private static final String TYPE = "lucene";

    /** 委托客户端 */
    private final DefaultExpressionParser delegate = new DefaultExpressionParser();

    @Override
    /** Type */
    public String type() {
        return TYPE;
    }

    @Override
    /** 解析 */
    public BTreeNode parse(String expression) {
        // Lucene 语法预处理：将 field:value 转为 field = value
        String normalized = normalizeLucene(expression);
        return delegate.parse(normalized);
    }

    @Override
    /** Generate */
    public String generate(BTreeNode tree) {
        // 将 B-Tree 还原为 Lucene 查询语法
        if (tree == null) { return ""; }
        return switch (tree.getType()) {
            case LOGIC -> {
                String left = generate(tree.getLeft());
                String right = generate(tree.getRight());
                yield left + " " + tree.getOperator() + " " + right;
            }
            case NOT -> "-" + generate(tree.getRight());
            case COMPARE -> {
                String field = generate(tree.getLeft());
                String op = tree.getOperator();
                String val = generate(tree.getRight());
                if ("BETWEEN".equalsIgnoreCase(op)) {
                    yield field + ":[" + val + "]";
                }
                yield field + ":" + val;
            }
            case COLUMN -> tree.getOperator();
            case VALUE -> {
                Object v = tree.getValue();
                yield v instanceof String s ? "\"" + s + "\"" : String.valueOf(v);
            }
            default -> delegate.generate(tree);
        };
    }

    /**
     * Lucene 语法预处理
     *
     * <p>将 Lucene 特有语法转为通用表达式语法：
     * <ul>
     *   <li>field:value → field = value</li>
     *   <li>-field:value → NOT (field = value)</li>
     *   <li>field:[min TO max] → field BETWEEN min AND max</li>
     * </ul>
     * @param expression 方法入参 expression
     * @return 结果字符串
     */
    private String normalizeLucene(String expression) {
        // 简化处理，实际项目中可能需要更复杂的转换
        String result = expression;
        // field:[min TO max] → field BETWEEN min AND max
        result = result.replaceAll("(\\w+):\\[(.+?)\\s+TO\\s+(.+?)\\]", "$1 BETWEEN $2 AND $3");
        // field:value → field = value
        result = result.replaceAll("(\\w+):(\"[^\"]+\"|\\S+)", "$1 = $2");
        return result;
    }
}
