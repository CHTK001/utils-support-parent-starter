package com.chua.common.support.enums;


/**
 * 用户操作类型枚举，用于标识日志/审计中常见的业务动作。
 *
 * <p>常用于以下场景：</p>
 * <ul>
 *     <li>用户操作日志中的动作分类</li>
 *     <li>审计记录中的行为类型标记</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum Action {

    /**
     * 无动作
     */
    NONE,

    /**
     * 新建
     */
    CREATE,

    /**
     * 更新
     */
    UPDATE,

    /**
     * 删除
     */
    DELETE,

    /**
     * 查询
     */
    QUERY,

    /**
     * 登录
     */
    LOGIN,

    /**
     * 登出
     */
    LOGOUT,

    /**
     * 导出
     */
    EXPORT,

    /**
     * 导入
     */
    IMPORT,

    /**
     * 其他
     */
    OTHER
}
