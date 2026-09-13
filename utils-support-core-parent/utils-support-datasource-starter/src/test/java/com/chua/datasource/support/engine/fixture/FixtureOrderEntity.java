package com.chua.datasource.support.engine.fixture;

import com.chua.datasource.support.annotation.TableName;

/**
 * 带自定义表名注解的订单实体。
 *
 * @author CH
 */
@TableName("t_order")
public class FixtureOrderEntity {

    /** 订单编号 */
    private Long id;

    /** 用户编号 */
    private Long userId;

    /**
     * 无参构造器。
     */
    public FixtureOrderEntity() {
    }

    /**
     * 获取订单编号。
     *
     * @return 订单编号
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置订单编号。
     *
     * @param id 订单编号
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取用户编号。
     *
     * @return 用户编号
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 设置用户编号。
     *
     * @param userId 用户编号
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
