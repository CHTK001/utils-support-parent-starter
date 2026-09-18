package com.chua.datasync.agent.support.exception;

/**
* 数据同步错误码枚举。
* <p>
* 错误码格式：DSYNC-{类别}-{编号}
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public enum DataSyncErrorCode {

    // ==================== 通用错误 (1xxx) ====================
    /** 未知错误 */
    UNKNOWN(1000, "未知错误"),

    /** 初始化失败 */
    INIT_FAILED(1001, "初始化失败"),

    /** 关闭/清理失败 */
    CLEANUP_FAILED(1002, "关闭/清理失败"),

    // ==================== 配置错误 (2xxx) ====================
    /** 缺少必要配置 */
    CONFIG_MISSING(2001, "缺少必要配置: {0}"),

    /** 配置无效 */
    CONFIG_INVALID(2002, "配置无效: {0}"),

    /** 映射配置缺失 */
    MAPPING_NOT_FOUND(2003, "未找到映射配置: {0}"),

    /** 触发器配置错误 */
    TRIGGER_INVALID(2004, "触发器配置无效: {0}"),

    // ==================== Source 错误 (3xxx) ====================
    /** 源 未找到 */
    SOURCE_NOT_FOUND(3001, "未找到 Source: {0}"),

    /** 源 读取失败 */
    SOURCE_READ_FAILED(3002, "Source 读取失败: {0}, 原因: {1}"),

    /** 源 连接失败 */
    SOURCE_CONNECT_FAILED(3003, "Source 连接失败: {0}, 原因: {1}"),

    /** 源 资源未找到 */
    SOURCE_RESOURCE_NOT_FOUND(3004, "Source 资源不存在: {0}"),

    // ==================== Sink 错误 (4xxx) ====================
    /** Sink 未找到 */
    SINK_NOT_FOUND(4001, "未找到 Sink: {0}"),

    /** Sink 写入失败 */
    SINK_WRITE_FAILED(4002, "Sink 写入失败: {0}, 原因: {1}"),

    /** Sink 连接失败 */
    SINK_CONNECT_FAILED(4003, "Sink 连接失败: {0}, 原因: {1}"),

    // ==================== 网络错误 (5xxx) ====================
    /** 网络连接超时 */
    NETWORK_TIMEOUT(5001, "网络连接超时: {0}"),

    /** 网络连接失败 */
    NETWORK_CONNECT_FAILED(5002, "网络连接失败: {0}, 原因: {1}"),

    /** 网络断开 */
    NETWORK_DISCONNECTED(5003, "网络断开: {0}"),

    // ==================== 数据库错误 (6xxx) ====================
    /** 数据库连接失败 */
    DB_CONNECT_FAILED(6001, "数据库连接失败: {0}, 原因: {1}"),

    /** 数据库查询失败 */
    DB_QUERY_FAILED(6002, "数据库查询失败: {0}, SQL: {1}, 原因: {2}"),

    /** 数据库写入失败 */
    DB_WRITE_FAILED(6003, "数据库写入失败: {0}, SQL: {1}, 原因: {2}"),

    /** 数据库事务失败 */
    DB_TRANSACTION_FAILED(6004, "数据库事务失败: {0}, 原因: {1}"),

    /** 数据库连接池耗尽 */
    DB_POOL_EXHAUSTED(6005, "数据库连接池耗尽: {0}"),

    // ==================== 文件错误 (7xxx) ====================
    /** 文件未找到 */
    FILE_NOT_FOUND(7001, "文件未找到: {0}"),

    /** 文件读取失败 */
    FILE_READ_FAILED(7002, "文件读取失败: {0}, 原因: {1}"),

    /** 文件写入失败 */
    FILE_WRITE_FAILED(7003, "文件写入失败: {0}, 原因: {1}"),

    /** 文件权限不足 */
    FILE_PERMISSION_DENIED(7004, "文件权限不足: {0}"),

    // ==================== 字段映射错误 (8xxx) ====================
    /** 字段映射配置错误 */
    MAPPING_FIELD_INVALID(8001, "字段映射配置错误: {0}"),

    /** 字段缺失 */
    MAPPING_FIELD_MISSING(8002, "字段缺失: {0}"),

    /** 字段类型不匹配 */
    MAPPING_TYPE_MISMATCH(8003, "字段类型不匹配: {0}, 期望: {1}, 实际: {2}"),

    // ==================== 方向约束错误 (9xxx) ====================
    /** 源 方向错误 */
    SOURCE_DIRECTION_ERROR(9001, "Source 方向错误，应为 INPUT: sourceId={0}, direction={1}"),

    /** Sink 方向错误 */
    SINK_DIRECTION_ERROR(9002, "Sink 方向错误，应为 OUTPUT: sinkId={0}, direction={1}"),

    /**
     * 方向不匹配
     * @param 9003 方法入参 9003
     */
    DIRECTION_MISMATCH(9003, "Source/Sink 方向不匹配: source={0}, sink={1}");

    /** 代码 */
    private final int code;
    /** 模板 */
    private final String template;

    /**
     * 构造方法，创建 数据SyncError编码 实例。
     *
     * @param code 编码，不允许为 null
     * @param template 模板，不允许为 null
     */
    DataSyncErrorCode(int code, String template) {
        this.code = code;
        this.template = template;
    }

    /**
    * 获取错误码数字。
    *
    * @return 错误码
    */
    public int code() {
        return code;
    }

    /**
    * 获取格式化模板。
    *
    * @return 模板字符串
    */
    public String template() {
        return template;
    }

    /**
    * 格式化错误消息。
    *
    * @param args 参数
    * @return 格式化后的错误消息
    */
    public String format(Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        String result = template;
        for (Object arg : args) {
            result = result.replaceFirst("\\{0\\}", arg != null ? arg.toString() : "null");
        }
        return result;
    }

    /**
    * 获取完整错误码字符串。
    *
    * @return DSYNC-{code} 格式的字符串
    */
    public String codeString() {
        return "DSYNC-" + code;
    }

    /**
    * 获取带错误码的错误消息。
    *
    * @param args 参数
    * @return 格式化的错误消息，含错误码前缀
    */
    public String formatWithCode(Object... args) {
        return codeString() + " " + format(args);
    }
}
