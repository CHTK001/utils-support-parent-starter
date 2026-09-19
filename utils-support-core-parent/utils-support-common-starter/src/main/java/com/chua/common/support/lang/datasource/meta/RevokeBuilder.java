package com.chua.common.support.lang.datasource.meta;

/**
 * 撤销权限链式构建器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RevokeBuilder {

    /**
     * 指定取消授权目标用户。
     *
     * @param username 用户名
     * @return this
     */
    RevokeBuilder fromUser(String username);

    /**
     * 执行撤销权限语句。
     *
     * @return true 撤销成功
     */
    boolean execute();
}
