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

    /**
     * Maps
     * @return 写入Option 对象
     */
    public static WriteOption maps() {
        WriteOption option = new WriteOption();
        option.writeType = WriteType.MAP;
        return option;
    }

    /**
     * WithAuto关闭Stream
     * @param autoCloseStream auto关闭流（布尔开关）
     * @return 写入Option 对象
     */
    public WriteOption withAutoCloseStream(boolean autoCloseStream) {
        this.autoCloseStream = autoCloseStream;
        return this;
    }

    /**
     * WithCharset
     * @param charset 字符集，不允许为 null
     * @return 写入Option 对象
     */
    public WriteOption withCharset(Charset charset) {
        this.charset = charset;
        return this;
    }

    /**
     * 获取
     * @param key 键，不允许为 null
     * @return 对象 对象
     */
    public Object get(String key) {
        return attributes.get(key);
    }

    /**
     * 是否Auto关闭Stream
     * @return 是否成功（true 表示成功）
     */
    public boolean isAutoCloseStream() {
        return autoCloseStream;
    }

    /**
     * 获取写入Type
     * @return 写入类型 对象
     */
    public WriteType getWriteType() {
        return writeType;
    }

    /**
     * 获取Charset
     * @return 字符集 对象
     */
    public Charset getCharset() {
        return charset;
    }

    public enum WriteType {
        MAP, LINE
    }
}
