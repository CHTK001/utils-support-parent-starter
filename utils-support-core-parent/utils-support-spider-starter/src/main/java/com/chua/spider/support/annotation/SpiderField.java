package com.chua.spider.support.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 爬虫字段映射注解。
 *
 * <p>标记 POJO 字段如何从爬取结果中提取数据，支持两种提取方式：
 *
 * <h3>方式1：CSS 选择器提取（静态页面）</h3>
 * <pre>{@code
 * @SpiderField(selector = "h1.article-title", attr = "text")
 * private String title;
 *
 * @SpiderField(selector = "meta[property=article:author]", attr = "content")
 * private String author;
 *
 * @SpiderField(selector = "div.content", attr = "html")
 * private String contentHtml;
 * }</pre>
 *
 * <h3>方式2：AI 提取（动态/复杂页面）</h3>
 * <pre>{@code
 * @SpiderField(ai = "文章标题")
 * private String title;
 *
 * @SpiderField(ai = "作者名字")
 * private String author;
 * }</pre>
 *
 * <h3>方式3：混合提取</h3>
 * <pre>{@code
 * @SpiderField(selector = "h1.title")    // CSS 优先
 * private String title;
 *
 * @SpiderField(ai = "文章摘要")           // AI 补充
 * private String summary;
 * }</pre>
 *
 * @since 4.0.0.42
 * @see SpiderAi
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SpiderField {

    /**
     * CSS 选择器。
     *
     * <p>用于从 HTML 中定位元素，支持 JSoup 的 CSS 选择器语法。
     * 示例：{@code "h1.title"}、{@code "div#content"}、{@code "meta[name=description]"}
     * <p>设置此值时将通过 CSS 选择器从页面 HTML 中提取数据。
     * 如果同时设置了 {@link #ai()}，则 CSS 选择器优先。
     *
     * @return CSS 选择器
     */
    String selector() default "";

    /**
     * 提取属性。
     *
     * <p>当设置了 {@link #selector()} 时，指定从匹配元素中提取哪个属性值：
     * <ul>
     *   <li>{@code "text"} — 元素的文本内容（默认）</li>
     *   <li>{@code "html"} — 元素的内部 HTML</li>
     *   <li>{@code "href"} — 链接地址</li>
     *   <li>{@code "src"} — 图片地址</li>
     *   <li>{@code "content"} — meta 标签的 content 属性</li>
     *   <li>其他任意 HTML 属性名</li>
     * </ul>
     *
     * @return 要提取的属性名，默认 "text"
     */
    String attr() default "text";

    /**
     * AI 提取指令。
     *
     * <p>描述该字段应该如何通过 AI 从页面内容中提取。
     * 例如：{@code "文章标题"}、{@code "作者名字"}、{@code "发布时间"}。
     * <p>需要配合 {@link SpiderAi} 类级注解一起使用才能生效。
     * 如果同时设置了 {@link #selector()}，则以 selector 为准。
     *
     * @return AI 提取指令描述
     */
    String ai() default "";

    /**
     * 默认值。
     *
     * <p>当提取结果为空时使用的默认值。
     *
     * @return 默认值
     */
    String defaultValue() default "";
}
