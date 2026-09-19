package com.chua.common.support.lang.ast.parser;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.spi.annotations.Spi;

/**
 * SQL 表达式解析器
 *
 * <p>支持 SQL WHERE 子句中的条件表达式解析和生成。
 *
 * <h3>支持的语法</h3>
 * <pre>
 *   age > 18 AND status = 'active'
 *   name LIKE '%test%' OR id IN (1, 2, 3)
 *   created_at BETWEEN '2024-01-01' AND '2024-12-31'
 *   deleted_at IS NULL
 *   score >= 60 AND score <= 100
 * </pre>
 *
 * <h3>示例</h3>
 * <pre>{@code
 *   ExpressionParser parser = new SqlExpressionParser();
 *
 *   // 解析
 *   BTreeNode tree = parser.parse("age > 18 AND status = 'active'");
 *
 *   // 生成
 *   String sql = parser.generate(tree);
 *   // → "(age > 18 AND status = 'active')"
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi("sql")
public class SqlExpressionParser implements ExpressionParser {

    /**
     * 类型
     */
    private static final String TYPE = "sql";

    /**
     * 内部委托的默认解析器
     *
     * <p>SQL WHERE 条件的语法与通用表达式基本一致，
     * 直接复用 {@link DefaultExpressionParser} 的解析逻辑。
     */
    private final DefaultExpressionParser delegate = new DefaultExpressionParser();

    @Override
    /** Type */
    public String type() {
        return TYPE;
    }

    @Override
    /** 解析 */
    public BTreeNode parse(String expression) {
        return delegate.parse(expression);
    }

    @Override
    /** Generate */
    public String generate(BTreeNode tree) {
        return delegate.generate(tree);
    }
}
