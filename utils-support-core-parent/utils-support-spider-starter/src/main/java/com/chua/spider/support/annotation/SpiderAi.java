package com.chua.spider.support.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
* 爬虫 AI 提取注解。
*
* <p>标记在 POJO 类上，定义 AI 提取的整体指令。
* 配合 {@link SpiderField#ai()} 字段级注解一起使用。
*
* <p>使用示例：
* <pre>{@code
* @SpiderAi("从文章页面中提取以下信息")
* public class Article {
*     @SpiderField(ai = "文章标题")
*     private String title;
*
*     @SpiderField(ai = "作者名字")
*     private String author;
*
*     @SpiderField(ai = "发布时间，格式 yyyy-MM-dd")
*     private String publishDate;
* }
*
* // 使用
* Spider.create()
*     .addUrl("https://example.com/article")
*     .pipeline(SpiderMappingPipeline.of(Article.class, article -> {
*         System.out.println(article.getTitle());
*     }))
*     .run();
* }</pre>-> {
* 系统.出.println(article.获取title());
*     }))
* .运行();
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see SpiderField
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SpiderAi {

    /**
    * AI 提取指令。
    *
    * <p>描述希望 AI 从页面内容中提取哪些信息，
    * 作为 对话客户端 的 系统 提示符 发送给 AI 模型。
    *
    * <p>例如：{@code "从新闻文章中提取标题、作者、发布时间和正文内容"}。
    * AI 会根据这个指令 + 每个字段的 {@link SpiderField#ai()} 描述，
    * 自动生成结构化的 JSON 输出并映射到 POJO。
    *
    * @return AI 提取指令
     */
    String value() default "";

    /**
    * AI 模型名称。
    *
    * <p>可选，指定使用的 AI 模型，如 {@code "gpt-4o"}、{@code "deepseek-chat"}。
    * 不指定时使用 对话客户端 默认模型。
    *
    * @return 模型名称
     */
    String model() default "";
}
