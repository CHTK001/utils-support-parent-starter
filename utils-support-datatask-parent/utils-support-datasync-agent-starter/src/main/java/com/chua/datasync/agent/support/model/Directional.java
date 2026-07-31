package com.chua.datasync.agent.support.model;

/**
 * 标记具有方向性的接口。
 * <p>
 * 用于区分 INPUT（数据源）和 OUTPUT（数据汇）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Directional {

    /**
     * 获取方向。
     *
     * @return 方向
     */
    Direction direction();
}
