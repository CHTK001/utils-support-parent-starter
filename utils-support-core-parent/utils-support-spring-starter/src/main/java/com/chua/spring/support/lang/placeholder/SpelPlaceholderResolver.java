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
* Spring spel 占位符解析器，支持 {@code #{...}} 表达式。
*
* <p>当文本中出现 {@code #{...}} 格式的表达式时，交给 Spring SpEL 引擎求值。
* 支持 spel 的全部能力：属性访问、方法调用、条件运算、Bean 引用等。</p>
*
* <p><b>支持 Bean 引用：</b>在 Spring 环境中注册 {@link BeanFactoryResolver}，
* 表达式可通过 {@code @beanName} 引用容器中的 Bean，例如
* {@code #{@config.getTimeout()}}。非 Spring 环境回退为纯表达式求值。</p>
*
* <p>本实现通过 {@code @Spi("spel")} 注册到注解式 SPI 框架，
* 被占位符解析链自动发现与调用。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("spel")
@ConditionalOnClass("org.springframework.expression.spel.standard.SpelExpressionParser")
public class SpelPlaceholderResolver implements PlaceholderResolver {

    /**
    * spel 表达式解析器，线程安全，可复用
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    @Override
    /** 解析Placeholder */
    public String resolvePlaceholder(String placeholderName) {
        if (placeholderName == null) {
            return null;
        }
 // 仅处理 #{...} 格式的 spel 表达式，其他格式交给链中的下一个解析器
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

    @Override
    /** 获取财产 */
    public String getProperty(String key) {
        return null;
    }

    /**
    * 创建 Spring Bean 感知的 spel 求值上下文。
    *
    * <p>Spring 环境中注册 {@link BeanFactoryResolver}，使表达式可通过
    * {@code @beanName} 引用容器 Bean；非 Spring 环境仅支持纯表达式求值。</p>
    *
    * @return 绑定 Bean 解析器的求值上下文
     */
    private StandardEvaluationContext createEvaluationContext() {
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (SpringBeanUtils.getApplicationContextOrNull() != null) {
            context.setBeanResolver(new BeanFactoryResolver(SpringBeanUtils.getApplicationContext()));
        }
        return context;
    }
}