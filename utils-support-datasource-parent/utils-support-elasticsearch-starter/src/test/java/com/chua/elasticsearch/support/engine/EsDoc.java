package com.chua.elasticsearch.support.engine;

/**
 * Elasticsearch 引擎集成测试用实体。
 *
 * <p>经 JacksonJsonpMapper 序列化为索引文档。</p>
 *
 * @author CH
 */
public class EsDoc {

    /** 主键 */
    private Long id;

    /** 标题 */
    private String title;

    /** 正文 */
    private String content;

    /** 无参构造器 */
    public EsDoc() {
    }

    /**
     * 全参构造器
     * @param id ID，不允许为 null
     * @param title 标题，不允许为 null
     * @param content 内容，不允许为 null
     */
    public EsDoc(Long id, String title, String content) {
        this.id = id;
        this.title = title;
        this.content = content;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
