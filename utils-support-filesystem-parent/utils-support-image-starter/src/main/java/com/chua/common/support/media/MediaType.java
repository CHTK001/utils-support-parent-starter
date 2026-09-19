package com.chua.common.support.media;

import java.io.Serializable;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MediaType implements Serializable {
    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
     * 类型
    */
    private final String type;
    /**
     * Subtype
    */
    private final String subtype;
    /**
     * 字符集
    */
    private final String charset;

    /**
     * 创建 media类型 实例
     * @param type 类型
     * @param subtype subtype
     * @param charset 字符集
     */
    public MediaType(String type, String subtype, String charset) {
        this.type = type;
        this.subtype = subtype;
        this.charset = charset;
    }

    /**
     * 创建 media类型 实例
     * @param type 类型
     * @param subtype subtype
     */
    public MediaType(String type, String subtype) {
        this(type, subtype, null);
    }

    /**
     * 获取类型
     *
     * @return 获取类型的结果
     */
    public String getType() {
        return type;
    }

    /**
     * 获取Subtype
     *
     * @return 获取subtype的结果
     */
    public String getSubtype() {
        return subtype;
    }

    /**
     * 获取字符集
     *
     * @return 获取字符集的结果
     */
    public String getCharset() {
        return charset;
    }

    /**
     * 转为字符串
     *
     * @return 转为字符串的结果
     */
    public String toString() {
        if (charset != null) {
            return type + "/" + subtype + "; charset=" + charset;
        }
        return type + "/" + subtype;
    }
}
