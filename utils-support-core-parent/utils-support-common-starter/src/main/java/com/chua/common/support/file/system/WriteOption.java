package com.chua.common.support.file.system;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class WriteOption {
    /** 写入类型 */
    private WriteType writeType = WriteType.MAP;
    /**
     * 字符集
     */
    private Charset charset = StandardCharsets.UTF_8;
    /** 是否自动关闭流 */
    private boolean autoCloseStream;
    /** 扩展属性集合 */
    private Map<String, Object> attributes = new HashMap<>();

    public static WriteOption maps() {
        WriteOption option = new WriteOption();
        option.writeType = WriteType.MAP;
        return option;
    }

    public WriteOption withAutoCloseStream(boolean autoCloseStream) {
        this.autoCloseStream = autoCloseStream;
        return this;
    }

    public WriteOption withCharset(Charset charset) {
        this.charset = charset;
        return this;
    }

    public Object get(String key) {
        return attributes.get(key);
    }

    public boolean isAutoCloseStream() {
        return autoCloseStream;
    }

    public WriteType getWriteType() {
        return writeType;
    }

    public Charset getCharset() {
        return charset;
    }

    public enum WriteType {
        MAP, LINE
    }
}
