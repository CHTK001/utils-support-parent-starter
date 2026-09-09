package com.chua.protocol.support.network.net;

/**
 * User-Agent 解析值对象。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class UserAgent {

    /** 原始 User-Agent 字符串 */
    private String raw;

    /** 默认构造 */
    public UserAgent() {
    }

    /**
     * 构造 User-Agent。
     *
     * @param raw 原始字符串
     */
    public UserAgent(String raw) {
        this.raw = raw;
    }

    /** 获取原始字符串 */
    public String getRaw() {
        return raw;
    }

    /** 设置原始字符串 */
    public void setRaw(String raw) {
        this.raw = raw;
    }
}