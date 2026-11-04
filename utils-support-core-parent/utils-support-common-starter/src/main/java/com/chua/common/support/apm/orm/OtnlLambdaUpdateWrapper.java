package com.chua.common.support.apm.orm;

import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;

/**
 * Otnl 引擎的 Lambda 更新包装器。
 * <p>
 * 可观测数据来自 APM 后端（OAP / OTel Collector / 进程内 agent），
 * 本系统只是一个读取方，没有回写通道。因此这里把 {@link #update()}
 * 从「静默返回 0」改成显式拒绝：调用方拿到异常能立刻发现走错了通道，
 * 而不是以为「更新成功但影响 0 行」。
 * </p>
 *
 * @param <T> 实体类型
 * @author CH
 * @since 4.0.0.51
 */
public class OtnlLambdaUpdateWrapper<T> extends LambdaUpdateWrapper<T> {

    /**
     * 创建包装器
     *
     * @param entityClass 实体类型
     */
    public OtnlLambdaUpdateWrapper(Class<T> entityClass) {
        super(entityClass);
    }

    /**
     * 只读引擎拒绝更新
     *
     * @return 永不返回
     */
    @Override
    public int update() {
        throw new UnsupportedOperationException(
                "APM 引擎是只读观测通道，不支持更新: " + getEntityClass().getSimpleName());
    }
}
