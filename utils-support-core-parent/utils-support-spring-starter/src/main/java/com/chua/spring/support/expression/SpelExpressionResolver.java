package com.chua.spring.support.expression;

import com.chua.common.support.expression.ExpressionResolver;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spring.support.configuration.SpringBeanUtils;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;

/**
 * Spring spel 表达式解析器，使用 Spring spel 引擎解析 {@code #{...}} 表达式。
 *
 * <p>由 {@link com.chua.common.support.expression.ExpressionResolvers} SPI 链自动发现。
 * 优先级高于默认实现，Spring 环境自动启用。</p>
 *
 * <p><b>能力：</b></p>
 * <ul>
 *   <li>SpEL 全语法：方法调用、属性访问、三目运算、集合操作等</li>
 *   <li>{@code @beanName} 引用 Spring 容器中的 Bean</li>
 *   <li>根对象绑定目标实例，{@code method}、{@code args}、{@code targetClass} 上下文变量</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "spel", order = 100)
@ConditionalOnClass("org.springframework.expression.spel.standard.SpelExpressionParser")
public class SpelExpressionResolver implements ExpressionResolver {

    /**
     * spel 表达式前缀标识
     */
    private static final String PREFIX = "#{";

    /**
     * spel 表达式后缀标识
     */
    private static final String SUFFIX = "}";

    /**
     * spel 表达式解析器，线程安全可复用
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    @Override
    /**
     * 是否支持
    */
    public boolean isSupport(String expression) {
        return expression != null && expression.startsWith(PREFIX) && expression.endsWith(SUFFIX);
    }

    @Override
    /**
     * 解析
    */
    public String resolve(String expression, Object root, Map<String, Object> variables) {
        if (expression == null) {
            return null;
        }
        String expr = expression.substring(PREFIX.length(), expression.length() - SUFFIX.length());
        try {
            StandardEvaluationContext context = createEvaluationContext(root, variables);
            Object value = parser.parseExpression(expr).getValue(context);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 创建 Spring Bean 感知的 spel 求值上下文。
     *
     * <p>注册 {@link BeanFactoryResolver}，表达式可通过 {@code @beanName} 引用容器 Bean；
     * 根对象绑定目标实例，变量绑定上下文数据。</p>
     *
     * @param root      根对象
     * @param variables 上下文变量
     * @return 求值上下文
     */
    private StandardEvaluationContext createEvaluationContext(Object root, Map<String, Object> variables) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (SpringBeanUtils.getApplicationContextOrNull() != null) {
            context.setBeanResolver(new BeanFactoryResolver(SpringBeanUtils.getApplicationContext()));
        }
        if (root != null) {
            context.setRootObject(root);
        }
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                context.setVariable(entry.getKey(), entry.getValue());
            }
        }
        return context;
    }
}
