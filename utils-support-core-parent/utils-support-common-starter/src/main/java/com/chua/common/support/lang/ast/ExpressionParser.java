package com.chua.common.support.lang.ast;

import org.jspecify.annotations.NullUnmarked;

/**
 * 表达式解析器 SPI 接口
 *
 * <p>定义表达式的双向转换能力：
 * <ul>
 *   <li>正向：源表达式 → B-Tree（parse）</li>
 *   <li>反向：B-Tree → 源表达式（generate）</li>
 * </ul>
 *
 * <p>通过 SPI 机制自动发现实现类，每个实现类通过 {@link #type()} 标识支持的表达式类型。
 * 不同表达式类型（SQL、GQL、Lucene 等）各自实现此接口。
 *
 * <h3>SPI 注册示例</h3>
 * <pre>{@code
 *   @Spi("sql")
 *   public class SqlExpressionParser implements ExpressionParser {
 *       public String type() { return "sql"; }
 *       public BTreeNode parse(String expression) { ... }
 *       public String generate(BTreeNode tree) { ... }
 *   }
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
public interface ExpressionParser {

    /**
     * 获取表达式类型标识
     *
     * <p>用于 SPI 查找时匹配。常见值：
     * <ul>
     *   <li>"sql" — SQL WHERE 条件表达式</li>
     *   <li>"gql" — GraphQL / Gremlin 查询表达式</li>
     *   <li>"cypher" — Neo4j Cypher 表达式</li>
     *   <li>"redisearch" — RediSearch 查询表达式</li>
     *   <li>"lucene" — Lucene 查询表达式</li>
     *   <li>"expr" — 通用表达式（如 age > 18 && status == 'active'）</li>
     * </ul>
     *
     * @return 表达式类型标识
     */
    String type();

    /**
     * 正向转换：源表达式 → B-Tree
     *
     * <p>将指定类型的表达式文本解析为 B-Tree 树结构。
     *
     * @param expression 源表达式文本
     * @return B-Tree 根节点
     * @throws IllegalArgumentException 表达式语法错误时抛出
     */
    BTreeNode parse(String expression);

    /**
     * 反向转换：B-Tree → 源表达式
     *
     * <p>将 B-Tree 树结构还原为指定类型的表达式文本。
     *
     * @param tree B-Tree 根节点
     * @return 源表达式文本
     */
    String generate(BTreeNode tree);
}
