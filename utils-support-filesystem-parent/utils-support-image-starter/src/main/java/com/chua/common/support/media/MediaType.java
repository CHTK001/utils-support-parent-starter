package com.chua.common.support.media;

import java.io.Serializable;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MediaType implements Serializable {

    /** 类型 */
    private final String type;
    /** Subtype */
    private final String subtype;
    /** 字符集 */
    private final String charset;

    public MediaType(String type, String subtype, String charset) {
        this.type = type;
        this.subtype = subtype;
        this.charset = charset;
    }

    public MediaType(String type, String subtype) {
        this(type, subtype, null);
    }

    public String getType() {
        return type;
    }

    public String getSubtype() {
        return subtype;
    }

    public String getCharset() {
        return charset;
    }

    public String toString() {
        if (charset != null) {
            return type + "/" + subtype + "; charset=" + charset;
        }
        return type + "/" + subtype;
    }
}
