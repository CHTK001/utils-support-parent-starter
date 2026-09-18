package com.chua.protocol.support.network.net;

/**
* 用户-Agent 解析值对象。
*
* @author CH
* @since 4.0.0.42
 */
public class UserAgent {

    /** 原始 用户-Agent 字符串 */
    private String raw;
    /** 浏览器名称 */
    private String browser;
    /** 操作系统名称 */
    private String operatingSystem;

    /** 默认构造 */
    public UserAgent() {
    }

    /**
    * 构造 用户-Agent。
    *
    * @param raw 原始字符串
    */
    public UserAgent(String raw) {
        this.raw = raw;
    }

    /**
    * 获取原始字符串
    *
    * @return 获取raw的结果
    */
    public String getRaw() {
        return raw;
    }

    /**
    * 设置原始字符串
    *
    * @param raw raw
    */
    public void setRaw(String raw) {
        this.raw = raw;
    }

    /**
    * 获取浏览器名称
    *
    * @return 获取browser的结果
    */
    public String getBrowser() {
        return browser;
    }

    /**
    * 获取操作系统名称
    *
    * @return 获取operating系统的结果
    */
    public String getOperatingSystem() {
        return operatingSystem;
    }

    /**
    * 从原始字符串解析 用户-Agent。
    *
    * @param uaString 用户-Agent 原始字符串
    * @return 解析后的 用户Agent 实例
    */
    public static UserAgent parseUserAgentString(String uaString) {
        UserAgent ua = new UserAgent(uaString);
        if (uaString == null || uaString.isBlank()) {
            return ua;
        }
        // 简单浏览器识别
        if (uaString.contains("Chrome")) {
            ua.browser = "Chrome";
        } else if (uaString.contains("Firefox")) {
            ua.browser = "Firefox";
        } else if (uaString.contains("Safari") && !uaString.contains("Chrome")) {
            ua.browser = "Safari";
        } else if (uaString.contains("Edge")) {
            ua.browser = "Edge";
        } else if (uaString.contains("MSIE") || uaString.contains("Trident")) {
            ua.browser = "IE";
        }
        // 简单操作系统识别
        if (uaString.contains("Windows")) {
            ua.operatingSystem = "Windows";
        } else if (uaString.contains("Mac OS")) {
            ua.operatingSystem = "macOS";
        } else if (uaString.contains("Linux")) {
            ua.operatingSystem = "Linux";
        } else if (uaString.contains("Android")) {
            ua.operatingSystem = "Android";
        } else if (uaString.contains("iPhone") || uaString.contains("iPad")) {
            ua.operatingSystem = "iOS";
        }
        return ua;
    }
}
