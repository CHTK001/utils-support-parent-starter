package com.chua.common.support.lang.datasource.engine;

import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.spi.ServiceProvider;

/**
 * ORM 条件构建入口 — 把表达式文本翻译成 {@link Condition} 条件树。
 *
 * <p>本接口是 {@code ExpressionParser} 与 ORM 条件模型之间的桥。完整链路：</p>
 * <pre>{@code
 *   表达式文本
 *     --ExpressionParser.parse()--> BTreeNode（语言无关中间树）
 *     --本接口.parse()----------> Condition（ORM 条件树）
 *     --AbstractLambdaWrapper--> WHERE 片段 + 绑定参数
 * }</pre>
 *
 * <p>典型用法是让调用方用一段自由文本描述筛选条件，直接喂进 Lambda 链式查询：</p>
 * <pre>{@code
 *   List<User> users = engine.query(User.class)
 *       .eq(User::getTenantId, 42L)                                  // 固定条件
 *       .and(engine.orm().parse("age > 18 AND status = 'active'"))   // 文本条件
 *       .list();
 * }</pre>
 *
 * <p><b>为什么产出 {@code Condition} 而不是表达式文本</b>：条件树会被
 * {@code AbstractLambdaWrapper#buildWhere} 渲染成 {@code ?} 占位符形式，
 * 列名过白名单校验、值一律参数化绑定。直接把表达式文本拼进 SQL 会绕过这两层防护。</p>
 *
 * <p><b>实现是无状态的</b>：翻译只依赖表达式树本身，不感知引擎、数据源与方言。
 * 因此默认实现对所有引擎通用，各引擎无需覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Engine#orm()
 * @see ExpressionParser
 */
public interface EngineOrm {

    /**
     * {@link #parse(String)} 使用的默认表达式解析器 SPI 扩展键。
     */
    String DEFAULT_PARSER = "expr";

    /**
     * 用指定解析器把表达式文本翻译为 ORM 条件树。
     *
     * <p>调用方显式给出源语言解析器，因此可以用 Cypher / Lucene 等方言的解析器
     * 解析，再降级为 ORM 条件（前提是表达式只含 ORM 支持的算子）。</p>
     *
     * @param expression 表达式文本，不允许为 null 或空白
     * @param parser     表达式解析器，不允许为 null
     * @return 条件树根节点（非 null）
     * @throws IllegalArgumentException          表达式为空、语法错误或含 ORM 不支持的算子时抛出
     * @throws UnsupportedOperationException    解析器为 null 时抛出
     */
    Condition parse(String expression, ExpressionParser parser);

    /**
     * 用默认解析器（{@value #DEFAULT_PARSER}）把表达式文本翻译为 ORM 条件树。
     *
     * @param expression 表达式文本，不允许为 null 或空白
     * @return 条件树根节点（非 null）
     * @throws IllegalArgumentException      表达式为空、语法错误或含 ORM 不支持的算子时抛出
     * @throws IllegalStateException        默认解析器未注册时抛出
     */
    default Condition parse(String expression) {
        ExpressionParser parser =
                ServiceProvider.of(ExpressionParser.class).getExtension(DEFAULT_PARSER);
        if (parser == null) {
            throw new IllegalStateException("未注册的表达式解析器 SPI 扩展: " + DEFAULT_PARSER
                    + "，请检查 META-INF/extensions/ 是否包含该实现");
        }
        return parse(expression, parser);
    }
}