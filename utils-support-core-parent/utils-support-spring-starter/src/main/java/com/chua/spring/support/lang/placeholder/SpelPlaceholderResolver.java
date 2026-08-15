package com.chua.spring.support.lang.placeholder;

import com.chua.common.support.lang.placeholder.PlaceholderResolver;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spring.support.configuration.SpringBeanUtils;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * Spring SpEL 占位符解析器，支持 {@code #{...}} 表达式。
 *
 * <p>当文本中存在 {@code #{...}} 格式的表达式时，使用 Spring SpEL 引擎解析。
 * 支持 SpEL 的全部特性：方法调用、属性访问、条件运算等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("spel")
@ConditionalOnClass("org.springframework.expression.spel.standard.SpelExpressionParser")
public class SpelPlaceholderResolver implements PlaceholderResolver {

    /**
     * SpEL 表达式解析器
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    @Override
    public String resolvePlaceholder(String placeholderName) {
        if (placeholderName == null) {
            return null;
        }
        if (placeholderName.startsWith("#{") && placeholderName.endsWith("}")) {
            String expr = placeholderName.substring(2, placeholderName.length() - 1);
            try {
                Object value = parser.parseExpression(expr).getValue(createEvaluationContext());
                return value != null ? value.toString() : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 创建 Spring Bean 感知的 SpEL 求值上下文，支持 {@code @beanName} 引用。
     */
    private StandardEvaluationContext createEvaluationContext() {
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (SpringBeanUtils.getApplicationContextOrNull() != null) {
            context.setBeanResolver(new BeanFactoryResolver(SpringBeanUtils.getApplicationContext()));
        }
        return context;
    }

    @Override
    public String getProperty(String key) {
        return null;
    }
}