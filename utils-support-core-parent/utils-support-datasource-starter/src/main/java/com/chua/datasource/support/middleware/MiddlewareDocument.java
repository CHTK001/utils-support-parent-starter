package com.chua.datasource.support.middleware;

/**
 * 单条详情（文档 / 消息 / 键值 …），宿主渲染成 JSON 或文本。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MiddlewareDocument {

    /** 详情标题（主题名 / 集合名 …） */
    private final String title;

    /** 正文：优先 JSON 文本 */
    private final String text;

    /** 附加属性（分区、offset、时间戳、键 …），按插入顺序展示 */
    private final java.util.Map<String, String> attributes;

    /**
     * 全字段构造。
     *
     * @param title      标题
     * @param text       正文
     * @param attributes 附加属性
     */
    public MiddlewareDocument(String title, String text, java.util.Map<String, String> attributes) {
        this.title = title;
        this.text = text;
        this.attributes = attributes == null ? java.util.Map.of() : java.util.Map.copyOf(attributes);
    }

    /**
     * 紧凑构造：只有标题与正文。
     *
     * @param title 标题
     * @param text  正文
     * @return 文档对象
     */
    public static MiddlewareDocument of(String title, String text) {
        return new MiddlewareDocument(title, text, java.util.Map.of());
    }

    /**
     * @return 标题
     */
    public String title() {
        return title == null ? "" : title;
    }

    /**
     * @return 正文
     */
    public String text() {
        return text == null ? "" : text;
    }

    /**
     * @return 附加属性
     */
    public java.util.Map<String, String> attributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "MiddlewareDocument{title=" + title() + ", textLength=" + text().length() + '}';
    }

    // ==== Jackson getters (generated) ====
    // 本类用 fluent 访问器（xxx()）对外，Jackson 默认只认 getXxx()/isXxx()，
    // 因此这里补一层 getter 别名；既有调用方不受影响。

    /**
     * Jackson getter，对应 {@link #title()}。
     *
     * @return title
     */
    public String getTitle() {
        return title();
    }

    /**
     * Jackson getter，对应 {@link #text()}。
     *
     * @return text
     */
    public String getText() {
        return text();
    }

    /**
     * Jackson getter，对应 {@link #attributes()}。
     *
     * @return attributes
     */
    public java.util.Map<String, String> getAttributes() {
        return attributes();
    }
}
