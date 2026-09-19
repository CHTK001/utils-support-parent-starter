package com.chua.runtime.plugin;

/**
 * 字节码插桩点枚举 — 定义所有可插桩的字节码位置。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum InterceptPoint {

    /**
     * 方法入口 — 方法执行前插入
     */
    ENTRY("entry"),

    /**
     * 方法出口 — 方法正常返回后插入
     */
    EXIT("exit"),

    /**
     * 异常出口 — 方法抛出异常时插入
     */
    EXCEPTION("exception"),

    /**
     * 日志方法调用前 — 日志记录器.信息/调试/错误 等调用前
     */
    LOG_PRE("log_pre"),

    /**
     * 日志方法调用后 — 日志记录器.信息/调试/错误 等调用后
     */
    LOG_POST("log_post"),

    /**
     * 套接字 连接前
     */
    NET_CONNECT_PRE("net_connect_pre"),

    /**
     * 套接字 连接后
     */
    NET_CONNECT_POST("net_connect_post"),

    /**
     * 套接字 读取前
     */
    NET_READ_PRE("net_read_pre"),

    /**
     * 套接字 写入前
     */
    NET_WRITE_PRE("net_write_pre"),

    /**
     * 文件打开前
     */
    FILE_OPEN_PRE("file_open_pre"),

    /**
     * 文件打开后
     */
    FILE_OPEN_POST("file_open_post"),

    /**
     * 文件读取前
     */
    FILE_READ_PRE("file_read_pre"),

    /**
     * HTTP 请求发送前
     */
    HTTP_REQUEST_PRE("http_request_pre"),

    /**
     * HTTP 响应接收后
     */
    HTTP_RESPONSE_POST("http_response_post"),

    /**
     * 数据库 SQL 执行前
     */
    DB_SQL_PRE("db_sql_pre"),

    /**
     * 数据库 SQL 执行后
     */
    DB_SQL_POST("db_sql_post"),

    /**
     * 线程创建前
     */
    THREAD_CREATE_PRE("thread_create_pre"),

    /**
     * 类加载前
     */
    CLASS_LOAD_PRE("class_load_pre"),

    /**
     * 方法执行耗时统计
     */
    DURATION("duration");

    /**
     * 插桩点标识
     */
    private final String key;

    /**
     * 构造方法，创建 InterceptPoint 实例。
     *
     * @param key 键，不允许为 null
     */
    InterceptPoint(String key) {
        this.key = key;
    }

    /**
     * 获取键
     *
     * @return 获取键的结果
     */
    public String getKey() {
        return key;
    }

    /**
     * 根据 键 获取插桩点。
     *
     * @param key 插桩点 键
     * @return 插桩点
     */
    public static InterceptPoint of(String key) {
        for (InterceptPoint point : values()) {
            if (point.key.equals(key)) {
                return point;
            }
        }
        return null;
    }
}