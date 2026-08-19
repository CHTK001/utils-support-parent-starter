package com.chua.lucene.support.engine;

/**
 * Lucene 字段名常量。
 *
 * @since 4.0.0.42
 */
public final class LuceneFields {

    /**
     * 文档 id 字段名
     */
    public static final String ID = "id";

    /**
     * Lucene 内部版本字段
     */
    public static final String VERSION = "_version_";

    /**
     * 全文检索默认字段
     */
    public static final String CONTENT = "content";

    /**
     * 私有构造。
     */
    private LuceneFields() {
    }
}
